# Runtime Token 预算与用量统计方案

## 为什么做

agent-runtime 现有循环预算只覆盖三类硬阈值：轮数（max_turns）、工具调用次数（max_tool_calls）、连续失败次数（max_failures），全部由 `LoopController.evaluate_budget` 统一仲裁。这三类阈值只能间接约束成本：一个 12 轮的 Loop 里每轮 Planner 都可能携带很长的上下文，token 消耗和费用完全不可见也不可控。这与仓库 Agent 开发理念中"循环边界必须齐备：max_turns、token/费用预算、permission mode、compaction、checkpoint、人类中断，缺一不可"的要求存在缺口。

现状问题具体有三点：

第一，非流式路径 `OpenAICompatibleClient.chat()` 虽然返回 usage，但调用方（TaskUnderstandingService、RuntimePlanner、resume 工具）全部丢弃，没有任何 run 级累计。第二，流式路径 `stream_chat()` 完全不采集 usage，而流式问答恰恰是智能引擎的生产主链路。第三，`done` 事件的 metrics 只携带请求缓存指标（hits/misses/stores），Java 后端与前端无法展示单次运行的 token 消耗，评估侧也无法把成本纳入回归指标。

## 方案是什么

引入 run 级 token 用量统计与 token 预算两个能力，保持"预算阈值唯一来源在 LoopController"的既有架构不变：

1. 用量统计：新增 `app/core/llm/usage.py`，基于 `contextvars.ContextVar` 实现 per-run 的 token 累计器。Executor 在每次 run 开始时开启追踪，`OpenAICompatibleClient` 在每次真实模型调用完成后把 usage 写入当前 run 的累计器。contextvars 随 asyncio 任务树自动传播（`asyncio.gather` 派生的子任务继承同一累计器对象），因此 TaskUnderstanding、Planner、工具内 LLM 调用无需修改任何函数签名即可被统计；并发请求各自持有独立上下文，互不污染。这解决了"LLM 客户端是进程级共享单例、无法在客户端上挂 per-run 计数"的核心矛盾。

2. token 预算：`BudgetConfig` 新增 `max_tokens` 字段（0 表示不限，默认 0，保持完全向后兼容），`RuntimeConfig` 新增同名默认值配置。`LoopController.evaluate_budget` 在既有三项检查之后追加 token 检查：run 累计 total_tokens 达到阈值即返回 `BudgetDecision(blocked=True)`，stop_reason 使用新增的 `StopReason.TOKEN_BUDGET_EXCEEDED`。累计器字典本身挂到 graph state 的 `token_usage` 键上（同一可变对象，客户端写入后 LoopController 读到的即时值），无需任何 graph 节点参与搬运。

3. 流式 usage 采集：OpenAI 兼容分支在流式请求 payload 中附加 `stream_options: {"include_usage": true}`（仅对 `deepseek_api` provider 开启，与既有 thinking 开关同样的保守策略，避免非标准兼容端点拒绝请求），解析末帧 usage；Anthropic 分支解析 `message_start`（input_tokens）与 `message_delta`（output_tokens 终值）两帧。

4. 结果暴露：`done` 事件与非流式响应的 `metrics` 新增 `token_usage` 字段，形如 `{"prompt_tokens": N, "completion_tokens": M, "total_tokens": T, "llm_calls": C}`。字段命名统一采用 OpenAI 习惯，Anthropic 的 input_tokens/output_tokens 在写入时归一化。

费用（人民币/美元）估算本期不做：单价随模型与厂商频繁变动，需要引入可配置价目表与币种管理，收益低于复杂度。本期先把 token 维度做实，费用作为演进项（见文末）。

## 具体怎么做

涉及模块与文件：

