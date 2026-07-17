# AGENTS.md

本文件为 Codex 在本仓库中工作的项目级指导。修改代码前请先阅读本文件，并优先遵循各模块 README、设计文档以及 `agent-doc/` 下的开发理念。

## 项目定位

`job-buddy` 是面向求职场景的本地 Agent 工作台，采用 Vue 前端、Spring Boot 业务后端、Python Agent Runtime、agent-tool 工具服务，以及意图识别、评估、记忆和沙箱服务组成的多服务架构。系统支持 Boss 在线简历同步、简历管理、岗位收藏与详情加载、岗位和简历分析、对话式问答、求职旅程、面试题库、项目深挖、系统设置与记忆管理。Agent 执行、规划、工具治理和上下文能力由 agent-runtime 承载；Java 后端负责业务 API、认证、文件与数据管理及下游服务编排；Boss 直聘浏览器能力由 agent-tool 提供。

核心原则：

- 配置驱动而非硬编码，敏感信息一律外置。
- 模块按能力切分，对外通过 HTTP/SSE 标准接口交互，避免隐式耦合。
- 文档即代码、规范驱动开发、测试驱动开发，三者作为 AI 协作的基础。
- 统一日志、统一异常、统一响应结构，便于跨模块联调与排障。
- 所有模块均提供 Dockerfile 与健康检查接口，支持容器化部署。

## 仓库结构

```text
job-buddy/
├── .agent-harness/     # 自动化开发工作流脚手架：Goal、Loop、验证、评估、裁判、运行现场
├── agent-backend/      # Java Spring Boot 统一后端 / 编排服务
├── agent-runtime/      # Python FastAPI + LangGraph，Agent 运行时与工具编排
├── agent-intent/       # 意图识别与路由模块
├── agent-tool/         # 工具服务；Boss 直聘浏览器能力以 boss_browser 工具注入
├── agent-sandbox/      # Python FastAPI，基于 srt 的代码与命令沙箱执行器
├── agent-eval/         # Agent 与模型效果评估服务，包含 Trace 规则评分器与评估用例
├── agent-memory/       # 长程记忆与上下文管理服务
├── agent-frontend/     # Vue 3 + Vite 前端工程
├── scripts/            # 一键启动、停止、状态检查和产物清理脚本
└── agent-doc/          # 开发理念、设计文档、参考资料
```

各模块以独立服务的形态交付，互相之间通过 HTTP 接口协作，禁止跨模块直接访问对方数据库或内部数据结构。

`.agent-harness` 与 `agent-eval` 是工程闭环的一部分，不是附属文档目录。任何会影响构建、启动、验证、核心链路、意图路由、工具调用、Trace 节点、质量门禁或可观测字段的代码变更，都必须同步检查并更新对应 Harness 规则、脚本和评估用例。

## 开发文档优先规则

关键改动必须先阅读并遵循 `agent-doc/` 对应主题目录下的架构或能力文档；如果缺少对应文档，或实现目标与既有文档不一致，应先补充或更新语义化命名的主题文档，再进入代码实现。禁止使用 `README.md` 作为主题文档名。关键改动包括但不限于架构边界、核心链路、意图识别、能力路由、Planner、工具路由、Prompt、Workflow、Profile、Java 后端与 Runtime 的接口契约、Trace、Checkpoint、Memory、Eval、Harness、SSE 主流程和登录态处理。

开发文档必须说明能力目标、正式方案、模块与接口、风险边界和验证方法，不得记录迭代历史、过渡方案或路线图。系统架构、核心链路和 Runtime 职责边界的基准文档为 [`agent-doc/架构设计/系统架构与核心链路.md`](agent-doc/架构设计/系统架构与核心链路.md)。涉及 Agent 架构、Prompt、Java Backend 与 Runtime 职责边界的任务必须先阅读该文档。

## 技术栈与运行约束

### Java 后端模块（agent-backend）

