# agent-memory

`agent-memory` 是长程记忆与上下文管理服务。服务优先对接自部署的 TencentDB Agent Memory Hermes Gateway；当 Gateway 未配置或不可用时，本地持久化默认使用 PostgreSQL，不再使用 H2 或其他嵌入式数据库。未配置 PostgreSQL 连接时，仅保留内存兜底，适用于本地快速验证。

## 接口

- `GET /health`
- `POST /v1/memories`
- `GET /v1/memories/search?q=trace&scope=session`
- `PUT /v1/memories/{memory_id}`
- `POST /v1/memories/{memory_id}/rollback`
- `DELETE /v1/memories/{memory_id}`
- `POST /v1/memories/purge-expired`

写入与召回采用 BM25、时间衰减和可选向量信号的 RRF 融合排序（详见 `app/relevance.py`），不包含图数据库召回。每条记忆带 `kind`（step / task / long_term / semantic）、`operator_id` 与 `version` 字段；`PUT` 更新会在覆盖前留存历史版本，`POST .../rollback` 回滚到上一版本内容。

所有写类与召回接口只从受信的 `X-Tenant-Id`、`X-Operator-Id` 请求头解析所有权；为兼容旧客户端，请求模型中的 `operator_id` 字段暂时保留但不参与身份判定，缺少操作者请求头时进入匿名隔离分区。服务通过 Loguru `audit="memory"` 绑定字段输出审计日志，覆盖创建、检索、更新、回滚、删除与过期清理，便于按操作者还原记忆变更链路。

## 启动与验证

```bash
uv sync --extra dev
uv run python server.py
uv run python -m pytest
```

## PostgreSQL 本地持久化配置

当未启用 TencentDB Agent Memory Gateway，或 Gateway 暂时不可用需要本地兜底时，PostgreSQL 连接统一读取仓库根目录 `.env`：

```bash
SPRING_DATASOURCE_URL=jdbc:postgresql://<host>:<port>/<database>
SPRING_DATASOURCE_USERNAME=<username>
SPRING_DATASOURCE_PASSWORD=<password>
AGENT_MEMORY_DB_SSL_MODE=disable
AGENT_MEMORY_DB_POOL_SIZE=5
AGENT_MEMORY_DB_CONNECT_ATTEMPTS=4
AGENT_MEMORY_DB_CONNECT_TIMEOUT_SECONDS=8
AGENT_MEMORY_DB_CONNECT_BACKOFF_SECONDS=0.5
```

`agent-memory` 会自动加载根目录 `.env`，并将 `SPRING_DATASOURCE_*` 转换为 PostgreSQL 连接串，默认与 Backend 共用业务数据库。也可通过根目录 `.env` 中的 `AGENT_MEMORY_DATABASE_URL` 覆盖连接串，其优先级高于 `SPRING_DATASOURCE_*`。服务启动时会自动创建带 `agent_memory_` 前缀的表和必要索引。

`AGENT_MEMORY_DB_SSL_MODE` 支持 `disable`、`prefer`、`allow`、`require`、`verify-ca` 和 `verify-full`。未配置且连接串不含 `sslmode` 时默认使用 `disable`，避免本地无 TLS PostgreSQL 在 SSL 升级阶段断开；连接串中的 `sslmode` 可直接生效，独立环境变量的优先级更高。生产环境应使用 `require` 或 `verify-full`，并通过 Secret 或部署平台注入连接串，不要提交真实账号密码。

服务启动时会对建池和幂等 Schema 初始化期间出现的瞬时断连、连接超时或数据库临时不可用执行有界重试。默认最多尝试 4 次，单次建连超时 8 秒，按 0.5、1、2 秒指数退避；可分别通过 `AGENT_MEMORY_DB_CONNECT_ATTEMPTS`、`AGENT_MEMORY_DB_CONNECT_TIMEOUT_SECONDS` 和 `AGENT_MEMORY_DB_CONNECT_BACKOFF_SECONDS` 调整。密码、库名、权限、SSL 校验及非法配置等确定性错误不会重试，重试耗尽后服务仍会启动失败，避免静默退化造成持久化记忆丢失。

## TencentDB Agent Memory 自部署配置

如使用自部署的 TencentDB Agent Memory Hermes Gateway，可通过根目录 `.env` 配置网关地址：

```bash
TDAI_MEMORY_GATEWAY_URL=http://127.0.0.1:8420
TDAI_GATEWAY_API_KEY=
```

`TDAI_GATEWAY_API_KEY` 为空时不会发送 `Authorization` 头，适用于未开启网关鉴权的自部署场景。历史占位值 `change-me`、`changeme`、`none`、`null` 也会被视为未配置密钥。
