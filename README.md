# job-buddy

`job-buddy` 是一个面向求职场景的本地 Agent 工作台。当前项目已经从早期的通用 Agent 平台雏形，演进为“Vue 前端 + Spring Boot 业务后端 + Python Agent Runtime + Runtime 按需工具 + 评估/记忆/工具/沙箱辅助服务”的多服务工程。

当前主线聚焦在求职工作流：登录与同步 Boss 在线简历、简历管理、岗位收藏与详情懒加载、岗位/简历分析、对话式问答、求职旅程、面试题库、项目深挖、系统设置与记忆管理。Agent 核心能力逐步下沉到 `agent-runtime`，Java 后端主要负责业务 API、登录态、文件与数据管理，以及对 Runtime、Intent、Memory、Tool、Eval 等服务的代理编排；Boss 能力作为 `agent-tool` 工具注入，Runtime 只做代理和编排。

## 当前状态

- **前端已落地**：`agent-frontend` 提供工作台页面，包含登录中心、聊天面板、简历库、Boss 简历同步、岗位卡片、求职旅程、面试题库、项目深挖、设置中心等页面组件。
- **后端已落地**：`agent-backend` 提供统一 `/api` 业务接口，包含认证、Boss 登录、聊天、简历、岗位收藏、岗位详情、求职旅程、面试题、项目深挖、提示词和系统设置等模块。
- **Boss 取数工具已落地**：`agent-tool` 提供 `boss_browser` 工具，底层使用 [jackwener/boss-cli](https://github.com/jackwener/boss-cli) 复用本机浏览器 Cookie、执行岗位搜索、岗位详情和在线简历读取。
- **Agent Runtime 已落地**：`agent-runtime` 提供运行接口、工具代理、权限、Trace、Checkpoint、Prompt、Profile、Workflow、Memory Client 和内置工具。
- **辅助服务已落地**：`agent-intent`、`agent-memory`、`agent-tool`、`agent-eval`、`agent-sandbox` 均提供 FastAPI 服务、健康检查、统一响应信封、结构化日志、版本锁定依赖与覆盖主流程及异常路径的测试用例，具备容器化与本地一键启动能力。
- **一键本地启动已落地**：根目录 `scripts/start-all.sh`、`status-all.sh`、`stop-all.sh` 管理多服务启动、状态和停止；日志输出到 `.run/logs/YYYYMMDD/`。

## 仓库结构

```text
job-buddy/
├── agent-backend/       # Java 8 + Spring Boot 业务后端 / BFF / Runtime 代理层
├── agent-frontend/      # Vue 3 + Vite 前端工作台
├── agent-runtime/       # Python FastAPI + LangGraph，Agent 运行时与工具编排
├── agent-intent/        # Python FastAPI，意图识别与路由
├── agent-memory/        # Python FastAPI，记忆写入、检索、更新和过期清理
├── agent-tool/          # Python FastAPI，工具目录与工具执行入口，包含 boss_browser 工具实现
├── agent-eval/          # Python FastAPI，Trace / 运行结果 / 能力清单 / Judge 评估
├── agent-sandbox/       # Python FastAPI + srt CLI，命令与代码沙箱执行
├── agent-doc/           # 设计文档、开发理念、部署资料和参考资料
├── .agent-harness/      # 本地验证、评估、质量门禁和 Goal/Loop 脚手架
├── scripts/             # 一键启动、停止、状态检查和产物清理脚本
├── CLAUDE.md            # 项目级 AI 协作规范
└── README.md
```

各服务通过 HTTP/SSE 协作，禁止跨模块直接访问对方内部数据结构。Java 后端是前端默认访问入口；Python 服务既可被后端调用，也可独立调试。

## 服务清单

| 模块 | 状态 | 默认端口 | 主要职责 |
| --- | --- | ---: | --- |
| `agent-frontend` | 已落地 | 5173 | Vue 工作台 UI，通过 Vite proxy 访问后端 `/api` |
| `agent-backend` | 已落地 | 8080 | 业务 API、认证、简历、岗位、聊天、题库、项目、设置、Runtime 代理 |
| `agent-runtime` | 已落地 | 8010 | Agent 运行、工具注册/调用、权限、Trace、Checkpoint、Prompt/Profile/Workflow；内置 `boss_browser` 按需工具 |
| `agent-intent` | 已落地 | 8020 | 意图分类，输出 domain、intent、confidence、risk、next_action 等；LLM 失败软降级，API 层结构化日志 |
| `agent-memory` | 已落地 | 8030 | 记忆写入、搜索、更新、回滚、删除、过期清理；BM25 + 时间衰减 RRF 排序，operator_id 鉴权与审计；支持 Gateway 或 PostgreSQL 兜底 |
| `agent-tool` | 已落地 | 8040 | 工具目录与 `/v1/tools/{name}/execute` 执行入口，包含 `boss_browser` 工具实现 |
| `agent-eval` | 已落地 | 8050 | Trace、完整运行结果、能力清单和 LLM Judge 评估；Judge 调用超时、重试与软降级，API 层结构化日志 |
| `agent-sandbox` | 已落地 | 8061 | 基于 srt 的代码与命令沙箱，统一异常归类与结构化执行日志，默认无网络只读策略 |

## 技术栈

| 层次 | 当前使用 |
| --- | --- |
| 后端 | Java 8、Spring Boot 2.7、MyBatis Plus、Flyway、PostgreSQL、Redis、MinIO、Knife4j |
| 前端 | Vue 3、Vite、Pinia、Vitest、ESLint、原生 CSS |
| Python 服务 | Python 3.10.16、FastAPI、Uvicorn、Pydantic、Loguru、httpx、PyYAML |
| Runtime | LangGraph、OpenAI 兼容 Chat Completions、MCP 适配、内置文件/搜索/简历/Shell 工具、agent-tool 工具代理 |
| Boss 工具 | 位于 agent-tool，使用 jackwener/boss-cli、本机浏览器 Cookie、拟人化限速和上游异常归类 |
| 沙箱 | `@anthropic-ai/sandbox-runtime` 的 `srt` CLI |
| 验证 | Maven Test、Pytest、Vitest、ESLint、`.agent-harness` 门禁脚本 |

当前主 README 不再把 MySQL、Elasticsearch、Milvus、Kafka、Kubernetes、Jenkins 等未在代码主链路中直接使用的组件列为项目必备依赖。如后续重新接入，应同步补充配置、启动脚本和验证方式。

## 环境准备

- JDK 8
- Maven（仓库当前未提供 `mvnw`）
- Python 3.10.16 与 `uv`
- Node.js 18+
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
- `BOSS_CLI_*`：agent-tool 中 Boss 工具的 boss-cli 凭证目录、Cookie 来源、状态校验和请求参数；`BOSS_BROWSER_*` 中的限速项继续兼容。

真实密钥、数据库密码、模型 API Key、用户数据和本地运行目录不得提交到仓库。

## 快速开始

### 一键启动本地开发环境

```bash
./scripts/start-all.sh
./scripts/status-all.sh
```

启动后常用地址：

- 前端：<http://localhost:5173>
- 后端健康检查：<http://localhost:8080/api/health>
- 后端接口文档：<http://localhost:8080/doc.html>
- Runtime 健康检查：<http://localhost:8010/health>
- Boss 工具入口：<http://localhost:8010/v1/runtime/tools/boss_browser/invoke>
- Sandbox 健康检查：<http://localhost:8061/health>

停止全部服务：

```bash
./scripts/stop-all.sh
```

日志默认写入 `.run/logs/YYYYMMDD/{service}.log`，PID 写入 `.run/pids/`。

### 单独启动后端

```bash
cd agent-backend
mvn spring-boot:run
```

### 单独启动前端

```bash
cd agent-frontend
npm install
npm run dev
```

### 单独启动 Runtime

```bash
cd agent-runtime
uv sync --extra dev
uv run uvicorn server:app --host 0.0.0.0 --port 8010 --reload
```

发起一次运行：

```bash
curl -X POST http://localhost:8010/v1/runtime/runs \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"请回显 hello runtime"}]}'
```

### Boss 工具登录态

Boss 具体实现位于 `agent-tool` 的 `boss_browser` 工具中，底层使用 jackwener/boss-cli。推荐先在本机常用浏览器中登录 Boss 直聘网页端，再启动服务；agent-tool 会按需导入浏览器 Cookie 并保存到 `BOSS_CLI_HOME/credential.json`。

```bash
cd agent-tool
BOSS_CLI_HOME="$(cd .. && pwd)/.run/boss-cli-home" ./scripts/start.sh
```

可以通过 agent-tool 工具做非访问 Boss 的限速状态验证：

```bash
curl -X POST http://localhost:8040/v1/tools/boss_browser/execute \
  -H 'Content-Type: application/json' \
  -d '{"arguments":{"operation":"rate","payload":{}},"confirm":true}'
```

### 单独启动其他 Python 服务

```bash
cd agent-intent   && uv sync --extra dev && uv run python server.py
cd agent-memory   && uv sync --extra dev && uv run python server.py
cd agent-tool     && uv sync --extra dev && uv run python server.py
cd agent-eval     && uv sync --extra dev && uv run python server.py
cd agent-sandbox  && uv sync            && uv run python server.py
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
- `GET /api/settings`、`GET /api/settings/memories`

完整接口以后端 Knife4j 文档和 Controller 为准。

### Python 服务

- Runtime：`/health`、`/v1/runtime/runs`、`/v1/runtime/tools`、`/v1/runtime/tools/{name}/invoke`、`/v1/runtime/trace-events`、`/v1/agent/runs/stream`；Boss 能力通过 `/v1/runtime/tools/boss_browser/invoke` 代理到 agent-tool
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
- 只有真实落地、能启动或有明确代码入口的能力才写入“当前状态”；规划内容放入 `agent-doc/开发文档/`，不要混在 README 的已落地能力中。
- 已停用或未接入主链路的组件不要继续出现在快速开始、必备依赖和主技术栈中。