- `agent-runtime/app/core/llm/usage.py`（新增）：`start_usage_tracking()` 创建并绑定累计器；`record_usage(usage, count_call=True)` 归一化 prompt_tokens/input_tokens、completion_tokens/output_tokens 后累加；`current_usage()` 读取当前累计器。
- `agent-runtime/app/models/schemas.py`：`BudgetConfig` 增加 `max_tokens: int = 0`。
- `agent-runtime/app/core/common/settings.py`：`RuntimeConfig` 增加 `max_run_tokens: int = 0` 与对应 settings 属性，作为请求未显式携带预算时的默认值。
- `agent-runtime/app/core/common/constants.py`：`StopReason` 增加 `TOKEN_BUDGET_EXCEEDED = "token_budget_exceeded"`。
- `agent-runtime/app/core/llm/openai_client.py`：`chat()` 成功路径调用 `record_usage`（请求缓存命中不计，因为未真实消耗 token）；`_build_payload` 流式分支按 provider 附加 `stream_options`；`stream_chat()` 循环内新增 `_record_stream_usage(chunk)` 处理 OpenAI 末帧与 Anthropic 两帧。
- `agent-runtime/app/core/agent/loop_controller.py`：`evaluate_budget` 追加 token 检查，阈值语义与工具预算一致（达到即停，`>=`）。
- `agent-runtime/app/core/agent/executor.py`：`execute()` 与 `execute_stream()` 开始时 `start_usage_tracking()`；`_initial_state()` 把当前累计器对象挂到 `state["token_usage"]`（checkpoint 恢复路径同样覆盖，预算按当前 run 重新累计）；`_collect_metrics()` 输出 `token_usage`。

接口契约变化：`POST /v1/agent/runs` 与 `/v1/agent/runs/stream` 的请求 `budget` 对象接受可选 `max_tokens`；响应/done 事件 `metrics` 新增 `token_usage`。均为增量变更，Java 后端 `RuntimeManagedRequestFactory` 现有 budget 三字段不受影响，可后续按需下发 `max_tokens`。

## 风险与注意事项

- 部分 OpenAI 兼容端点不支持 `stream_options`，误发会被拒绝请求。因此只对 `deepseek_api` provider 开启，其余 provider 流式 usage 暂缺采集（chat() 非流式路径不受影响）。
- Anthropic 的 `message_delta.usage.output_tokens` 是终态累计值而非增量，只在该帧记录一次，避免与 `message_start` 重复计数；`llm_calls` 仅在 `message_start` 帧计数一次。
- 请求缓存命中不计入 token 用量，这与"费用视角"一致，但意味着 token_usage 不等于"上下文体积"，做上下文预算时不能复用该指标。
- 累计器通过 contextvars 传播，若未来引入跨任务的手工线程池执行 LLM 调用，需显式传递 context，否则该调用不被统计（当前代码全部走 asyncio，无此路径）。
- checkpoint 中会序列化 `token_usage` 快照，恢复时被新 run 的累计器覆盖，预算按单 run 计，不跨 run 累计。

## 如何验证

- `agent-runtime/tests/test_loop_controller.py`：新增 token 预算用例（达到阈值阻断、0 表示不限、缺省不阻断）。
- `agent-runtime/tests/test_llm_usage.py`（新增）：归一化（OpenAI/Anthropic 字段名）、并发上下文隔离、`_record_stream_usage` 对 OpenAI 末帧与 Anthropic 两帧的处理、缓存命中不计数。
- 回归：`cd agent-runtime && uv run python -m pytest`，以及 `./.agent-harness/scripts/gate.sh agent-runtime --quick`。
- Harness/Eval 联动检查结论：`agent-eval` 的 grader 与 run_engine_eval.py 仅消费 metrics 中的时延键（ttfb_ms/ttft_ms/done_ms），`token_usage` 为纯增量字段，不需要同步修改评估用例与评分器；engine-eval-v1.yaml 的 done_payload 契约中 metrics 键已存在，语义不变。

## 后续如何演进

- 费用估算：引入可配置价目表（provider + model → 每千 token 单价），在 metrics 中追加 `estimated_cost`，并把成本纳入 engine-eval 的联合评分维度。
- 流式 usage 全覆盖：跟进各兼容端点对 `stream_options` 的支持情况，逐步放开 provider 白名单。
- 预算下发：Java 后端 `RuntimeManagedRequestFactory` 按会话/用户等级下发差异化 `max_tokens`，与运营侧配额体系打通。
- 上下文预算：token_usage 与 ContextAssembler 的 metrics 结合，为 Compaction 触发阈值提供输入（对应全局优化方案阶段四-2）。
