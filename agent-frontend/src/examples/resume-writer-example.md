## 个人信息

:::left

**林知远（虚构示例） ｜ AI 应用开发工程师**

上海 ｜ 5 年研发经验 ｜ Java / Python ｜ 期望薪资：40-50k

138****6721 ｜ lin.zhiyuan@example.com

技术方向：Agent、RAG、Text-to-SQL 与企业级 AI 应用工程化

:::

:::right

![照片位置](/resume-photo-placeholder.svg)

:::

## 教育背景

:::left

**华东地区某理工类高校 ｜ 软件工程 ｜ 全日制本科**

:::

:::right

**2017.09 - 2021.06**

:::

## 职业概述

- 具备企业级 AI 应用研发经验，能够围绕业务指标完成 Agent 工作流设计、模型与工具集成、效果评估、生产交付及稳定性治理。

## 专业技能

- **AI 应用：** Spring AI、LangGraph、Agent、RAG、Text-to-SQL、Function Calling、Embedding、Rerank、评估集。
- **工程开发：** Java、Python、Spring Boot、FastAPI、SSE、PostgreSQL、Redis、ClickHouse、Kafka。
- **生产治理：** Prompt 版本管理、Guardrail、Checkpoint、OpenTelemetry、Docker、Kubernetes、灰度发布。

## 工作经历

:::left

**某智慧零售 SaaS 企业 ｜ AI 应用开发工程师**

:::

:::right

**2023.04 - 至今**

:::

- 负责经营分析与知识助手类 AI 应用研发，承担 Agent 架构、工具协议、评估体系、运行时治理及生产稳定性建设。
- 建立 Prompt、知识库、工具、评估集和发布版本规范，推动产品、算法、研发与业务团队形成可重复的交付流程。

:::left

**某客户服务技术企业 ｜ Java / AI 应用研发工程师**

:::

:::right

**2021.07 - 2023.03**

:::

- 负责智能客服辅助、工单分类和知识检索服务研发，完成模型网关、异步任务、缓存、限流降级和监控告警建设。

## 项目经验

:::left

**零售经营分析 Agent ｜ 技术负责人**

:::

:::right

**2025.01 - 至今**

:::

`Spring AI` `LangGraph` `Text-to-SQL` `ClickHouse` `Redis` `OpenTelemetry`

- 采用 Planner、Executor、Verifier 分层架构组织意图识别、任务规划、指标查询、证据校验和结论生成；通过语义指标层、JSON Schema 约束和 Checkpoint 机制统一数据口径并支持失败恢复。
- 建设 SQL AST 检查、只读沙箱、超时熔断和结果采样链路，结合历史查询与业务规则构造回归评估集，将可执行 SQL 准确率由 81% 提升至 95%。
- 通过并行工具调用和分层缓存将 P95 响应时间由 16 秒降至 7 秒；基于 OpenTelemetry 记录节点耗时、Token 成本、工具入参与证据引用，支持问题回放和版本对比。

:::left

**售后工单质检 Agent ｜ 核心研发**

:::

:::right

**2024.02 - 2024.12**

:::

`FastAPI` `Hybrid RAG` `PostgreSQL` `Kafka` `Kubernetes`

- 构建字段抽取、规则检索、责任归因和整改建议工作流，采用 BM25 与向量混合召回、Cross-Encoder 重排序及规则—模型双通道置信度，低置信结果进入人工复核。
- 通过 Kafka 分片消费、幂等状态机和租户级限流支撑日均 20 万工单，规则命中率达 92%、人工复核量下降 43%；以字段脱敏、数据权限和审计日志保障安全合规。
