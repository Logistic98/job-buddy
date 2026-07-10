# job-buddy

`job-buddy` 是面向求职场景的本地 Agent 工作台，采用 Vue 前端、Spring Boot 业务后端、Python Agent Runtime，以及意图识别、记忆、工具、评估和沙箱服务组成的多服务架构。

系统支持 Boss 登录与在线简历同步、简历管理、岗位收藏与详情加载、岗位和简历分析、对话式问答、求职旅程、面试题库、项目深挖、系统设置与记忆管理。`agent-runtime` 负责 Agent 执行、规划、工具治理、上下文、Trace 与 Checkpoint；Java Backend 负责业务 API、认证、文件和数据管理及下游服务编排；Boss 能力由 `agent-tool` 的 `boss_browser` 工具提供。

## 核心能力

- `agent-frontend` 提供登录中心、聊天、简历库、Boss 简历同步、岗位卡片、求职旅程、面试题库、项目深挖和设置中心。
- `agent-backend` 提供统一 `/api` 业务接口以及认证、业务事务、文件、数据和 SSE 编排。
- `agent-runtime` 提供任务理解、Planner、工具治理、权限、Trace、Checkpoint、Prompt、Profile、Workflow 和 Memory Client。
- `agent-intent`、`agent-memory`、`agent-tool`、`agent-eval`、`agent-sandbox` 分别提供意图路由、长期记忆、工具执行、质量评估和隔离执行能力。
- 根目录 `scripts/start-all.sh`、`status-all.sh`、`stop-all.sh` 管理本地服务，日志写入 `.run/logs/YYYYMMDD/`。

## 仓库结构

```text
job-buddy/
├── agent-backend/       # Java 17 + Spring Boot 3 业务后端 / BFF / Runtime 代理层
├── agent-frontend/      # Vue 3 + Vite 前端工作台
├── agent-runtime/       # Python FastAPI + LangGraph，Agent 运行时与工具编排
├── agent-intent/        # Python FastAPI，意图识别与路由
├── agent-memory/        # Python FastAPI，记忆写入、检索、更新和过期清理
├── agent-tool/          # Python FastAPI，工具目录与工具执行入口，包含 boss_browser 工具实现
├── agent-eval/          # Python FastAPI，Trace / 运行结果 / 能力清单 / Judge 评估
├── agent-sandbox/       # Python FastAPI + srt CLI，命令与代码沙箱执行
├── agent-doc/           # 设计文档、开发理念、部署资料和参考资料
├── .agent-harness/      # 本地验证、评估、质量门禁和 Goal/Loop 脚手架
├── scripts/                            # 本地启停、环境校验、清理和迁移脚本
├── docker-compose.yml                  # 应用服务编排
├── docker-compose-infra.yml   # PostgreSQL、Redis、MinIO 独立编排
├── CLAUDE.md                           # 项目级 AI 协作规范
└── README.md
```

各服务通过 HTTP/SSE 协作，禁止跨模块直接访问对方内部数据结构。Java 后端是前端默认访问入口；Python 服务既可被后端调用，也可独立调试。

## 服务清单

| 模块 | 默认端口 | 主要职责 |
| --- | ---: | --- |
| `agent-frontend` | 5173 | Vue 工作台 UI，通过 Vite proxy 访问后端 `/api` |
| `agent-backend` | 8080 | 业务 API、认证、简历、岗位、聊天、题库、项目、设置、Runtime 代理 |
| `agent-runtime` | 8010 | Agent 运行、工具注册/调用、权限、Trace、Checkpoint、Prompt/Profile/Workflow；内置 `boss_browser` 代理定义，实际执行转发至 agent-tool |
| `agent-intent` | 8020 | 意图分类，输出 domain、intent、confidence、risk、next_action 等；LLM 失败软降级，API 层结构化日志 |
| `agent-memory` | 8030 | 记忆写入、搜索、更新、回滚、删除、过期清理；BM25 + 时间衰减 RRF 排序，operator_id 鉴权与审计；支持 Gateway 或 PostgreSQL 兜底 |
| `agent-tool` | 8040 | 工具目录与 `/v1/tools/{name}/execute` 执行入口，包含 `boss_browser` 工具实现 |
| `agent-eval` | 8050 | Trace、完整运行结果、能力清单和 LLM Judge 评估；Judge 调用超时、重试与软降级，API 层结构化日志 |
| `agent-sandbox` | 8061 | 基于 srt 的代码与命令沙箱，统一异常归类与结构化执行日志，默认无网络只读策略 |