- agent-backend 采用 Java + Spring Boot，定位为业务后端、BFF/API 网关和 Agent Runtime 代理层，不再承载 Agent 核心调度逻辑。
- 依赖通过 Maven 管理；仓库未提供 `mvnw` Wrapper，使用全局 `mvn` 执行构建与测试。新增依赖必须锁定版本或由 BOM 统一管理。
- Controller 层负责参数校验和接口协议，Service 层负责业务编排，底层异常必须转换为统一响应结构。
- Java 端应保留用户、Boss 登录态、简历、岗位收藏、投递旅程、面试题库、文件、数据库等业务能力；执行期权威任务理解、Planner、Agent Loop、工具路由、Prompt 编排、上下文装配等 Agent 核心逻辑应位于 agent-runtime，通过代理接口执行。agent-intent 保留为 Backend 调用的轻量前置分类与高风险复核服务，其结果只作路由提示和安全辅助，不能替代 Runtime 的执行期理解或授予工具权限。
- 配置通过 `application-*.yml`、环境变量、Profile 或 Secret 注入，禁止在代码或默认配置中写入真实密钥、生产地址。
- 对外服务必须提供 `/health` 或 Spring Actuator 健康检查，并维护 Dockerfile。

### 后端 Python 模块（agent-runtime / agent-intent / agent-sandbox / agent-eval / agent-memory / agent-tool）

- Python 3.10+，以各模块 `pyproject.toml` 为准。
- 依赖使用 `uv` 管理，依赖必须带版本号，禁止使用不带版本的隐式依赖。
- Web 框架优先使用 FastAPI + Uvicorn，数据建模使用 Pydantic，日志使用 Loguru。
- agent-runtime 使用 LangGraph 组织 Agent 状态图，OpenAI 兼容协议对接模型服务，定位为通用 Agent 运行时和 Agent Core 承载层。
- agent-runtime 应承载 Agent Loop、Planner、Task Understanding、Intent Routing、Tool Registry、Tool Router、Tool Permission、Prompt Runtime、Profile、Workflow 注册与路由、Memory、Context、Trace、Checkpoint 和 LLM 适配等通用能力；业务定制通过 tools / profiles / workflows / prompts 注入，不应在 runtime 核心代码中硬编码 Boss 直聘、简历、岗位收藏等业务概念。Workflow 中声明的 Backend 业务动作或前端事件由对应服务执行，Runtime 只负责加载校验、能力匹配、执行期治理和元数据透传，不得越过服务边界直接执行业务事务。
- agent-intent 负责意图识别与路由，输出结构至少包含 `domain`、`intent`、`confidence`、`risk`、`needs_clarification`、`next_action`。
- agent-sandbox 通过 `@anthropic-ai/sandbox-runtime` 的 `srt` CLI 执行不可信代码，禁止在沙箱外执行用户提供的命令。

### 前端模块（agent-frontend）

- agent-frontend 采用 Vue 3 + Vite，组件库以项目实际选型为准（参考 README）。
- 接口请求统一封装，禁止在组件内散落 axios/fetch 调用。
- 状态管理以项目实际选型为准；全局状态集中管理，页面级状态保持本地化。
- 不允许把后端地址硬编码到代码中，通过 Vite 环境变量或代理配置注入。

### 数据与中间件

- MySQL / Redis / Elasticsearch / Kafka / MinIO / Milvus 等中间件按模块按需启用。
- 中间件连接信息、密钥、模型 API Key 等敏感配置必须通过环境变量或挂载的配置文件注入。
- 任何对生产数据库的结构变更必须通过迁移脚本管理，不允许直接手工改库。
- 后端 Flyway 脚本位于 `agent-backend/src/main/resources/db/migration/`。V1.0.0 至 V1.0.7 构成面向空数据库的规范基线，属于不可变资产；数据库变更只能追加更高版本的新脚本，禁止修改、删除、重命名或复用版本号，也禁止 repair、baseline 或手工覆盖绕过版本校验。文件命名遵循 `V<major>_<minor>_<patch>__<English_description>.sql`，描述使用英文单词、数字和下划线。
- Flyway 只允许初始化系统基线数据，以及由指定迁移维护的默认 `admin`、`user` 账号与对应角色关联。项目经历、简历、岗位收藏、求职进展、聊天记录、认证状态等用户私有业务数据必须通过受鉴权 API 写入，禁止进入 Flyway 和 Git；除受控默认身份种子迁移外，新迁移对私有业务表执行 `INSERT`、`UPDATE` 或 `DELETE` 均会被门禁拒绝。

## 常用命令

### agent-runtime

```bash
cd agent-runtime
uv sync --extra dev
uv run uvicorn server:app --host 0.0.0.0 --port 8010 --reload
```

### agent-sandbox

```bash
cd agent-sandbox
uv sync
uv run python server.py
```

启动前需在本机安装上游依赖：`npm install -g @anthropic-ai/sandbox-runtime`，Linux 还需要 `bubblewrap`、`socat`、`ripgrep`。

