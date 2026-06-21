# agent-memory 检索相关性排序与运行时记忆启用方案

## 背景与动机

agent-memory 当前的本地检索（`PostgresMemoryStore.search` 与内存 `MemoryStore.search`）使用 `LOWER(content) LIKE '%query%'` 的整串子串匹配。该实现存在三个本质问题，使其停留在 MVP 阶段而无法落地：

第一，整串匹配对多词查询和词序变化完全失效。当查询为"Java 后端 经验"而记忆内容为"具备后端开发经验，熟悉 Java"时，子串匹配返回空结果，召回率极低。第二，结果排序仅按 `created_at DESC`，没有任何相关性度量，最新写入的无关记忆会排在高相关记忆之前。第三，结果以硬编码 `LIMIT 50` 截断，既无相关性 top-K，也无法配置。

按照 `agent-doc/设计原则` 中"检索是上下文调度而非向量库召回""召回追求 recall，进入 Prompt 前必须经过 rerank、去重、top-K"的要求，记忆检索至少要做到：分词匹配、相关性排序、可配置 top-K。完整的 BM25 + 向量 + 图混合召回需要 Milvus、Embedding 等外部基础设施，属于后续演进；本方案先在不引入任何新依赖的前提下，把检索从"子串匹配"提升到"相关性排序的关键词检索"，覆盖中英文混合的求职语料。

同时，agent-runtime 的记忆客户端默认关闭（`memory.enabled` 默认 false），导致整条记忆链路在默认配置下并未真正运行。本方案将记忆能力改为可配置启用，并保持失败软降级，使其在具备 agent-memory 服务时即可落地。

## 方案概述

引入一个零依赖的相关性打分模块 `app/relevance.py`，提供统一的分词与打分函数，供 PostgreSQL 与内存两套存储共用，保证行为一致与可测试。

分词策略针对中英文混合语料：ASCII 字母数字按词切分；中文按"字 + 相邻二元组（bigram）"切分，使"后端"这类词在内容包含"后端"时可被命中。二元组用于提高中文匹配精度，单字用于保证短查询的召回。

检索流程改为"候选召回 + 重排"两段式。候选召回阶段，PostgreSQL 侧用 `content ILIKE ANY($patterns)` 以查询中的显著词（长度不小于 2 的 ASCII 词与中文二元组）做宽匹配，结合 scope 与未过期条件，按 `created_at DESC` 取一个候选池（默认 200 条）；当查询无显著词时退化为按时间召回。重排阶段在 Python 内用打分函数计算每条候选的相关性，分值由"查询词命中的饱和词频 + 查询词覆盖率 + 时间衰减"构成，按分值降序取 top-K（默认 10，可配置）。内存存储走同一套分词与打分，保证单测与生产语义一致。

候选池大小与 top-K 通过环境变量配置：`AGENT_MEMORY_SEARCH_POOL`（默认 200）与 `AGENT_MEMORY_SEARCH_TOP_K`（默认 10），时间衰减半衰期由 `AGENT_MEMORY_SEARCH_HALF_LIFE_DAYS`（默认 30）控制，符合"配置驱动而非硬编码"原则。

运行时记忆启用通过 agent-runtime 既有的记忆配置开关与 agent-backend 的代理路径完成：记忆默认保持失败软降级，启用后写入与检索任一环节异常都不影响主链路，仅记录告警并返回空结果，避免记忆服务不可用拖垮 Agent 主流程。

## 涉及模块与接口

本方案改动集中在 agent-memory：新增 `app/relevance.py`，改写 `app/store.py` 中 `MemoryStore.search` 与 `PostgresMemoryStore.search`，新增针对分词、打分与排序的单元测试。对外 HTTP 契约（`GET /v1/memories/search` 的请求与响应结构）保持不变，调用方 agent-backend `AgentIntegrationServiceImpl.searchMemory` 与 agent-runtime 记忆客户端无需改动接口，仅在行为上获得相关性排序后的结果。TencentDB 网关召回路径为不透明透传，不在本次改写范围内。

## 风险与注意事项

中文单字二元组分词是一种轻量近似，并非真正的中文分词，对长文本可能产生一定噪声词，但通过"显著词候选过滤 + 覆盖率加权"可将噪声影响限制在排序层而非召回层。候选池上限决定了重排的输入规模，过大影响延迟、过小影响召回，默认 200 在求职语料规模下是平衡值，可按需调参。PostgreSQL 的 `ILIKE ANY` 在 content 上仍走现有的 `LOWER(content)` 索引能力有限，候选阶段在大表上可能退化为顺序扫描；当前记忆量级可接受，未来数据增长后应迁移到 PostgreSQL 全文检索或外部向量库。

