# agent-backend

`agent-backend` 是 `job-buddy` 的 Java 8 字节码目标 + Spring Boot 业务后端，也是前端默认访问的 BFF/API 入口。当前职责不再是单一“复杂问答 MVP”，而是承载求职工作台的业务 API、登录态、文件与数据管理，并代理调用 `agent-runtime`、`agent-intent`、`agent-memory`、`agent-tool`、`agent-eval` 等服务。Boss 直聘能力作为 `agent-tool` 的 `boss_browser` 工具注入，由 Runtime 编排调用。

## 当前能力

- 健康检查与统一响应：`GET /api/health`，响应结构为 `code`、`message`、`data`。
- 用户登录：`/api/auth/login`、`/api/auth/me`、`/api/auth/logout`。
- Boss 登录代理：`/api/boss/login-qr`、`/api/boss/login-status`、`/api/boss/login-cancel`。
- 对话问答：`/api/chat/ask`、`/api/chat/stream`、会话列表、消息列表、删除会话。
- 简历管理：求职画像、简历上传解析、Boss 在线简历同步、资源上传、预览、缩略图、下载、分析、删除。
- 岗位能力：收藏岗位保存/查询/分析/删除，岗位详情懒加载。
- 求职旅程：求职目标、投递/面试记录、进展分析。
- 面试题库：题库分页、元数据、导入、生成、批量处理、练习/考试、代码样例运行。
- 项目深挖：项目管理、材料管理、项目面试题生成。
- Prompt 与设置：前端提示词、用户画像上下文、系统设置、记忆列表管理。

## 技术栈

- JDK 8（当前本地验证版本为 1.8.0_333；Docker 镜像使用 Temurin 17 构建/运行，但 Maven 编译目标仍为 1.8）
- Spring Boot 2.7.18
- MyBatis Plus
- Flyway
- PostgreSQL
- Redis
- MinIO
- Knife4j / OpenAPI
- Maven 3.8+（当前本地验证版本为 3.8.6；仓库当前未提供 `mvnw`）

## 主要目录

```text
agent-backend/
├── src/main/java/com/jobbuddy/backend/
│   ├── common/          # 通用配置、响应、异常、DTO、工具类
│   └── modules/         # 业务模块
│       ├── auth/        # 用户登录与 Boss 登录代理
│       ├── chat/        # 对话、SSE、Runtime/Intent 集成
│       ├── resume/      # 简历、画像、文件与对象存储
│       ├── job/         # 岗位收藏与详情
│       ├── journey/     # 求职旅程
│       ├── interview/   # 面试题库与练习
│       ├── project/     # 项目深挖
│       ├── prompt/      # Prompt 与画像上下文
│       └── system/      # 系统设置与记忆管理
├── src/main/resources/
│   ├── application.yml
│   ├── schema.sql
│   └── prompts/registry.yaml
├── scripts/start.sh
├── Dockerfile
└── pom.xml
```

## 本地启动

在仓库根目录准备 `.env`：

```bash
cp ../.env.example ../.env
```

然后按需修改 PostgreSQL、Redis、MinIO、Runtime、Boss 浏览器按需工具等配置。

单独启动：

```bash
cd agent-backend
mvn spring-boot:run
```

使用根目录一键脚本启动时，会自动注入 `SERVER_PORT`、`AGENT_SANDBOX_URL`、`AGENT_RUNTIME_URL` 等运行参数；Boss 能力通过 Runtime 的 `boss_browser` 工具按需执行：

```bash
./scripts/start-all.sh
```

## 常用地址

- 健康检查：<http://localhost:8080/api/health>
- Knife4j 文档：<http://localhost:8080/doc.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

## 常用接口

```bash
curl http://localhost:8080/api/health

curl -X POST http://localhost:8080/api/chat/ask \
  -H 'Content-Type: application/json' \
  -d '{"question":"请分析我的求职状态"}'

curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我分析收藏岗位"}'
```

完整接口以 Controller 和 Knife4j 文档为准。

## 配置说明

关键环境变量在根目录 [.env.example](../.env.example) 中维护：

- `SERVER_PORT`
- `SPRING_DATASOURCE_URL`
- `SPRING_DATASOURCE_USERNAME`
- `SPRING_DATASOURCE_PASSWORD`
- `SPRING_REDIS_HOST`
- `SPRING_REDIS_PORT`
- `AGENT_RUNTIME_URL`
- `AGENT_INTENT_URL`
- `AGENT_MEMORY_URL`
- `AGENT_TOOL_URL`
- `AGENT_EVAL_URL`
- `BOSS_CLI_*`（Runtime `boss_browser` 工具使用）；`BOSS_BROWSER_*` 中的限速项继续兼容
- `JOB_BUDDY_MINIO_*`
- `JOB_BUDDY_DEFAULT_USER_ID`
- `JOB_BUDDY_RESUME_RUNTIME_WORKSPACE`

新增、删除或重命名配置项时，必须同步更新 `.env.example` 和相关 README。

## 验证

```bash
cd agent-backend
mvn test
```

后端质量门禁：

```bash
../.agent-harness/scripts/verify.sh agent-backend --quick
../.agent-harness/scripts/gate.sh agent-backend --quick
```

修改聊天主链路、SSE、Runtime 代理、Trace、Intent、工具调用或评估字段时，还需要检查 `.agent-harness/scripts/evaluate.sh`、`agent-eval` 用例和评分器是否需要同步更新。
