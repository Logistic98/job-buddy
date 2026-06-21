# agent-intent 接入对话主链路方案

## 背景与动机

agent-intent 是一个独立的分层意图识别服务（规则 → 加权打分 → 可选 LLM 兜底 → 默认澄清），输出结构包含 `domain`、`intent`、`confidence`、`secondary`、`risk`、`needs_clarification`、`next_action`、`slots`。对话主链路需要在进入较重的 runtime 任务理解之前，先完成一层快速、独立的意图与风险预判，用于观测、提示和高风险安全门控。

按照 `agent-doc/设计原则` 中“意图识别分层：Domain Router → Intent Classifier → Clarification Gate → Tool Router → Action Authorization”以及“高风险动作必须由独立的分类器复核”的要求，agent-intent 适合作为 Java BFF 入口处的预分类层。Runtime 返回的任务理解仍是主路由依据，agent-intent 负责提供轻量预判、风险提示和可配置安全拦截。

本方案把 agent-intent 接入对话主链路，作为 runtime 任务理解之前的快速预分类层，承担三项职责：独立的高风险安全门控、向 runtime 传递意图提示、以及在 SSE 上透出预判结果用于观测。为控制对已验证的对话路由路径的回归风险，本方案默认不改变既有路由结果，安全门控以配置开关控制且默认关闭，需在完成浏览器端到端验证后再开启。

## 方案概述

在 agent-backend 新增 `IntentClient`，通过共享的 `RestTemplate` 与 `ServiceResilience`（服务键 `agent-intent`，分类为幂等操作可重试）调用 agent-intent 的 `POST /v1/intent/classify`，解析 `data` 为结构化结果，失败或为空时返回空。

`IntentServiceImpl.classify` 首先调用 agent-intent，拿到结果后映射为 `IntentResult`；当 agent-intent 不可用或返回空时，退化到本地关键词规则分类（`classifyLocally`），降级标记使用 `intent_service_unavailable_local_fallback`，以准确反映降级来源。

在 `ChatSseServiceImpl.handle` 中，于会话首包反馈之后、`runTaskUnderstanding` 之前，调用 `intentService.classify` 得到预判结果 `preIntent`，并据此做三件事。第一，将 `preIntent` 以 `intent_hint` 元数据注入 runtime 任务理解请求，runtime 对未知元数据安全忽略，因此该注入是纯增量、不破坏现有契约的。第二，通过新增的 `intent_precheck` SSE 事件透出预判结果，前端对未知事件类型安全忽略，因此不影响既有交互。第三，提供配置开关 `job-buddy.intent-safety-gate-enabled`（默认 false）的高风险安全门控：当开关开启且 `preIntent` 的 `risk` 为 `high` 且 `next_action` 为 `reject` 时，直接发送拒绝性助手消息并结束本轮，不进入 runtime 链路。默认关闭时主链路行为与现状完全一致，仅新增一个观测事件与一个 runtime 提示元数据。

runtime 返回的 `IntentResult` 仍然是路由的权威依据，本方案不替换它，从而把回归风险限制在"新增事件 / 新增元数据 / 默认关闭的安全门控"范围内。

## 涉及模块与接口

改动集中在 agent-backend 的 chat 模块。新增 `modules/chat/client/IntentClient.java`，改写 `modules/chat/service/impl/IntentServiceImpl.java`，在 `ChatSseServiceImpl` 构造器注入 `IntentService` 并在 `handle` 中接入预判，新增配置项 `job-buddy.intent-safety-gate-enabled` 与对应 `JobBuddyProperties` 字段及 `application.yml` 键。对外消费 agent-intent 的 `POST /v1/intent/classify`（请求体 `{"message": "..."}`，响应 `{code,message,data:{domain,intent,confidence,secondary,risk,needs_clarification,next_action,slots,router}}`）。agent-intent 自身不改动。

## 风险与注意事项

预分类在对话首字延迟链路上新增一次 agent-intent 往返。agent-intent 以规则为主、响应为毫秒级，且调用经 `ServiceResilience` 熔断保护，服务不可用时快速失败并降级本地规则；但共享 `RestTemplate` 的读超时较长，单次慢响应仍可能拖慢首字，后续可为意图调用配置独立的短超时。高风险安全门控属于用户可见的对话行为变更，按 CLAUDE.md 必须经浏览器端到端验证后方可开启，因此默认关闭；在未完成 E2E 验证前不得声称"交互已走通"。本地降级规则的关键词表硬编码，属已知技术债，后续应配置化。

## 验证方式

新增并执行 agent-backend 单元测试：`IntentClient` 解析与降级、`IntentServiceImpl` 在 agent-intent 可用时采用其结果、在不可用时退化本地规则并带正确降级标记。同步更新 `IntentRoutingContractTest` 的降级标记断言。执行 `mvn -o test`（或针对类的 `-Dtest`）确认通过。安全门控与 SSE `intent_precheck` 事件需在本地起后端 + 前端后做浏览器端到端验证：确认普通问答不被门控误拦、预判事件可观测、开启门控后高风险请求被独立拒绝，验证完成前安全门控保持默认关闭。

## 后续演进

第一阶段（本方案）接入 agent-intent 作为预分类与提示层，安全门控默认关闭。第二阶段在完成 E2E 验证后默认开启高风险门控，并把 `needs_clarification` 接入澄清门（低置信走澄清而非直接进 runtime）。第三阶段按设计原则把高频稳定意图下沉到规则 / 小模型，强模型只处理长尾与高风险样本，并将 agent-intent 的分类输出纳入 agent-eval 的意图分类评估闭环。