## 技术栈

| 层次 | 技术与组件 |
| --- | --- |
| 后端 | Java 17、Spring Boot 3.5、MyBatis Plus、Flyway、PostgreSQL、Redis、MinIO、SpringDoc OpenAPI、Knife4j UI |
| 前端 | Vue 3、Vite 7、Pinia、Vitest、ESLint、原生 CSS |
| Python 服务 | Python 3.10.16、FastAPI、Uvicorn、Pydantic、Loguru、httpx、PyYAML |
| Runtime | LangGraph、OpenAI 兼容 Chat Completions、MCP 适配、内置文件/搜索/简历/Shell 工具、agent-tool 工具代理 |
| Boss 工具 | 位于 agent-tool，使用 jackwener/boss-cli、本机浏览器 Cookie、拟人化限速和上游异常归类 |
| 沙箱 | `@anthropic-ai/sandbox-runtime` 的 `srt` CLI |
| 验证 | Maven Test、Pytest、Vitest、ESLint、`.agent-harness` 门禁脚本 |

MySQL、Elasticsearch、Milvus、Kafka、Kubernetes 和 Jenkins 不属于项目运行依赖。新增运行依赖必须同步提供配置、启动方式和验证链路。

## 环境准备

当前本地验证环境为 JDK 17.0.6、Maven 3.8.6、Python 3.10.x + uv 0.10.x、Node.js 22.16.0 / npm 10.9.x。前端依赖 Vite 7，Node 版本需满足 `^20.19.0 || >=22.12.0`，推荐直接使用 Node.js 22.16+。

- JDK 17+（项目编译目标为 Java 17 字节码；Docker 镜像使用 Temurin 17 构建/运行）
- Maven 3.8+（仓库当前未提供 `mvnw`）
- Python 3.10.x 与 `uv`（`agent-runtime` 锁定 Python 3.10.16，其余 Python 服务以各自 `pyproject.toml` 为准）
- Node.js 22.16+ 或 20.19+
- PostgreSQL 与 Redis（后端本地完整运行需要）
- 可选：Docker
- 可选：已登录 Boss 直聘网页端的本机常用浏览器（Boss 工具可从浏览器 Cookie 导入登录态）

沙箱服务需要安装上游 CLI：

```bash
npm install -g @anthropic-ai/sandbox-runtime
# Linux 还需 bubblewrap、socat、ripgrep；macOS 需 ripgrep
```

## 配置

根目录提供 [.env.example](./.env.example)。本地开发时复制为 `.env` 后填写真实值：

```bash
cp .env.example .env
```

重要配置包括：

- `SPRING_DATASOURCE_URL`、`SPRING_DATASOURCE_USERNAME`、`SPRING_DATASOURCE_PASSWORD`：后端 PostgreSQL 连接。
- `SPRING_REDIS_*`：后端 Redis 连接。
- `AGENT_RUNTIME_URL`、`AGENT_INTENT_URL`、`AGENT_MEMORY_URL`、`AGENT_TOOL_URL`、`AGENT_EVAL_URL`：后端调用 Python 辅助服务地址。
- `JOB_BUDDY_LLM_*`：Runtime 的 OpenAI 兼容模型配置。
- `JOB_BUDDY_MINIO_*`：简历附件和资源对象存储配置。
- `AGENT_INTERNAL_SERVICE_TOKEN`：Backend 与 Runtime、Intent、Memory、Tool、Eval、Sandbox 之间共享的服务令牌；生产环境必填。
- `JOB_BUDDY_BOSS_CREDENTIAL_ENCRYPTION_KEY`：Boss 凭据 AES-256-GCM 加密使用的 Base64 32 字节密钥，生产环境必须稳定保存。
- `BOSS_CLI_*`：agent-tool 中 Boss 工具的 Cookie 来源、状态校验、请求和限速参数。