### Boss 按需工具

Boss 具体实现位于 `agent-tool` 的 `boss_browser` 工具中，`agent-runtime` 只保留工具选择、权限和代理调用。底层取数复用 jackwener/boss-cli（PyPI 包 `kabi-boss-cli`）的本地 Cookie 提取、HTTP API Client、请求抖动、退避和上游异常归类能力。推荐先复用本机常用浏览器的 Boss 登录态；二维码扫码登录作为兜底能力，dispatch 后若缺少必要 Web Cookie，会临时拉起一次性 headless Chromium 补齐 Cookie 后立即关闭。该步骤由 `BOSS_CLI_HEADLESS_COOKIE`（默认开启）控制，详见 [`agent-doc/业务能力/Boss直聘集成与岗位检索.md`](agent-doc/业务能力/Boss直聘集成与岗位检索.md)。

默认推荐二维码登录。登录成功后的 Cookie 由后端持久化到 PostgreSQL `auth_state`，每次调用时通过请求载荷注入 agent-tool 内存；禁止创建本地凭证目录或 `credential.json`。浏览器 Cookie 导入默认关闭，只有明确接受钥匙串授权时才能显式开启。

```bash
cd agent-tool
./scripts/start.sh
```

工具服务入口为 `POST /v1/tools/boss_browser/execute`；Runtime 代理入口为 `POST /v1/runtime/tools/boss_browser/invoke`。该工具负责登录态判断、岗位搜索、详情懒加载和在线简历读取。`status` 默认只读本地凭证，不主动请求 Boss；真实访问仍需要人工低频验证，命中验证码、安全验证、访问异常、限速或账号异常时立即停手。

### agent-backend

```bash
cd agent-backend
mvn spring-boot:run
```

### agent-frontend

```bash
cd agent-frontend
npm install
npm run dev
```

### 前端与交互改动的浏览器验证

凡是修改 `agent-frontend`、工作台交互、登录弹窗、SSE 流程、岗位卡片、原岗位预览、扫码登录、会话恢复、状态管理或任何用户可见 UI 行为，不能只跑单元测试或 `npm run build` 就声明完成，必须启动本地服务并使用浏览器做端到端验证。

推荐验证流程：

```bash
# 后端，Boss 凭证从 PostgreSQL auth_state 注入 Tool 内存
cd agent-backend
mvn spring-boot:run

# 前端
cd agent-frontend
npm run dev
```

浏览器验证至少覆盖：页面能打开、登录态判断符合预期、弹窗只在状态确认后出现、关键按钮可点击、SSE/加载态会结束、错误提示可见且不循环、用户可见结果符合需求。对于 Boss 直聘相关功能，还必须确认 `auth_state` 可恢复登录态、Tool 仅在内存中使用凭证、仓库和用户目录没有生成凭证文件。

交付说明必须写明浏览器验证的访问地址、执行的用户路径、观察到的结果，以及无法覆盖的原因。若没有完成浏览器验证，不允许声称“交互已走通”。

### agent-intent

`agent-intent` 为意图识别与路由模块，具体启动命令以模块 README、`pyproject.toml` / `pom.xml` / `package.json` 为准。

### agent-eval

```bash
cd agent-eval
uv sync --extra dev
uv run python server.py
uv run python -m pytest
```

服务提供 `GET /health`、`POST /v1/eval/trace`（Trace 规则评分）、`POST /v1/eval/run`（用例批量评估）、`POST /v1/eval/capabilities`（能力评估）与 `POST /v1/eval/judge`（裁判评估）。修改核心链路节点、Trace 字段、工具事件、意图路由或 Agent 输出结构时，必须同步更新 `agent-eval/app/grader.py`、`agent-eval/cases/` 与 `agent-eval/tests/`。

### .agent-harness

```bash
# 依赖与 harness 自检
./.agent-harness/scripts/doctor.sh

# 查看支持模块
./.agent-harness/scripts/verify.sh --list

# 分层验证：只跑测试/构建
./.agent-harness/scripts/verify.sh agent-backend --quick
./.agent-harness/scripts/verify.sh agent-frontend --quick
./.agent-harness/scripts/verify.sh agent-runtime --quick
./.agent-harness/scripts/verify.sh agent-eval --quick

# 交付门禁：测试/构建 + 行为评估
./.agent-harness/scripts/gate.sh all --quick
./.agent-harness/scripts/gate.sh agent-backend --quick

# 只跑评估层
./.agent-harness/scripts/evaluate.sh agent-backend
./.agent-harness/scripts/evaluate.sh agent-intent
./.agent-harness/scripts/evaluate.sh agent-eval
```

