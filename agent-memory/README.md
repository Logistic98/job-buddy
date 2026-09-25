# agent-memory

基于 `mem0ai==2.2.1` OSS 的独立记忆服务。Mem0 负责写入、Embedding、检索、排序、更新和历史；HTTP 适配层保留租户/用户隔离、scope、秒级 TTL、禁用过滤、回滚和审计。已移除自研 BM25/RRF/Embedding/Rerank 引擎。

正式运行使用 PostgreSQL + pgvector，Mem0 原生 SQLite history 使用持久卷；当前仅支持单实例，数据库故障返回 503，不切换到内存后端。`/health` 同时检查向量库和 history，但不主动调用收费模型。

## 配置与启动

配置只放仓库根目录 `.env`，示例见 `.env.example`。必需项：

```dotenv
AGENT_MEMORY_DATABASE_URL=postgresql://user:password@localhost:5432/job_buddy
AGENT_MEMORY_DB_SSL_MODE=disable
AGENT_MEMORY_COLLECTION=agent_memory_mem0
AGENT_MEMORY_HISTORY_PATH=data/mem0/history.db
AGENT_MEMORY_EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1/embeddings
AGENT_MEMORY_EMBEDDING_API_KEY=replace-me
AGENT_MEMORY_EMBEDDING_MODEL=BAAI/bge-m3
AGENT_MEMORY_EMBEDDING_DIMS=1024
AGENT_MEMORY_EMBEDDING_TIMEOUT_SECONDS=5
AGENT_MEMORY_SEARCH_TOP_K=10
AGENT_MEMORY_SEARCH_THRESHOLD=0.1
```

LLM 配置读取 `AGENT_MEMORY_LLM_BASE_URL/API_KEY/MODEL`，缺失时复用 `JOB_BUDDY_LLM_BASE_URL/API_KEY/MODEL_NAME`。现有显式记忆接口使用 `infer=false`，不会擅自抽取、改写或拆分用户保存的内容，LLM 自动抽取尚未开放为 HTTP 接口。Embedding 必须可用。原有 Embedding/Rerank 开关不再控制检索；排序完全由 Mem0 完成。Mem0 telemetry 强制关闭。

```bash
uv sync --extra dev
uv run python server.py
uv run python -m pytest
```

## HTTP 契约

接口均保留 `{code, message, data}`。除 `/health` 外，配置内部令牌后必须传入 `X-Internal-Service-Token`。身份只从可信上游的 `X-Tenant-Id` 与 `X-Operator-Id` 读取，请求正文不能覆盖身份。

| 方法 | 路径 | 行为 |
| --- | --- | --- |
| GET | /health | 引擎与存储就绪检查 |
| POST | /v1/memories | 显式保存一条事实 |
| GET | /v1/memories | scope、limit 范围内列表 |
| GET | /v1/memories/search?q=... | Mem0 Top-K 召回 |
| PUT | /v1/memories/{id} | 更新正文，可刷新 TTL |
| POST | /v1/memories/{id}/rollback | 回滚上一版本，连续回滚直到无历史 |
| DELETE | /v1/memories/{id} | 删除记忆并清除其明文历史 |
| DELETE | /v1/memories?scope=... | 清理当前用户指定范围 |
| POST | /v1/memories/purge-expired | 只清理当前用户过期记忆 |

不存在或越权均返回 HTTP 404；引擎异常返回 HTTP 503，不返回密钥、底层响应或连接串。禁用和过期条件在候选检索前交给 Mem0 过滤。旧的 `mem_` ID 格式保持兼容。

## 评估

数据集和阈值：`agent-eval/cases/memory-baseline.yaml`。评分器：`agent-eval/app/memory_grader.py`。确定性测试使用真实 Mem0 SDK + Qdrant 和 Embedding 替身，仅验证契约。真实效果必须运行：

```bash
uv run python scripts/evaluate_memory.py --backend pgvector --output ../agent-eval/reports/memory-live-unique.json
# 无 PostgreSQL 时可测真实 Embedding + Mem0 Qdrant；不能代替生产存储验收。
uv run python scripts/evaluate_memory.py --backend qdrant --output ../agent-eval/reports/memory-qdrant-unique.json
```

脚本使用独立合成身份和评估 collection，结束后清理；报告路径必须未存在，避免覆盖证据。失败与缺依赖均返回非零退出码。门槛：Recall@5 ≥ 0.90、MRR@5 ≥ 0.85、显式更新/隔离/生命周期 100%、检索 p95 ≤ 3000 ms、显式更新 p95 ≤ 5000 ms。真实评估不证明自动抽取或模型自然语言纠错效果，指标定义见[设计文档](../agent-doc/核心能力/记忆管理与混合检索.md)。

## 数据与部署

不要把旧 `agent_memory_items`/`agent_memory_revisions` 表删除或复用为向量表。正式切换前备份 PostgreSQL 与 history，停止写入，使用迁移脚本导入并核验旧记录及历史，再启动服务。保留旧表用于人工核对，不能双写两个引擎。未完成迁移不能声称旧数据已切换。pgvector 扩展需数据库管理员预先提供；容器镜像升级前应备份现有 PostgreSQL 卷。

```bash
uv run python scripts/migrate_legacy.py
# 服务停写且完成备份后，显式导入；源表只读。
uv run python scripts/migrate_legacy.py --apply
```

迁移保留旧公开 ID 和历史正文。遇到中断或目标冲突会停止，不能盲目覆盖；需核对未完成条目后重试。