真实密钥、数据库密码、模型 API Key、用户数据和本地运行目录不得提交到仓库。

## 数据库迁移规范

后端使用 Flyway 管理数据库结构变更，脚本统一放在 `agent-backend/src/main/resources/db/migration/`。V1.0.0 至 V1.0.7 是按身份权限、简历存储、聊天 Agent、岗位旅程、面试、项目、分析平台和默认授权数据拆分的规范基线，只允许在全新空数据库执行；`baseline-on-migrate` 关闭，非空且没有 Flyway 历史记录的数据库失败关闭。该基线属于不可变资产，数据库变更只能追加高于 V1.0.7 的脚本，禁止修改、删除、重命名或复用版本号。文件名遵循 `V<major>_<minor>_<patch>__<English_description>.sql`。

Flyway 初始化共享租户、权限定义、角色、菜单、角色菜单目录，默认 `admin`、`user` 账号及其角色关联，并通过追加迁移维护平台级系统岗位黑名单；两个默认账号的初始密码均为 `12345678`，数据库只保存 BCrypt 哈希，管理员可在平台设置的用户管理中重置密码。项目经历、简历、岗位收藏、求职进展、聊天记录和认证状态等私有业务数据必须通过受鉴权 API 写入，禁止进入 Flyway 和 Git。除指定的默认身份种子迁移及门禁明确登记的 V1.0.8 `blacklist_item` 系统种子迁移外，新增迁移向私有业务表执行 `INSERT`、`UPDATE` 或 `DELETE` 会被质量门禁直接拒绝；系统黑名单同样禁止通过其他迁移插入、更新或删除。

数据库由 Flyway 在空 Schema 中初始化，禁止使用 repair、baseline 或手工覆盖绕过版本校验。公开部署后应立即修改默认密码。

提交涉及数据库结构的改动前，先运行 Flyway 迁移校验：

```bash
./.agent-harness/scripts/check_flyway_migrations.py
```

该检查由 `./.agent-harness/scripts/verify.sh agent-backend --quick` 和 `./.agent-harness/scripts/gate.sh agent-backend --quick` 执行。

## 快速开始

根目录 `.env.example` 是配置项模板，`.env` 只允许值不同。首次运行先复制模板并检查键集合：

```bash
cp .env.example .env
./scripts/sync-env.py
```

### Docker Compose 部署

Docker Engine 与 Compose 可用时，先填写 `.env` 并校验键集合：

```bash
cp .env.example .env
./scripts/sync-env.py
unset COMPOSE_PROJECT_NAME
```

`COMPOSE_PROJECT_NAME` 是 Docker Compose 保留变量，若宿主 Shell 已导出该变量，会覆盖两个文件各自的项目名，必须在执行本项目的 Compose 命令前清除。基础设施与应用使用两个独立 Compose 项目。先启动 PostgreSQL、Redis、MinIO，并等待健康检查通过：

```bash
docker compose --env-file .env -f docker-compose-infra.yml up -d --wait
```

首次使用空数据卷时，Compose 会自动创建应用数据库和独立的 Memory 数据库，Redis 会初始化空数据集；Backend 首次启动时会检查并自动创建 `JOB_BUDDY_MINIO_BUCKET`。上述操作均为幂等检查，重复启动不会覆盖已有数据库、Redis 数据或 MinIO 对象。

再构建并启动八个应用服务：

```bash
docker compose --env-file .env -f docker-compose.yml up -d --build --wait
```

应用可以独立停止、重建和删除，不影响基础设施容器及其数据卷：

```bash
docker compose --env-file .env -f docker-compose.yml down
```

