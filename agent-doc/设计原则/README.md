# Agent 设计原则

本目录沉淀 job-buddy 的 Agent 运行时设计原则，是 CLAUDE.md 中"Agent 设计原则"章节里运行时、检索、记忆、意图、安全、Prompt、评测各小节的展开说明。工具体系与核心链路的原则分别见 `agent-doc/工具体系/` 与 `agent-doc/核心链路/`。涉及 agent-runtime、agent-intent、agent-memory、agent-sandbox、agent-eval 的改动必须先对照本目录原则。

## 运行时与 Agent Loop

- Agent Loop 形成"获取上下文 → 行动 → 验证"的闭环，任何动作都要有可验证手段（测试、类型检查、状态比对）。
- 循环边界必须齐备：max_turns、token/费用预算、permission mode、compaction、checkpoint、人类中断，缺一不可。本仓库对应 `agent-runtime/config/config.yaml` 的 runtime 段与 checkpoint 段。
- 长任务必须状态化执行：持久化、检查点、重试、人类介入，禁止只靠从头重跑。
- 上下文操作支持 Continue / Rewind / Compact / Clear，错误路径要能被移除而不是用噪声覆盖。
- Compaction 是任务状态迁移，必须保留目标、修改、决策、失败、下一步关键字段，不是单纯摘要。
- 子 Agent 用独立上下文承载噪声探索，只把摘要返回主会话；并行任务用 worktree 隔离避免文件冲突。
- 会话期内不切换模型，缓存按模型隔离；需要轻量模型时用 Subagent 承担。

## 检索与上下文

- 检索是上下文调度而非"向量库召回"，目标是最小高信号 token，而非最大 recall。
- 离线 Contextual Retrieval 与运行时 Agentic Search 必须组合使用。
- 召回追求 recall，进入 Prompt 前必须经过 rerank、去重、top-K、摘要压缩。
- 工具返回结构化线索（路径、链接、摘要、ID），支持局部读取与渐进披露；大结果落盘到 result_storage_dir，上下文中只保留引用。
- 检索利用元数据（路径、目录、时间戳、依赖关系）作为信号，不要只依赖语义相似。
- 用引用而非完整内容做长期上下文，按需通过工具加载具体细节。

## 记忆机制

- 区分四类信息：当前步骤、当前任务、跨任务长期、跨会话语义，分别用上下文 / 任务状态 / Memory / Persona 承载。
- 在线写入轻量，离线做梦（Dreams）负责去重、冲突解决、洞察提炼，新 Store 不覆盖原 Store。
- 记忆系统必须支持更新、覆盖、过期、删除与回滚，不能只追加。
- 写入比检索更关键，需主动判断"是否值得记"，低质量写入会长期污染系统。
- 检索用 BM25 + Vector + Graph 混合并以 RRF 融合，单一向量召回不足以应对符号化代码场景。
- 长期记忆是攻击面：写入、存储、召回、执行、共享、遗忘六个环节都要鉴权与审计。
- 本仓库长期记忆由 agent-memory 服务承载，runtime 侧通过 MemoryConfig 集成，检索失败静默降级，不阻塞主链路。

## 意图识别与路由

- 意图识别分层：Domain Router → Intent Classifier → Clarification Gate → Tool Router → Action Authorization。
- 业务意图不等于动作授权，高风险动作必须由独立的 transcript classifier 复核。
- 分类输出结构化（domain、intent、confidence、secondary、risk、needs_clarification、next_action），允许 unknown 与澄清。本仓库由 agent-intent 模块承载。
- 工具路由按意图收窄候选工具集，禁止一上来就暴露所有高风险工具。runtime 侧由 ToolGateway 按能力声明收窄候选。
- 高频稳定意图下沉到规则、小模型或 embedding 分类器，强模型只处理长尾与高风险样本。

## 安全、权限与沙箱

- 安全规则能写成 hook、沙箱、权限程序，就不要只写进 Prompt。
- Permission Prompt 不能替代边界设计，过度依赖会导致 Approval Fatigue。
- 文件系统隔离与网络隔离必须成对出现，并约束子进程继承。shell_exec 统一通过 agent-sandbox 执行，沙箱不可用时 fail-closed，不回退宿主机，见《Runtime Shell工具沙箱化方案》。
- 凭据采用 scoped credential，长期密钥绝不进入 Agent 可控环境；Git 等敏感操作走代理注入。
- 工具结果、网页、Shell 输出默认是不可信数据，进入上下文前过 Prompt Injection Probe。runtime 侧由 `app/core/tool/injection_probe.py` 在 ToolGateway 出口打标告警。
- 高风险动作走 Transcript Classifier，只看用户消息与 tool call，剥离 assistant 解释。
- 连续拒绝要有 backstop（如 3 次或 20 次升级人工），headless 直接终止。
- 沙箱（srt）同时管控文件、网络与子进程，敏感目录默认禁读写。

## Prompt 与缓存

- 缓存是前缀级字节匹配，动态时间戳、随机顺序绝不能进入前缀。
- Prompt 分层布局：静态系统 → 工具定义 → 项目规则 → 会话上下文 → 当前轮消息，越靠前越稳定。
- 工具集合在会话期内顺序稳定，模式切换通过工具（如 EnterPlanMode）而非增删工具表达。
- 状态更新走追加消息（如 system-reminder），禁止修改前缀。
- Compaction 用 Cache-safe Forking：继承父会话前缀，只在末尾追加压缩指令，并预留 Compaction Buffer。
- 把 cache hit rate、cache_creation / read tokens 当作生产 SLO 指标监控。
- Prompt 是行为代码：每次变更走 diff、ablation、per-model eval、灰度与可回滚。本仓库 Prompt 资产集中在 `agent-runtime/config/prompts/`。

## 评测与质量观测

- Agent Eval 测的是模型 + Harness + 工具 + 环境的组合，结果必须带完整版本与资源配置。
- 优先评估环境最终状态（Outcome），其次才看模型说了什么；不要只检查路径。
- Grader 三级组合：规则负责正确性，LLM Judge 负责开放质量并要人类校准，Human 负责高风险抽检。本仓库对应 `agent-eval/app/grader.py` 与 `POST /v1/eval/judge`。
- 区分 Capability Eval（有爬坡空间）与 Regression Eval（接近 100%），任务在两者间流动。
- 同时跟踪 pass@k 与 pass^k：pass@k 衡量能力上限，pass^k 衡量上线可靠性。
- 用户反馈必须沉淀为可复现 task + grader，进入 regression suite（`agent-eval/cases/`）。
- 质量信号分层归因：模型 / Prompt / 上下文 / 工具 / Harness / 基础设施 / 评测七层都可能是根因。
- 防 Eval 污染与 awareness：用私有 / 动态测试集、扫描泄漏、监控异常 token 与搜索路径。
