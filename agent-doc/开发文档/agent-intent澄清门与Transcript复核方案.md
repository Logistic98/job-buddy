# agent-intent 澄清门与 Transcript 复核方案

## 为什么做

agent-intent 已具备"规则 → 评分 → 可选 LLM → 兜底"的分层分类，输出结构含 `needs_clarification` 与 `risk`，但对照仓库意图识别分层原则（Domain Router → Intent Classifier → Clarification Gate → Tool Router → Action Authorization）仍有两处缺口。其一，澄清出口分散在各层内部判断（空文本、短文本、求职域缺槽位），缺少统一的 Clarification Gate：任何一层给出的低置信结果都应在出口处被兜住，而不是依赖每层自觉。其二，"业务意图不等于动作授权"：高风险动作必须由独立的 transcript 复核环节把关，该环节只看用户消息与工具调用、剥离 assistant 解释，防止被模型自我说服式的解释绕过；当前模块只有入口关键词拦截，没有这一独立复核能力。

## 方案是什么

两项均先落规则实现，LLM 复核作为演进项：

一、Clarification Gate。在 `classify_intent` 出口处增加统一置信度门：分类结果 `confidence` 低于阈值（环境变量 `AGENT_INTENT_CLARIFY_CONFIDENCE_THRESHOLD`，默认 0.5）且非高风险时，强制 `needs_clarification=true`、`next_action="clarify"`，并在 `secondary` 追加 `low_confidence` 标记、在 `slots` 补充标准化的 `clarification_question`（已带域内澄清问题的结果不覆盖）。高风险结果不走澄清门——其出口固定为人工确认，不能被降级为普通澄清。现有各层输出的置信度均不低于 0.6（澄清兜底本身就是 clarify），因此该门对既有链路是纯防御性收口，不改变现有评估用例的期望输出。

二、Transcript 复核钩子。新增 `app/transcript_review.py` 与接口 `POST /v1/intent/review-transcript`，输入为对话消息列表与拟执行的工具调用列表，输出复核决定。规则如下：

- 只提取 `role == "user"` 的消息文本与 tool_calls 的名称/参数，assistant 消息一律剥离，不进入判定。
- 工具调用命中破坏性标记（delete/drop/truncate/rm、force push、生产环境、批量投递等）而用户消息中不存在任何高风险意图表达时，判 `deny`：破坏性动作缺少用户意图背书，视为可疑的模型自主行为或注入结果。
- 用户消息与工具调用同时呈现高风险信号时，判 `require_human_confirmation`：用户确有此意，但高风险动作仍须人工确认，意图不等于授权。
- 均无高风险信号时判 `approve`。
- 输出结构：`decision`（approve / require_human_confirmation / deny）、`risk`、`matched_rules`（命中的规则标识，便于审计与评估断言）、`reviewed_user_messages`、`reviewed_tool_calls`。

## 具体怎么做

1. 新增 `app/clarification.py`：`apply_clarification_gate(result) -> IntentResult`，阈值读环境变量；`service.classify_intent` 出口统一套用。
2. 新增 `app/transcript_review.py`：`TranscriptReviewRequest` / `TranscriptReviewResult` 模型与 `review_transcript` 规则函数，高风险词表与 service 层共享常量。
3. `app/api.py` 新增 `POST /v1/intent/review-transcript` 路由，沿用统一响应信封与 request_id 日志。
4. 测试：新增 `tests/test_clarification_gate.py`（低置信触发、高风险不降级、已带澄清问题不覆盖、阈值可配置）与 `tests/test_transcript_review.py`（assistant 消息剥离、deny/confirm/approve 三分支、空输入）。
5. Harness 同步：`.agent-harness/scripts/evaluate.sh` 的 `run_intent_eval` 由指定两个测试文件改为跑模块全量 pytest，保证新增测试进入评估门禁。

## 涉及模块与接口

agent-intent（app/clarification.py、app/transcript_review.py、app/api.py、app/service.py、tests/）与 `.agent-harness/scripts/evaluate.sh`。`/v1/intent/classify` 的请求与响应结构不变；新增接口为纯增量。agent-eval 的 engine-eval-v1.yaml 既有澄清与安全用例期望输出不受影响（澄清门对现有置信度分布是空操作），transcript 复核为新接口，暂以模块内单测覆盖，接入 Runtime 高风险工具链路后再补 agent-eval 端到端用例（见演进）。

## 风险与注意

- 规则词表存在漏报：transcript 复核定位为独立防线之一，不替代 Runtime 端的工具权限与沙箱；词表命中宁可保守（倾向 confirm 而非 approve）。
- deny 分支可能误伤"用户换措辞表达删除意图"：matched_rules 提供审计线索，误伤时由人工确认路径兜底，不直接放行。
- 阈值调整影响澄清率：默认 0.5 为防御性取值，调高前须跑 agent-eval 澄清用例回归。

## 如何验证

`uv run python -m pytest -q`（agent-intent 全量）通过；`./.agent-harness/scripts/gate.sh agent-intent --quick` 通过（verify 无 intent 目标时以 evaluate.sh agent-intent 为准）。

## 后续演进

- Transcript 复核升级为小模型分类器，规则作为快速路径与回退。
- Runtime 高风险工具调用前接入该复核接口，连续拒绝计数与人工升级由 Runtime/Backend 维护。
- agent-eval 增加 transcript 复核端到端评估用例（注入样本、意图不一致样本）。
