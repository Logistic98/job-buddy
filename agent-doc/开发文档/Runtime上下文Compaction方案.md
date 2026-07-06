# Runtime 上下文 Compaction 方案

## 为什么做

agent-runtime 的 Agent Loop 在多轮执行中把工具观察（observations）线性累加进 graph state，Planner 每轮把 `observations[-8:]` 原样拼进动态 Prompt。当前唯一的体积控制是 ContextAssembler 在生成 context_summary 时对 JSON 文本做末端硬截断（`...(truncated)`），这有两个问题：一是截断位置任意，可能把关键的失败记录或决策依据拦腰截断；二是截断只发生在装配摘要处，observations 本体不收敛，长循环任务的每轮 Planner 输入持续膨胀，token 消耗（阶段四-1 已可观测）与噪声同步增长。

仓库设计原则明确要求："Compaction 是任务状态迁移，必须保留目标、修改、决策、失败、下一步关键字段，不是单纯摘要"，以及"上下文操作支持 Continue / Rewind / Compact / Clear"。当前 Runtime 缺失 Compact 能力。

## 方案是什么

引入规则驱动的结构化压缩器 `ContextCompactor`，在 Loop 的观察节点（observe）后按阈值触发，把较早的原始观察折叠为一个保留五要素的结构化快照，只保留最近 K 条原始观察：

1. 五要素快照：`objective`（目标）、`changes`（修改：已成功执行的工具及其产出摘要，来源于 tool_results 而非解析观察文本，保证可靠）、`decisions`（决策：Planner 已生成的计划步骤目标）、`failures`（失败:失败工具与错误摘要）、`next_step`（下一步：当前选中的工具调用或计划中的后续步骤）。快照存入 `state["compaction"]`。
2. 追加式合并（cache-safe）：多轮触发时新折叠内容合并进既有快照（changes/failures/decisions 去重追加、objective/next_step 以最新为准），不重写历史，不修改任何已进入 Prompt 前缀的静态内容；压缩产物以一条带 `[Compaction]` 前缀的观察字符串放在 observations 列表头部，后续新观察继续在尾部追加，符合"状态更新走追加消息"的缓存原则（Planner 的 Prompt 缓存前缀是系统提示与工具目录，observations 属动态段，不影响前缀命中）。
3. 触发条件（配置驱动）：观察条数超过 `compaction_trigger_observations` 或观察总字符超过 `compaction_trigger_chars` 时触发，压缩后保留最近 `compaction_keep_recent` 条原始观察。开关 `compaction_enabled` 默认开启。
4. 可观测：触发时写入新增 Trace 事件 `context_compaction`（携带折叠条数、压缩前后字符数），并在 `state["metrics"]["compaction"]` 累计轮次与折叠总条数，进入 done/response 的 metrics。

本期为确定性规则压缩，不调用 LLM：离线测试可完整验证，且五要素来源（tool_results、plan、selected_tool_call）本就是结构化数据，规则折叠不损失关键信息。LLM 辅助的语义摘要作为演进项。

## 具体怎么做

涉及模块与文件：

- `agent-runtime/app/core/context/compactor.py`（新增）：`ContextCompactor.maybe_compact(state) -> Optional[CompactionReport]`。读取 observations/tool_results/plan/selected_tool_call/objective，按阈值折叠；返回报告（折叠条数、前后字符数）供 Trace 记录，未触发返回 None。
- `agent-runtime/app/core/common/settings.py`：`RuntimeConfig` 新增 `compaction_enabled: bool = True`、`compaction_trigger_observations: int = 12`、`compaction_trigger_chars: int = 6000`、`compaction_keep_recent: int = 4` 及对应 settings 属性。
- `agent-runtime/app/core/common/constants.py`：`TraceEventName` 新增 `CONTEXT_COMPACTION = "context_compaction"`。
- `agent-runtime/app/core/agent/graph.py`：`_observe` 节点在追加观察后调用压缩器，触发时记录 Trace 事件；压缩器实例由 `AgentGraphBuilder` 构造注入（默认构造）。
- `agent-runtime/app/core/context/assembler.py`：payload 增加可选 `compaction` 键（存在时注入），使一次性装配的 context_summary 同样受益。

数据结构（state["compaction"]）：

```json
{
  "objective": "...",
  "changes": [{"tool": "job_search", "summary": "..."}],
  "decisions": ["..."],
  "failures": [{"tool": "resume_parse", "error": "..."}],
  "next_step": "...",
  "folded_observations": 8,
  "rounds": 1
}
```

接口契约变化：response/done 的 metrics 可能新增 `compaction` 键；Trace 事件流可能出现 `context_compaction`。均为增量，必检事件集合（run_start 等）不变。

## 风险与注意事项

- observations 头部的 `[Compaction]` 条目会占用 Planner `observations[-8:]` 窗口一格；压缩后列表长度为 keep_recent + 1，远小于 8，窗口不会挤掉压缩快照。
- checkpoint 会序列化 `compaction`（纯字典）；恢复后继续按追加式合并，无兼容问题。
- changes/failures 摘要各自限长（单条 200 字符）并限量（各 20 条），防止快照自身无界增长。
- 压缩只作用于 Loop 内 observations，不改动请求 messages（会话历史压缩属 BFF/会话层职责，不在本期范围）。

## 如何验证

- 新增 `agent-runtime/tests/test_context_compactor.py`：条数触发、字符数触发、未达阈值不触发、多轮合并去重、五要素完整性、观察列表裁剪、禁用开关。
- 回归：`cd agent-runtime && uv run python -m pytest`、`./.agent-harness/scripts/gate.sh agent-runtime --quick`。
- Harness/Eval 联动检查结论：`context_compaction` 为新增可选 Trace 事件，`agent-eval/app/grader.py` 的必检事件集合与顺序校验不受影响（额外事件不违反契约）；metrics 新增键为纯增量。无需修改评估用例，如后续要把压缩率纳入评分再补 capability 用例。

## 后续如何演进

- LLM 辅助压缩:对超长工具产出先做语义摘要再折叠，规则快照兜底。
- 会话层 Compaction：Java BFF 对跨请求的 messages 历史做 cache-safe forking 压缩，与 Runtime 内 Loop 压缩形成两级。
- 与 token 预算联动：token_usage 接近 max_tokens 时提前主动触发压缩（软着陆的一部分）。
- Rewind/Clear：基于 checkpoint 的错误路径移除，配合压缩快照回放。
