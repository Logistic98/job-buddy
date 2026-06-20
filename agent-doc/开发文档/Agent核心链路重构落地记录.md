# Agent 核心链路重构落地记录

## 概述

本仓库的 Agent 架构沿《Agent核心逻辑迁移与Runtime职责边界方案》确定的方向完成核心链路落地。当前形态是：`agent-backend` 作为业务后端、BFF/API 网关和 Runtime 代理层，`agent-runtime` 承载任务理解、能力路由、Planner、Agent Loop、工具治理、上下文装配、Trace 和模型适配等通用 Agent Core 能力，业务能力通过工具、Profile、Workflow 和后端 API 注入。

落地目标不是把 Runtime 做成脱离业务的空框架，也不是把 Boss 直聘、简历、岗位等业务语义硬编码进 Runtime 核心代码，而是在当前求职场景中形成“通用内核 + 可插拔业务适配”的结构。

## 当前核心结构

### Runtime Core

`agent-runtime` 的核心链路包括以下组件：

- `app/core/agent/loop_controller.py`：集中处理预算评估、循环终止、任务理解后的分流决策和软着陆条件。
- `app/core/intent/`：任务理解相关逻辑，拆分为文本匹配、槽位抽取、结果构建和 LLM 优先理解编排。
- `app/core/capability/`：Profile 与 Capability Card 协议，描述能力、输入依赖、风险等级、候选工具和评估要求。
- `app/core/planner/planner.py`：根据任务理解、上下文、候选工具和历史观察生成执行计划。
- `app/core/tool/gateway.py`：统一工具治理入口，完成工具检索、范围过滤、权限判断、执行和结果规范化。
- `app/core/context/assembler.py`：按当前步骤、当前任务、最近消息、最近观察、长期引用和工具引用分层装配上下文。
- `app/core/checkpoint/` 与 `app/core/observability/`：提供运行检查点和 Trace 事件记录。
- `app/core/llm/openai_client.py`：统一模型适配，支持 OpenAI 兼容协议、流式输出、请求级缓存和提供方差异化参数。

### 配置资产

Runtime 通过配置注入业务语义：

- `agent-runtime/config/profiles/` 描述求职场景能力、槽位、风险和工作流边界。
- `agent-runtime/config/prompts/` 承载任务理解、Planner 和合成回答 Prompt。
- `agent-runtime/config/workflows/` 描述稳定业务工作流与 Runtime/BFF 职责分工。
- `runtime.business_metadata_keys` 由部署配置声明允许透传的业务元数据键，避免在核心代码里硬编码具体业务字段。

### Java 后端边界

`agent-backend` 保留业务系统职责，包括用户登录、Boss 登录态代理、简历、岗位收藏、求职旅程、面试题库、项目深挖、文件与数据库管理。Agent 相关链路通过 Runtime directive action 驱动，Java 侧负责业务动作执行和 SSE 事件转译，不再承载 Planner、工具路由、Prompt 编排和 Agent Loop。

`ChatSseServiceImpl` 以 Runtime directive 的 `action`、`next_action`、`target_action` 等字段作为业务分流依据；没有匹配到 BFF 业务动作时进入 Runtime 托管任务路径。前端 SSE 事件名保持兼容，确保 `intent`、`tool_status`、`job_cards`、`message`、`message_delta`、`done` 等事件可持续演进。

## 工具治理与搜索

ToolGateway 是 Runtime 工具执行的统一治理层。Agent Loop 不直接调用工具，而是通过网关完成候选工具收敛、权限检查、执行与结果规范化。工具检索使用本地 BM25 风格词项召回和轻量语义字段召回，并通过 RRF 融合排序，召回字段覆盖工具名、别名、search hint、description 和 tags，保证排序稳定且不依赖工具目录顺序。

Planner 支持工具 DAG 协议，解析模型返回的 `tool_calls` 数组并从 `plan_steps` 中提取未 defer 的工具步骤。当所有工具声明 `concurrency_safe=true` 时可并发执行，否则顺序执行。离线降级 Planner 优先读取工具 input schema 中的默认值，避免在代码中写死工具参数。

## 上下文与缓存

ContextAssembler 负责把运行上下文分层装配为摘要、结构化 payload 和 metrics。上下文指标写入 `state.metrics.context` 与 Trace，便于评估上下文预算、召回质量和压缩效果。长期记忆、Persona、Checkpoint 摘要和工具引用都通过装配器接口进入 Prompt，而不是直接在 Agent Loop 中拼接字符串。

LLM 客户端提供请求级缓存，缓存键基于模型、messages、tools、temperature、max_tokens 等完整 payload 的稳定 JSON SHA-256。命中后返回深拷贝结果并记录 `cache.hit=true`，`AgentRunResponse.metrics.llm_cache` 输出 hits、misses 和 stores。该缓存与模型服务端 Prompt Cache 互补。

## 联网搜索能力

内置 `web_search` 工具支持 Bocha Web Search、Bocha AI Search 与 DuckDuckGo fallback。配置层包含 `web_search.provider`、`bocha_api_key`、`bocha_web_endpoint`、`bocha_ai_endpoint`、`freshness` 和 `fallback_to_duckduckgo` 等字段，真实 Key 通过环境变量注入。开放域技术问答和需要近期信息的复杂工程问答可在 Profile 中声明 `required_tools: [web_search, web_fetch]`，Planner Prompt 要求涉及近期信息时优先检索、空结果换关键词重试。

## 评估与验证

核心链路变更需要同步关注三类验证：

1. Runtime 单元测试：覆盖 LoopController、任务理解拆分、Tool Search、ToolGateway、Planner 降级、ContextAssembler、LLM 缓存、工具运行时和 API Runtime。
2. Java 后端测试：覆盖 Runtime directive 分流、SSE 中继、业务动作代理、统一响应和异常路径。
3. Agent Eval / Harness：覆盖任务理解、工具执行、证据可信度、输出质量、安全副作用和功能可用性。

推荐验证命令：

```bash
cd agent-runtime && env -u JOB_BUDDY_RUNTIME_USE_LLM_PLANNER uv run python -m pytest -q
cd agent-backend && mvn test -q
cd agent-eval && uv run python -m pytest -q
./.agent-harness/scripts/evaluate.sh agent-runtime
./.agent-harness/scripts/evaluate.sh agent-backend
./.agent-harness/scripts/evaluate.sh agent-eval
```

## 后续演进

- 把 Java 端仍直接执行的稳定业务动作逐步包装为 Runtime 可治理工具，例如 `boss.search_jobs`、`job.rank_candidates`、`resume.match`、`resume.analyze`、`favorite.plan`、`journey.record`。
- ContextAssembler 继续接入 Memory、Persona、Checkpoint 摘要，引入 rerank、去重和 top-K 策略。
- Tool Search 从轻量本地召回演进为 BM25、向量服务和图关系的混合检索。
- Profile directive 全部显式声明 action 且 eval 覆盖稳定后，移除 Java 分流中的 intent fallback。
- 完善 Capability Card 的负例、槽位说明和评估样本，把线上失败样本沉淀到 Runtime 测试与 `agent-eval/cases/`。
- 建立 Skill Recall@K、Clarification Accuracy、Tool Misfire Rate 等指标，接入真实 LLM JSON 路由评测。
- 增加 Memory/Context 预算装配、Compaction、Checkpoint 回放、工具结果压缩和线上 Trace 回流；待 `app/core` 稳定且出现多运行时复用需求时，再评估是否拆出独立 `agent-core` package。