`.agent-harness/scripts/gate.sh` 是交付前统一质量门禁，`verify.sh` 负责测试/构建，`evaluate.sh` 负责行为评估，`judge.sh` 负责独立裁判，`run_goal.sh` 和 `loop.sh` 用于 Goal / Loop 自动化执行。Harness 与 Eval 的同步维护规则见下文“测试、Harness 与评估联动”。

### 其他模块

`agent-memory`、`agent-tool` 等模块的具体命令以各自 README、`pyproject.toml` / `pom.xml` / `package.json` 和 Dockerfile 为准。

## 总体开发规范

1. 禁止硬编码：环境地址、账号、密钥、模型参数、数据库连接、对外域名等必须放入配置文件或环境变量。
2. 多环境配置：至少区分 dev / prod，通过 `.env` 或挂载配置文件实现切换，不允许通过修改代码切换环境。`.env` 和 `.env.example` 只允许位于仓库根目录，任何子目录均不得创建这两个文件。
3. 依赖管理：新增依赖必须带版本号，Python 走 `uv` 与 `pyproject.toml`，Java 走 Maven 与 `pom.xml`，前端走 `package.json` 与 lock 文件。
4. 命名语义化：变量、函数、模块命名必须能在不读注释的情况下让人理解意图，避免无意义缩写。
5. 异常路径必做：禁止只实现 happy path，外部 HTTP、模型调用、检索调用必须设置超时、重试边界和错误处理。
6. 日志可定位：日志必须能辅助还原请求与任务上下文，至少包含 run_id、session_id、tool_name、node_id 等关键字段。
7. 敏感信息保护：禁止提交真实密钥、内网地址、个人账号、用户数据、大文件构建产物到仓库。
8. 容器化交付：每个对外服务模块必须维护 Dockerfile，并提供健康检查接口（如 `/health`）。
9. 跨模块协作：修改公共接口、数据结构、配置项时必须同步更新调用方代码、接口文档与示例。
10. 不要顺手重构：保持改动聚焦，与本次任务无关的代码不要顺手修改，避免引入难以追溯的变更。

## 接口设计规范

统一响应结构至少包含：

```json
{
  "code": 200,
  "message": "success",
  "data": {}
}
```

要求：

- `code` 必须具备明确语义，按 HTTP 状态码族表达业务结果：2xx 表示成功，4xx 表示客户端错误，5xx 表示服务端或下游依赖错误；错误码集中维护，禁止前后端各自约定。
- `message` 面向排错，禁止吞掉真实异常或返回无意义占位文本。
- Controller/API 层负责参数校验，业务层负责业务规则，底层异常统一转换为标准响应。
- LLM / Embedding / Rerank 等接口设计尽量兼容 OpenAI 协议，便于模型替换。
- 流式接口遵循 SSE 规范，支持 `data: [DONE]`、`finish_reason`、心跳包等常见场景，并通过 `stream` 参数同时支持流式与非流式返回。
- 同一含义字段在前端、后端、Agent 运行时、工具定义中保持命名一致。
- 接口文档优先通过 FastAPI 注解或 Swagger 注解自动生成，手写文档时应包含路由、请求方式、参数说明、示例、错误码、curl 示例等要素。

## Python 开发规范

### 工程结构

参考 `agent-runtime` 的组织方式：

```text
<module>/
├── app/
│   ├── api/            # FastAPI 路由
│   ├── core/           # 核心业务逻辑
│   ├── models/         # Pydantic 数据模型
│   └── server.py       # FastAPI app 工厂
├── config/             # YAML 配置文件
├── tests/              # 单元测试与集成测试
├── scripts/            # 开发与运维脚本
├── server.py           # Uvicorn 服务入口
├── Dockerfile
└── pyproject.toml
```

### 编码要求

- 数据结构优先使用 Pydantic 模型，避免使用无 schema 的字典传递跨层数据。
- I/O、HTTP、模型调用、检索调用必须设置超时、重试上限和明确的异常分支。
- 并发逻辑要明确线程或协程的安全边界，避免隐式共享全局可变状态。
- 日志中记录关键上下文字段：run_id、session_id、tool_name、node_id、operator_id 等。
- 单文件不要承载过多职责，模块切分以"职责单一、对外接口稳定"为目标。

### 工具与算子设计