需要停止基础设施时，应先停止应用，再执行：

```bash
docker compose --env-file .env -f docker-compose-infra.yml down
```

两个编排通过 `JOB_BUDDY_NETWORK_NAME` 指定的共享网络通信，容器内部地址固定使用 `postgres`、`redis`、`minio` 等服务名，不需要替换成服务器 IP。生产部署通过 `.env` 显式设置 `JOB_BUDDY_BIND_HOST` 和 `JOB_BUDDY_INFRA_BIND_HOST`；完整的密钥、网络、持久化、备份和卷退役说明见 [Docker Compose 应用与基础设施部署](agent-doc/运维部署/Docker%20Compose应用与基础设施部署.md)。

### 一键启动本地开发环境

```bash
./scripts/start-all.sh
./scripts/status-all.sh
```

访问地址由 `.env` 中的 `JOB_BUDDY_SERVER_HOST` 与端口变量决定；本地默认前端为 <http://127.0.0.1:5173>，后端健康检查为 <http://127.0.0.1:8080/api/health>。服务器监听接口由 `JOB_BUDDY_BIND_HOST` 控制，健康检查中的 `127.0.0.1` 保留为进程或容器内部回环地址。若健康端口已由 PID 目录之外的旧进程占用，`start-all.sh` 会立即失败并提示先停止该进程，避免旧 Backend 与启用新鉴权配置的 Runtime、Tool 混合运行。

停止全部本地服务：

```bash
./scripts/stop-all.sh
```

启动脚本按依赖顺序等待每个健康端点；服务提前退出或超过 `START_ALL_READY_TIMEOUT_SECONDS` 时会快速失败并打印日志尾部。日志默认写入 `.run/logs/YYYYMMDD/{service}.log`，PID 写入 `.run/pids/`。

### 单独启动后端

```bash
cd agent-backend
mvn spring-boot:run
```

### 单独启动前端

```bash
cd agent-frontend
npm ci
npm run dev
```

### 单独启动 Runtime

```bash
cd agent-runtime
uv sync --frozen --extra dev
uv run uvicorn server:app --host 127.0.0.1 --port 8010 --reload
```

发起一次运行：

```bash
curl -X POST http://localhost:8010/v1/agent/runs \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"请回显 hello runtime"}]}'
```

### Boss 工具登录态

Boss 具体实现位于 `agent-tool` 的 `boss_browser` 工具中，底层使用 jackwener/boss-cli。二维码会话按 tenant/user/chat 持久化 owner，轮询和取消均校验归属。登录成功后的 Cookie 由 Backend 使用 `JOB_BUDDY_BOSS_CREDENTIAL_ENCRYPTION_KEY` 执行 AES-256-GCM 加密后写入 PostgreSQL `auth_state`，读取历史明文时会机会式升级；调用工具时凭据只通过请求载荷注入 agent-tool 进程内存，Tool 不创建本地凭证目录。浏览器 Cookie 导入默认关闭。

```bash
cd agent-tool
./scripts/start.sh
```

可以通过 agent-tool 工具做非访问 Boss 的限速状态验证：

```bash
curl -X POST http://localhost:8040/v1/tools/boss_browser/execute \
  -H 'Content-Type: application/json' \
  -d '{"arguments":{"operation":"rate","payload":{}},"confirm":true}'
```

### 单独启动其他 Python 服务

```bash
cd agent-intent   && uv sync --frozen --extra dev && uv run python server.py
cd agent-memory   && uv sync --frozen --extra dev && uv run python server.py
cd agent-tool     && uv sync --frozen --extra dev && uv run python server.py
cd agent-eval     && uv sync --frozen --extra dev && uv run python server.py
cd agent-sandbox  && uv sync --frozen --extra dev && uv run python server.py
```

## 主要接口入口

### 后端 `/api`