## 验证方式

新增并执行 `agent-memory` 的单元测试：覆盖分词对中英文混合串的切分、多词与乱序查询能够命中、相关性排序使高相关项排在前、top-K 截断、无显著词时按时间召回等场景，命令为 `cd agent-memory && uv run python -m pytest`。PostgreSQL 查询层因依赖真实数据库，沿用既有"仅验证 DSN 推导 + 内存后端"的策略，新增打分逻辑通过共享的 `relevance` 模块在内存路径上获得等价覆盖。

## 后续演进

第一阶段（本方案）落地相关性排序的关键词检索。第二阶段引入 PostgreSQL 全文检索（`to_tsvector`/`ts_rank`）或 `pg_trgm` 相似度作为候选召回的增强。第三阶段接入 Embedding 与向量库，按设计原则用 BM25 + Vector + Graph 混合并以 RRF 融合，并补充离线去重（Dreams）、冲突解决与记忆分层（步骤 / 任务 / 长期 / 语义）。

## 第二轮落地：BM25-RRF 融合、版本回滚与写入鉴权审计

在第一阶段相关性排序的基础上，本轮把检索打分、记忆生命周期与安全审计三块短板一次性补齐，使记忆服务从"可用的关键词检索"推进到"具备可解释排序、可回滚状态与可审计写入"的形态，同时坚持零外部依赖、失败软降级与配置驱动的既有约束。

排序层面，`app/relevance.py` 新增标准 BM25 打分（`bm25_scores`，默认 `k1=1.5`、`b=0.75`），并以倒数排名融合（Reciprocal Rank Fusion，默认 `rrf_k=60`）把"词项相关性"与"时间新近度"两路排序合并，替换原先饱和词频叠加覆盖率的启发式线性加权。融合时按词项分排序得到一路名次、按新近度排序得到另一路名次，分别累加 RRF 倒数贡献后取并集重排；当查询存在显著词时仅以 BM25 命中文档作为候选集合，避免无关记忆借时间维度挤入结果，新近度为零的文档不参与新近度一路融合以消除并列噪声。这一结构把设计原则中"BM25 + Vector + Graph 以 RRF 融合"的目标先在 BM25 与新近度两路上落地，向量与图召回保留为可插拔的后续融合输入而非重写。原有 `relevance_score`、`tokenize`、`significant_terms` 等函数保持兼容，存量调用与单测不受影响。

生命周期层面，`MemoryItem` 增加 `kind`（step / task / long_term / semantic，非法值回落 task）、`operator_id` 与 `version` 字段，并引入 `MemoryRevision` 历史记录。`update` 在覆盖内容前先留存当前版本并递增 `version`，新增 `rollback` 弹出最近一条历史版本并恢复其内容，`delete` 与 `purge` 同步清理对应历史，避免悬挂版本。内存存储与 `PostgresMemoryStore` 走同一套语义：PostgreSQL 侧 schema 扩展 `kind`/`operator_id`/`version` 列并新增 `agent_memory_revisions` 表，`update` 在事务内以 `SELECT ... FOR UPDATE` 锁定当前行、写入历史表再覆盖，`rollback` 同样在事务内完成，保证并发写入下的版本一致性。

安全层面，所有写类与召回接口接受 `X-Operator-Id` 请求头，操作者解析优先级为请求头、请求体声明、匿名兜底，确保审计字段恒不为空。创建、检索、更新、回滚、删除与过期清理统一通过 Loguru `audit="memory"` 绑定字段落审计日志，记录操作者、动作、结果与受影响的记忆标识及版本，对应设计原则中"长期记忆是攻击面，写入、存储、召回、执行、共享、遗忘各环节都要鉴权与审计"的要求。对外新增 `POST /v1/memories/{memory_id}/rollback` 端点，其余端点路径与请求体保持向后兼容，调用方按需附带操作者头即可，不附带时行为与此前一致。

验证方式上，`agent-memory` 单测扩充 BM25 排序、显著词查询排除非词项命中、回滚恢复历史内容、无历史回滚报错、创建持久化 `operator_id` 与 `kind`、非法 `kind` 回落等场景，命令仍为 `cd agent-memory && uv run python -m pytest`，当前全部通过。本轮改动集中在 agent-memory 内部存储与 HTTP 层，未触及 agent-eval 评分用例与 .agent-harness 验证脚本所覆盖的对话主链路 Trace 节点、意图分类输出或工具事件结构，故评估与 Harness 无需同步调整；后续若把记忆写入与召回纳入主链路 Trace 断言，再补充对应用例。
