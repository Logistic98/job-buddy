# 平台实现补全与 Harness 修复重构方案

## 为什么做

当前仓库的设计文档方向正确,但多个模块的实现程度与文档声明不一致,形成"名实不副"的断层。具体表现为:`.agent-harness` 的 Goal/Loop 自动执行链路从未成功运行过(`runs/` 为空),`run_goal.sh` 在 headless 模式下缺少权限参数导致 Claude 无法编辑文件,多轮循环每轮冷启动丢失上下文,judge 裁决结果不进入门禁退出码,diff 预算只统计不拦截;agent-runtime 的 Trace 仅内存记录无持久化,与"全链路可观测、可回放"的设计目标不符;agent-intent 的分类器为单层规则实现,缺少设计文档要求的分层架构与可选模型兜底;agent-tool 仅有工具名注册表,无实际工具实现;agent-runtime 与 agent-memory 之间没有运行时集成,长程记忆能力悬空;agent-backend 中仍残留意图识别逻辑,与《Agent核心逻辑迁移与Runtime职责边界方案》的边界划分冲突。

本次重构不调整模块划分,九个模块保持不变,目标是把实现程度补到与设计文档一致。

## 方案是什么

按依赖顺序分六个阶段补全实现,每个阶段独立可验证、独立可提交:

第一阶段修复 `.agent-harness` 执行链路,使 Goal 自动化真正可运行:`run_goal.sh` 增加 headless 权限模式参数与会话延续(首轮捕获 session id,后续轮次 resume),增加 `max_diff_lines` 预算拦截;`judge.sh` 要求结构化结论行并解析,裁决结果纳入 run_goal 退出码。

第二阶段补全 agent-runtime 可观测性:TraceRecorder 增加 JSONL 落盘持久化(按 run_id 组织,可配置开关与目录),保留内存查询接口;CheckpointStore 增加按 run 加载、列表查询能力,保持现有文件格式兼容。

第三阶段升级 agent-intent 为分层分类器:第一层保留现有规则与 slot 抽取(高频稳定意图下沉规则层),第二层增加加权关键词评分器处理规则未命中的样本,第三层为可配置的 LLM 兜底分类器(OpenAI 兼容协议,环境变量注入,默认关闭,关闭或失败时降级到评分层结果)。输出结构保持 `domain/intent/confidence/secondary/risk/needs_clarification/next_action/slots` 不变,新增 `router` 字段标记命中层级。

第四阶段补全 agent-tool:为已注册的 `core_trace_summarize`、`memory_search`、`sandbox_execute` 提供实际实现。trace 摘要为纯函数实现;memory_search 与 sandbox_execute 为带超时与错误结构的 HTTP 客户端,目标地址通过环境变量注入。工具执行接口 `POST /v1/tools/{name}/execute` 返回统一结构(status、summary、data、warnings、next_actions、trace_id),错误结构含 retryable 与 suggested_action。

第五阶段打通 agent-runtime 与 agent-memory:在 runtime 增加 memory client(httpx,超时与重试上限,失败静默降级不阻塞主链路),在上下文装配阶段按配置注入记忆检索结果,默认关闭,通过配置开启。

第六阶段收敛 agent-backend 意图逻辑:`IntentServiceImpl` 改为优先代理调用 runtime/intent 服务,本地规则仅作为远端不可用时的降级路径,符合"Java 端为代理层"的边界方案。

## 具体怎么做

涉及模块与文件:`.agent-harness/scripts/run_goal.sh`、`judge.sh`、`goals/_template.md`、`README.md`;`agent-runtime/app/core/observability/trace.py`、`app/core/checkpoint/store.py` 及对应配置与测试;`agent-intent/app/` 新增 `scorer.py`、`llm_classifier.py`,改造 `service.py` 与测试;`agent-tool/app/` 新增 `tools/` 实现与 `server.py` 执行端点及测试;`agent-runtime/app/core/memory/` 新增 client 与配置;`agent-backend` 的 IntentService 增加 runtime 代理调用。

接口契约:agent-intent 响应结构向后兼容,仅追加 `router` 字段;agent-tool 新增执行端点不改变既有 `/v1/tools` 列表端点;agent-memory 现有 `/v1/memories`、`/v1/memories/search` 接口不变,runtime 作为其客户端。

## 风险与注意事项

run_goal.sh 的权限模式默认值采用 `acceptEdits` 而非跳过全部权限,破坏性命令仍需人工授权;LLM 兜底分类器默认关闭,避免无密钥环境下测试失败;memory 集成默认关闭且失败降级,不能因 agent-memory 未启动而阻塞 runtime 主链路;backend 代理改造保留本地降级,避免 runtime 未部署时 SSE 主流程不可用;所有新增配置走环境变量或 YAML,不写死地址与密钥。

## 如何验证

每阶段以 `.agent-harness/scripts/verify.sh <module> --quick` 与 `evaluate.sh <module>` 为最低门槛,交付前运行 `gate.sh all --quick`。harness 修复通过实际执行一次低风险 smoke goal 产生 `runs/` 现场来验证。新增能力均配套 pytest 用例:Trace 落盘与重载、Checkpoint 列表与按 run 加载、intent 三层路由与降级、agent-tool 执行端点与错误结构、runtime memory client 超时降级。

## 后续演进

Trace 落盘后可演进为 OpenTelemetry 导出;intent 评分层后续可替换为 embedding 分类器;agent-tool 可逐步承接跨模块共享工具并演进为工具市场;memory 集成稳定后引入写入判断与离线整理;backend 在代理路径稳定后删除本地意图规则,完成最终瘦身。
