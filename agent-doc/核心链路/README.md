# 核心链路

本目录沉淀 job-buddy 的 Agent 核心链路设计，是 CLAUDE.md 中"核心链路"小节的展开说明。涉及会话入口、意图分流、Planner、Agent Loop、Trace、记忆写入的改动必须先对照本文档，并同步检查 `.agent-harness/` 与 `agent-eval/` 的联动规则。

## 会话入口

会话入口必须先完成三项预检，违规直接拦截，不进入后续链路：

1. 发布版本加载：确认当前生效的 Prompt / Profile / 工具版本。
2. 身份权限预检：确认用户身份与可用能力范围。
3. 内容安全预检：拦截明显违规输入。

## 意图分流

意图识别后按置信度与任务形态分流：

- 低置信 → 澄清（Clarification Gate）。
- 稳定流程 → DAG / Workflow 固定编排。
- 外部 Agent → 准入治理后代理执行。
- 开放域问答 → 模型直接生成。
- 复杂任务 → Agent Loop。

本仓库入口链路为：前端 → agent-backend SSE → agent-intent 意图识别 → agent-runtime 任务理解与能力路由。

## 复杂任务前置

复杂任务进入 Loop 前必须完成：

1. Planner 生成计划。
2. 计划审核与预算预估（轮次、token、费用）。
3. 状态初始化（run_id、checkpoint）。
4. 记忆 / 上下文按预算装配。

## Agent Loop 动作治理

- Loop 内动作分四类：工具调用、检索、用户参与、状态更新。
- 工具与检索都要经过治理网关（ToolGateway）与结果压缩，禁止绕过网关直接执行。
- 每个节点都要写 Trace；验证反思后才决定继续、降级或结束。
- 长期记忆写入需经提取、去重、冲突检测与权限判断，不允许原文直写。

## 可观测闭环

- 全链路汇入 Trace 汇总与评估分析模块，形成可观测、可回归的闭环。
- 观测按 OpenTelemetry 上报 Trace / 日志 / 指标，审计字段固定 14 项以上，支撑回放、归因与评估样本沉淀。
- 关键事件契约（run_start、understand_goal、task_understanding、capability_route、finalize、run_end）由 `agent-eval/app/grader.py` 做回归校验；修改事件流必须同步更新 grader 与用例。
- 日志必须包含 run_id、session_id、tool_name、node_id 等关键字段，能还原请求与任务上下文。