- `GET /api/health`
- `POST /api/auth/login`、`GET /api/auth/me`、`POST /api/auth/logout`
- `GET /api/boss/login-qr`、`GET /api/boss/login-status`、`POST /api/boss/login-cancel`
- `POST /api/chat/ask`、`POST /api/chat/stream`、`GET /api/chat/sessions`
- `GET /api/resume`、`POST /api/resume/upload`、`POST /api/resume/boss/sync`、`GET /api/resume/{resumeId}/preview`
- `GET /api/jobs/favorites`、`POST /api/jobs/favorites`、`GET /api/jobs/detail`
- `GET /api/journey/target`、`GET /api/journey/records`、`POST /api/journey/analysis`
- `GET /api/interview/questions`、`POST /api/interview/questions/generate`、`POST /api/interview/code/run`
- `GET /api/project-deep-dive/projects`、`POST /api/project-deep-dive/projects/{projectId}/generate`
- `GET /api/settings`、`POST /api/settings/workspace/restore-defaults`、`GET /api/settings/memories`

恢复运行参数默认值需要具备 `tenant:manage` 权限，并使用登录接口取得的会话 Cookie：

```bash
curl -X POST -b cookie.txt http://127.0.0.1:8080/api/settings/workspace/restore-defaults
```

完整接口以后端 Knife4j 文档和 Controller 为准。

### Python 服务

- Runtime：`/health`、`/v1/agent/runs`、`/v1/runtime/tools`、`/v1/runtime/tools/{name}/invoke`、`/v1/runtime/trace-events`、`/v1/agent/runs/stream`；Boss 能力通过 `/v1/runtime/tools/boss_browser/invoke` 代理到 agent-tool
- Intent：`/health`、`/v1/intent/classify`
- Memory：`/health`、`/v1/memories`、`/v1/memories/search`、`/v1/memories/{memory_id}`、`/v1/memories/purge-expired`
- Tool：`/health`、`/v1/tools`、`/v1/tools/{name}/execute`
- Eval：`/health`、`/v1/eval/trace`、`/v1/eval/run`、`/v1/eval/capabilities`、`/v1/eval/judge`
- Sandbox：`/health`、`/v1/commands`、`/v1/cli`、`/v1/shell`、`/v1/python/code`、`/v1/code-file`

## 验证

按修改范围运行最小必要验证：

```bash
# 后端
cd agent-backend && mvn test

# Flyway 迁移规则
./.agent-harness/scripts/check_flyway_migrations.py

# 前端
cd agent-frontend && npm run lint && npm test && npm run build

# Python 服务，以 agent-runtime 为例
cd agent-runtime && uv run python -m pytest
```

也可以使用 Harness：

```bash
./.agent-harness/scripts/verify.sh --list
./.agent-harness/scripts/verify.sh agent-backend --quick
./.agent-harness/scripts/verify.sh agent-frontend --quick
./.agent-harness/scripts/gate.sh agent-backend --quick
```

涉及前端交互、登录弹窗、SSE、Boss 登录、岗位卡片、简历预览等用户可见行为时，除构建和测试外，还需要启动本地服务并用浏览器验证。

## 运行产物清理

默认预览，不删除：

```bash
scripts/clean-artifacts.sh --dry-run
```

实际清理日志、PID 和临时运行产物：

```bash
scripts/clean-artifacts.sh --apply
```

可选清理可再生成的构建产物和浏览器纯缓存：

```bash
scripts/clean-artifacts.sh --apply --build --browser-cache
```

浏览器缓存清理不会删除 Cookies、History、Local Storage、Session Storage 等登录态数据。

## 文档维护约定

- 修改接口、配置项、启动脚本、端口、服务职责或目录结构时，同步更新本 README 和对应模块 README。
- 新增环境变量时，同步更新 [.env.example](./.env.example)。
- 文档只描述具备代码、配置、接口和测试支撑的正式能力；主题文档放入 `agent-doc/架构设计/`、`运行时能力/`、`业务能力/` 或 `运维部署/`，使用能直接表达主题的文件名。
- 已停用或未接入主链路的组件不要继续出现在快速开始、必备依赖和主技术栈中。