- agent-runtime 内每个工具是独立、可复用、可测试的能力单元，通过 `BaseTool.definition()` 自描述。
- 工具的输入输出、必填项、错误码、是否流式必须文档化。
- 工具不得直接依赖平台内部数据库；必要配置通过运行时入参或配置文件传入。
- 高风险或破坏性工具必须显式声明，权限服务负责拦截。

## Java / Spring Boot 开发规范

- 推荐目录按 `controller`、`service`、`domain`、`repository`、`config`、`common`、`client` 组织，避免 Controller 直接承载业务逻辑。
- 请求和响应对象使用 DTO / VO 明确定义，禁止在 Controller、Service、Client 等跨层接口新增无 schema 的 `Map<String, Object>` 或 `MapBackedDto`；Map 只允许出现在 Repository/Mapper 行数据和 JSON 编解码等边界内部。
- 外部 HTTP、模型服务、检索服务、Runtime 服务调用必须设置超时、重试上限和熔断/降级边界。
- 日志需包含 request_id、session_id、run_id、operator_id 等关键字段，禁止输出密钥和完整敏感请求体。
- 单元测试覆盖 Service 层核心逻辑，接口测试覆盖 Controller 参数校验、统一响应和异常路径。

## 前端开发规范

- Vue 目录建议按 `src/components/`、`src/api/`、`src/stores/` 或 `src/state/`、`src/routes/`、`src/pages/`、`src/hooks/` 组织。
- 接口调用统一封装在 `src/api/` 或既有请求工具中。
- 跨页面共享状态集中管理，页面级状态保持本地化，避免把所有状态都提升为全局状态。
- 表单必须做前端校验，同时不能替代后端校验。
- 不依赖 `console` 作为业务逻辑实现，因为生产构建可能会移除 console/debugger。
- 复杂可视化页面（编排画布、日志展示、Trace 视图）注意懒加载、虚拟滚动与渲染性能。

## Agent 开发理念

本仓库的 Agent 工程实践遵循 [`agent-doc/工程规范/AI协作开发与质量验证规范.md`](agent-doc/工程规范/AI协作开发与质量验证规范.md) 的总体方向，可总结为以下几条。

### 文档即代码

- 在开始编码之前，先把架构、模块设计、库表、接口等文档写清楚，作为 AI 生成代码的输入。
- 文档随实现持续维护，禁止文档与代码长期不一致。
- 关键设计决策记录在仓库内，作为 AI 与人工协作的统一上下文。

### 规范驱动开发

- 每次让 Codex 生成代码前，先用自然语言把目标、边界、约束、禁止事项说清楚。
- 任务粒度尽量小、闭环明确，避免一次性让 AI 改动跨多个模块的大范围代码。
- 每实现若干功能模块后，安排一次重构，清理冗余、统一风格，防止技术债积累。

### 测试驱动开发

- 关键模块的测试用例优先于实现，由 AI 根据规范生成首版，人工校验后作为代码生成的契约。
- 用例覆盖主流程、边界条件与典型异常，不允许只覆盖 happy path。
- 测试通过只是最低门槛，人工评审仍需关注代码规范、日志完整性、并发安全与资源管理。

### 可验证目标

- 给 AI 的任务必须能在对话或日志中被验证，例如"`uv run pytest tests/test_x.py` 全部通过"、"`/health` 返回 200"。
- 模糊目标（如"性能更好"、"代码更优雅"）必须先转化为可执行命令或可对比指标。
- 长任务必须设定停止条件：最大轮次、最长运行时间、最大 diff 范围、允许修改的目录。

### Harness 优先

- 优先投入测试、lint、类型检查、可复现的本地运行环境，让 Agent 可以自我验证。
- CI、Dockerfile、启动脚本、健康检查、监控告警等基础设施在功能开发之前先打通。
- 自动化任务（Loop、夜间任务）必须显式设置预算与软着陆机制，失败时输出可读现场。

## Agent 设计原则

以下原则提炼自 [`系统架构与核心链路`](agent-doc/架构设计/系统架构与核心链路.md)、[`意图路由与工具安全`](agent-doc/运行时能力/意图路由与工具安全.md) 和 [`记忆管理与混合检索`](agent-doc/运行时能力/记忆管理与混合检索.md)，在涉及 Agent 运行时、工具、检索、记忆、权限、观测等改动时必须遵守。

### 运行时与 Agent Loop

