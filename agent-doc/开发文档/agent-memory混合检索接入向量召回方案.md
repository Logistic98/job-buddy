# agent-memory 混合检索接入向量召回方案

## 为什么做

agent-memory 当前的检索链路为"显著词候选召回（ILIKE / 内存过滤）+ BM25 词法与时间衰减两路 RRF 融合重排"。该方案零外部依赖、离线可测，但只有词法一路语义信号：查询与记忆内容用词不同而语义相近时（如"远程办公"与"在家上班"）无法召回。仓库设计原则要求"检索用 BM25 + Vector + Graph 混合并以 RRF 融合，单一向量召回不足以应对符号化代码场景"，反之亦然——单一词法召回也不足以应对语义改写场景。`relevance.py` 的融合框架在设计时已声明对向量一路保持开放，本方案补齐这一路。

## 方案是什么

新增可配置的 Embedding 客户端，把"查询与候选内容的余弦相似度"作为第三路排序信号并入既有 RRF 融合。核心取舍：

- Embedding 走 OpenAI 兼容 `/v1/embeddings` 协议（复用 httpx），服务地址、密钥、模型名全部由环境变量注入，默认关闭。关闭时行为与现状完全一致，不引入任何新依赖，不要求部署向量数据库。
- 向量只做候选池内重排，不做全库向量召回：候选召回仍由显著词 ILIKE / 时间兜底完成（候选池默认 200 条），向量在池内计算相似度。这避免了引入 pgvector/Milvus 等基础设施，全库向量召回作为演进项。
- Embedding 调用失败（超时、限流、服务不可用）静默降级为两路融合，仅记 warning，检索接口不报错、不变慢主链路以外的路径。
- 进程内内容哈希缓存（FIFO 上限 2048 条）避免同一记忆内容反复 embedding，查询向量每次实时计算。

排序融合规则（`relevance.rank` 扩展 `vector_scores` 可选参数）：

- 向量一路的参与门槛为余弦相似度大于 `vector_min_score`（默认 0.30），防止弱相关候选借名次噪声挤入结果。
- 有显著查询词时，参与融合的候选集合从"词法命中"扩为"词法命中 ∪ 向量达标"，语义改写场景由向量路补召回。
- 三路（词法、时间、向量）RRF 权重一致，融合常数沿用 k=60；`vector_scores` 缺省为 None 时行为与现状逐字节一致。

## 具体怎么做

1. 新增 `app/embedding.py`：`EmbeddingClient.embed(texts) -> list[list[float]] | None`，环境变量 `AGENT_MEMORY_EMBEDDING_ENABLED`（默认 false）、`AGENT_MEMORY_EMBEDDING_BASE_URL`、`AGENT_MEMORY_EMBEDDING_API_KEY`、`AGENT_MEMORY_EMBEDDING_MODEL`、`AGENT_MEMORY_EMBEDDING_TIMEOUT_SECONDS`（默认 5）、`AGENT_MEMORY_VECTOR_MIN_SIMILARITY`（默认 0.3）。
2. `app/relevance.py` 新增 `cosine_similarity`；`rank` 增加 `vector_scores`、`vector_min_score` 可选参数与向量一路融合逻辑。
3. `app/store.py` 新增模块级 `hybrid_rank(query, candidates)` 异步函数：Embedding 关闭或失败时退化为纯 `rank`；`MemoryStore.search` 改为 async 与 `PostgresMemoryStore.search` 对齐，两套存储统一走 `hybrid_rank`，保证行为一致。
4. `app/api.py` 的 `search_local_memories` 对内存后端改为 `await`。
5. 测试：dev 依赖补 `pytest-asyncio` 并开启 `asyncio_mode = "auto"`；`test_store.py` 检索用例改 async；新增 `test_embedding.py`（默认关闭零网络调用、请求载荷与响应解析、失败降级返回 None、缓存命中）与 `test_relevance.py` 向量融合用例（向量补召回词法未命中项、低于阈值不参与、缺省行为不变）。

## 涉及模块与接口

仅 agent-memory：`app/embedding.py`（新增）、`app/relevance.py`、`app/store.py`、`app/api.py`、`pyproject.toml`（dev 依赖）。对外 HTTP 接口签名与响应结构不变；`GET /v1/memories/search` 的排序结果在 Embedding 开启时更优，关闭时不变。agent-runtime 的 `MemoryClient` 无需改动。Harness 的 `verify.sh agent-memory --quick` 跑 pytest，无需脚本改动；agent-eval 不消费 memory 排序细节，无需同步评估用例。

## 风险与注意

- Embedding 服务时延直接叠加在检索路径上：超时默认 5 秒且失败即降级；候选池 200 条按批一次请求，不做逐条调用。
- 内容缓存以进程为界，多副本部署各自维护，属可接受的性能优化而非正确性依赖。
- 密钥仅经环境变量注入，日志不输出向量与密钥。

## 如何验证

`uv run python -m pytest -q`（agent-memory 全量）通过；`./.agent-harness/scripts/gate.sh agent-memory --quick` 通过。有 Embedding 服务的环境可开启开关后人工对比语义改写查询的召回差异。

## 后续演进

- pgvector 持久化向量列与全库 ANN 召回，替代池内重排。
- 图召回（记忆间引用关系）作为第四路信号并入 RRF。
- Embedding 结果落库缓存，跨副本共享。
