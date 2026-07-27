## 个人信息

:::left

**林知远（虚构示例） ｜ Agent 应用开发工程师**

上海 ｜ 5 年研发经验 ｜ Agent 应用 / Agent 平台 ｜ 期望方向：Agent 工程化

138****6721 ｜ lin.zhiyuan@example.com

核心方向：Agent Runtime、Context Engineering、RAG 与生产级 AI 应用

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

- 5 年后端与 AI 应用研发经验，近 3 年聚焦 LLM Agent；可独立完成任务理解、规划、工具治理、评测与生产交付，兼顾准确率、时延、成本和安全。

## 专业技能

- **Agent 工程：** LangGraph / Spring AI、Planner–Executor–Verifier、Tool Search / Calling、Structured Output、Checkpoint / HITL。
- **检索上下文：** Hybrid RAG、Query Rewrite / Rerank、Prompt Cache / Context Compaction、Long-term Memory。
- **生产工程：** Python / FastAPI、PostgreSQL / Redis / Kafka；Eval / Guardrail、OpenTelemetry、Docker / K8s。

## 工作经历

:::left

**某智慧零售 SaaS 企业 ｜ AI 应用开发工程师**

:::

:::right

**2023.04 - 至今**

:::

- 负责经营分析与知识助手 Agent，主导 Runtime、工具协议、评测与稳定性治理，协同产品、算法及数据团队完成需求到上线闭环。
- 建立 Prompt / Tool / Dataset 版本化、离线回放、影子流量和灰度发布机制，使 Agent 变更可度量、可回归、可回滚。

:::left

**某客户服务技术企业 ｜ Agent 应用研发工程师**

:::

:::right

**2021.07 - 2023.03**

:::

- 负责智能客服、工单分类与知识检索，建设模型网关、异步任务、缓存、限流熔断和监控告警，支撑多租户高并发调用。

## 项目经验

:::left

**零售经营分析 Agent ｜ 技术负责人**

:::

:::right

**2025.01 - 至今**

:::

`LangGraph` `Spring AI` `Text-to-SQL` `Tool Gateway` `ClickHouse`

- 设计 Task Understanding → Planner → Executor → Verifier 状态图；Tool Gateway 以 Schema、权限、预算、幂等键和 Checkpoint 约束调用，支持中断续跑与人工确认。
- 构建指标语义层、Schema / SQL 混合检索、AST 校验、只读沙箱和结果反查；以 860 条黄金集持续回归，使可执行 SQL 准确率由 81% 提升至 95%。
- 并行工具调用与语义缓存将 P95 从 16 秒降至 7 秒、Token 成本下降 37%；以 OpenTelemetry 关联 Prompt、工具、证据和版本，实现 Trace 回放与归因。

:::left

**售后工单质检 Agent ｜ 核心研发**

:::

:::right

**2024.02 - 2024.12**

:::

`FastAPI` `Hybrid RAG` `Rerank` `Kafka` `Kubernetes`

- 构建抽取、检索、归因与整改工作流；采用 Contextual Chunking、BM25 + Dense 召回、Cross-Encoder 重排和引用校验，低置信结果转人工复核。
- 以 Kafka 分片消费、幂等状态机、租户限流和失败补偿支撑日均 20 万工单；质检准确率 92%、复核量下降 43%，并以脱敏、ACL 和审计保障合规。

说明：以上企业、项目、职责及量化数据均为虚构脱敏示例。