- Agent Loop 形成"获取上下文 → 行动 → 验证"的闭环，任何动作都要有可验证手段（测试、类型检查、状态比对）。
- 循环边界必须齐备：max_turns、token/费用预算、permission mode、compaction、checkpoint、人类中断，缺一不可。
- 长任务必须状态化执行：持久化、检查点、重试、人类介入，禁止只靠从头重跑。
- 上下文操作支持 Continue / Rewind / Compact / Clear，错误路径要能被移除而不是用噪声覆盖。
- Compaction 是任务状态迁移，必须保留目标、修改、决策、失败、下一步关键字段，不是单纯摘要。
- 子 Agent 用独立上下文承载噪声探索，只把摘要返回主会话；并行任务用 worktree 隔离避免文件冲突。
- 会话期内不切换模型，缓存按模型隔离；需要轻量模型时用 Subagent 承担。

### 工具体系

- 工具单位是任务而非资源，围绕真实任务封装，避免把底层 API 全量暴露给模型。
- 工具是 ACI 而非 API 包装：参数清晰、返回结构化、错误可恢复、危险操作有权限。
- 工具命名用"业务域_资源_动作"，描述写明适用、不适用、参数、返回、错误处理，等同 Prompt 资产。
- 工具登记 8 要素：名称、描述、参数、返回、错误、权限、示例、评测，破坏性变更必须走新版本。
- 工具返回统一字段：status、summary、data、warnings、next_actions、trace_id；错误结构含 retryable 与 suggested_action。
- 大规模工具采用 Tool Search 延迟加载，稳定前缀常驻，按需展开 Schema。
- 数据密集与中间数据搬运用 Programmatic Tool Calling 或代码执行，避免污染上下文。
- 可执行对象只有四类：MCP、API、CLI、代码；Skills 仅作操作手册不直接执行，CLI 与代码必须进沙箱。

### 检索与上下文

- 检索是上下文调度而非"向量库召回"，目标是最小高信号 token，而非最大 recall。
- 离线 Contextual Retrieval 与运行时 Agentic Search 必须组合使用。
- 召回追求 recall，进入 Prompt 前必须经过 rerank、去重、top-K、摘要压缩。
- 工具返回结构化线索（路径、链接、摘要、ID），支持局部读取与渐进披露。
- 检索利用元数据（路径、目录、时间戳、依赖关系）作为信号，不要只依赖语义相似。
- 用引用而非完整内容做长期上下文，按需通过工具加载具体细节。

### 记忆机制

- 区分四类信息：当前步骤、当前任务、跨任务长期、跨会话语义，分别用上下文 / 任务状态 / Memory / Persona 承载。
- 在线写入轻量，离线做梦（Dreams）负责去重、冲突解决、洞察提炼，新 Store 不覆盖原 Store。
- 记忆系统必须支持更新、覆盖、过期、删除与回滚，不能只追加。
- 写入比检索更关键，需主动判断"是否值得记"，低质量写入会长期污染系统。
- 检索用 BM25 + Vector + Graph 混合并以 RRF 融合，单一向量召回不足以应对符号化代码场景。
- 长期记忆是攻击面：写入、存储、召回、执行、共享、遗忘六个环节都要鉴权与审计。

### 意图识别与路由

- 意图识别分层：Domain Router → Intent Classifier → Clarification Gate → Tool Router → Action Authorization。
- 业务意图不等于动作授权，高风险动作必须由独立的 transcript classifier 复核。
- 分类输出结构化（domain、intent、confidence、secondary、risk、needs_clarification、next_action），允许 unknown 与澄清。
- 工具路由按意图收窄候选工具集，禁止一上来就暴露所有高风险工具。
- 高频稳定意图下沉到规则、小模型或 embedding 分类器，强模型只处理长尾与高风险样本。

### 安全、权限与沙箱

- 安全规则能写成 hook、沙箱、权限程序，就不要只写进 Prompt。
- Permission Prompt 不能替代边界设计，过度依赖会导致 Approval Fatigue。
- 文件系统隔离与网络隔离必须成对出现，并约束子进程继承。
- 凭据采用 scoped credential，长期密钥绝不进入 Agent 可控环境；Git 等敏感操作走代理注入。
- 工具结果、网页、Shell 输出默认是不可信数据，进入上下文前过 Prompt Injection Probe。
- 高风险动作走 Transcript Classifier，只看用户消息与 tool call，剥离 assistant 解释。
- 连续拒绝要有 backstop（如 3 次或 20 次升级人工），headless 直接终止。
- 沙箱（srt）同时管控文件、网络与子进程，敏感目录默认禁读写。

### Prompt 与缓存

- 缓存是前缀级字节匹配，动态时间戳、随机顺序绝不能进入前缀。
- Prompt 分层布局：静态系统 → 工具定义 → 项目规则 → 会话上下文 → 当前轮消息，越靠前越稳定。
- 工具集合在会话期内顺序稳定，模式切换通过工具（如 EnterPlanMode）而非增删工具表达。
- 状态更新走追加消息（如 system-reminder），禁止修改前缀。
- Compaction 用 Cache-safe Forking：继承父会话前缀，只在末尾追加压缩指令，并预留 Compaction Buffer。
- 把 cache hit rate、cache_creation / read tokens 当作生产 SLO 指标监控。
- Prompt 是行为代码：每次变更走 diff、ablation、per-model eval、灰度与可回滚。

### 评测与质量观测

- Agent Eval 测的是模型 + Harness + 工具 + 环境的组合，结果必须带完整版本与资源配置。
- 优先评估环境最终状态（Outcome），其次才看模型说了什么；不要只检查路径。
- Grader 三级组合：规则负责正确性，LLM Judge 负责开放质量并要人类校准，Human 负责高风险抽检。
- 区分 Capability Eval（有爬坡空间）与 Regression Eval（接近 100%），任务在两者间流动。
- 同时跟踪 pass@k 与 pass^k：pass@k 衡量能力上限，pass^k 衡量上线可靠性。
- 用户反馈必须沉淀为可复现 task + grader，进入 regression suite。
- 质量信号分层归因：模型 / Prompt / 上下文 / 工具 / Harness / 基础设施 / 评测七层都可能是根因。
- 防 Eval 污染与 awareness：用私有 / 动态测试集、扫描泄漏、监控异常 token 与搜索路径。

### 核心链路

- 会话入口必须先做发布版本加载、身份权限预检与内容安全预检，违规直接拦截。
- 意图识别后分流：低置信走澄清，稳定流程走 DAG，外部 Agent 走准入治理，开放域走模型生成，复杂任务走 Agent Loop。
- 复杂任务必须走 Planner 生成计划 + 计划审核与预算预估 + 状态初始化 + 记忆/上下文预算装配后再进入 Loop。
- Agent Loop 动作分四类（工具、检索、用户参与、状态更新），工具与检索都要经过治理网关与结果压缩。
- 每个节点都要写 Trace，验证反思后才决定继续、降级或结束。
- 长期记忆写入需经提取、去重、冲突检测与权限判断。
- 全链路汇入 Trace 汇总与评估分析模块，形成可观测、可回归的闭环。
- 观测按 OpenTelemetry 上报 Trace / 日志 / 指标，审计字段固定 14 项以上，支撑回放、归因与评估样本沉淀。

## 测试、Harness 与评估联动

提交前先执行 `./scripts/format-code.sh` 统一格式，或执行 `./scripts/format-code.sh --check` 进行只读检查。Java 使用 Maven Spotless，Python 使用 Ruff，Vue/JavaScript/CSS 使用 Prettier；空 `record`、空类和空接口必须保留多行类型体，不得压缩为单行声明。注释应解释职责、协议语义、边界条件和非直观设计，禁止逐行复述代码或批量生成无信息量的模板注释。

提交前按修改范围执行最小必要验证：

- Java / Spring Boot 模块：执行 `mvn test` / `mvn verify`（仓库未提供 mvnw Wrapper，Maven 生命周期会自动执行 Spotless 检查）。
- Python 模块：执行 `uv run ruff check`、`uv run ruff format --check` 和 `uv run python -m pytest`，必要时附带覆盖率检查。
- Vue 前端：执行 `npm run format:check`、`npm run lint`、`npm test`、`npm run build`，关键页面需本地 `npm run dev` 联调。
- Flyway 迁移变更：必须运行 `./.agent-harness/scripts/check_flyway_migrations.py`；`verify.sh agent-backend --quick` 和 `gate.sh agent-backend --quick` 会自动执行该检查。
- 接口变更：补充或更新 curl 示例、Mock 数据、接口文档。
- 配置变更：在 dev 环境验证启动流程，确认健康检查通过。
- Agent 行为、意图、工具、Trace、观测字段变更：同步执行或更新 `agent-eval` 评估，并通过 `.agent-harness/scripts/evaluate.sh <target>` 或 `gate.sh <target> --quick` 验证。

Harness 联动规则：

1. 修改任一模块的启动命令、测试命令、构建命令、目录结构、健康检查接口或依赖管理方式时，必须同步检查 `.agent-harness/scripts/verify.sh`、`doctor.sh`、`gate.sh` 和 `.agent-harness/README.md`。
2. 修改 Agent 核心链路、SSE 事件、Trace 节点、工具事件、意图分类输出、风险等级、权限边界或用户可见任务流程时，必须同步检查 `.agent-harness/scripts/evaluate.sh`、`agent-eval/app/grader.py`、`agent-eval/cases/` 和 `agent-eval/tests/`。
3. 新增跨模块能力时，应优先补充或更新 `.agent-harness/goals/` 中的 Goal 示例；新增周期性巡检、回归检查或健康检查时，应补充 `.agent-harness/loops/`。
4. 修改 Flyway 迁移规则、迁移目录或数据库结构变更流程时，必须同步维护 `.agent-harness/scripts/check_flyway_migrations.py`、`.agent-harness/scripts/verify.sh` 和 `.agent-harness/README.md`。
5. 代码已经更新但 Harness 或 Eval 未更新时，必须在回复中明确说明“不需要同步更新”的理由；不能默认忽略。
6. 跨模块交付优先运行 `./.agent-harness/scripts/gate.sh all --quick`；单模块交付至少运行对应模块的 `gate.sh <target> --quick` 或说明无法运行原因。

如果本地环境无法完整验证，必须在回复或提交说明中明确未验证项与原因。

## Git 与版本规范

提交信息使用英文，遵循 Conventional Commit 风格：

```text
<type>: <short description>
```

可用类型：

- `feat`: new feature
- `fix`: bug fix
- `perf`: performance improvement
- `refactor`: code refactoring
- `docs`: documentation changes
- `style`: formatting changes
- `test`: tests added or changed
- `build`: build system or dependency changes
- `revert`: revert previous commit
- `ci`: CI changes
- `release`: release changes
- `workflow`: workflow changes
- `chore`: other changes

要求：

- 一次提交只做一类事情，跨模块大改应拆分提交。
- 分支名建议使用 `feature/`、`feat/`、`fix/`、`bugfix/`、`hotfix/`、`release/`、`chore/` 前缀。
- 版本号采用语义化版本 X.Y.Z：bug 修复升 Z，向下兼容的新增功能升 Y，不兼容的重大变更升 X。

## 部署规范

- 每个模块必须提供 Dockerfile，并通过健康检查接口暴露存活状态。
- 不同环境维护独立配置文件，禁止把生产配置写死在代码或镜像中。
- 环境变量通过 `-e` 或 `.env` 注入，敏感信息走 Secret 或配置中心。
- 镜像标签不允许只用 `latest`，应包含版本号、提交哈希或构建时间。
- 上线前必须确认回滚路径：保留上一版镜像、保留上一版配置、数据库变更具备回滚脚本。

## 安全与合规

- 禁止提交密钥、Token、生产账号、真实用户数据、敏感日志。
- 日志中避免输出完整请求体、模型密钥、身份证号、手机号等敏感信息。
- 外部模型、向量库、知识服务调用必须通过配置化方式接入，不允许把密钥写入代码。
- 沙箱执行用户代码时必须明确网络、文件系统的 allow/deny 策略，默认拒绝高风险操作。

## Codex 工作方式

在本仓库中工作时，建议遵循以下流程：

1. 先判断改动涉及哪个模块，阅读对应 README、`agent-doc/` 中的开发理念与现有实现。
2. 优先复用项目已有结构、工具类、响应模型、日志组件与异常处理方式，避免引入重复抽象。
3. 不要引入新的框架、状态管理库、中间件或复杂抽象，除非用户明确要求且有充分理由。
4. 修改接口、数据结构、配置、数据库时，同步检查所有调用链路，避免遗漏下游影响。
5. 保持改动聚焦，避免顺手重构无关代码，遇到无关问题应单独提出而不是混入当前改动。
6. 生成代码与脚本时不要包含任何表情符号。
7. 输出 Git 提交日志时使用纯英文、简短凝练的 Conventional Commit 风格。
8. 输出 Mermaid 图表时考虑低版本兼容，避免使用过新的语法特性。
9. 输出项目材料类文字时使用正式语言，采用大段叙述而非碎片化列表。
10. 默认使用中文回复，除非用户指定其他语言。
