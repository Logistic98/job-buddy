# agent-backend

`agent-backend` 是 `job-buddy` 的 Java 17 + Spring Boot 3 业务后端和 BFF/API 入口，负责求职工作台的业务 API、认证、文件与数据管理，并编排 `agent-runtime`、`agent-intent`、`agent-memory`、`agent-tool`、`agent-eval` 等服务。Boss 直聘能力由 `agent-tool` 的 `boss_browser` 工具提供，并由 Runtime 统一治理。

## 能力范围

- 健康检查与统一响应：`GET /api/health`，响应结构为 `code`、`message`、`data`。
- 多租户用户登录：`/api/auth/login`、`/api/auth/me`、`/api/auth/logout`，登录只提交全局唯一用户名和密码，租户由账号记录自动解析。
- 动态 RBAC 管理：平台设置通过 `/api/admin/users` 与 `/api/admin/rbac` 管理本租户用户、多角色、动态菜单和角色菜单授权；管理能力按权限码判断，不能访问其他用户业务数据。
- Boss 登录代理：`/api/boss/login-qr`、`/api/boss/login-status`、`/api/boss/login-cancel`。
- 对话问答：`/api/chat/ask`、`/api/chat/stream`、会话列表、消息列表、删除会话。
- 简历管理：求职画像、简历上传解析、Boss 在线简历同步、资源上传、预览、缩略图、下载、分析、删除。
- 岗位能力：收藏岗位保存/查询/分析/删除，岗位详情懒加载。
- 求职旅程：求职目标、投递/面试记录、进展分析。
- 面试题库：题库分页、元数据、导入、生成、批量处理、练习/考试、代码样例运行。
- 项目深挖：项目管理、材料管理、项目面试题生成，以及问题的手动新增、编辑、删除；重新生成仅替换 AI 生成的问题，手动维护（`source=manual`）的问题会保留。
- Prompt 与设置：前端提示词、用户画像上下文、系统设置、记忆列表管理。

## 技术栈

- JDK 17+（当前本地验证版本为 17.0.6；Maven 编译目标和 Docker 构建/运行镜像均为 Java 17）
- Spring Boot 3.5.16
- MyBatis Plus
- Flyway
- PostgreSQL
- Redis
- MinIO
- SpringDoc OpenAPI / Knife4j UI（禁用增强定制器）
- Maven 3.8+（验证版本 3.8.6；仓库不提供 `mvnw`）

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

然后按需修改 PostgreSQL、Redis、MinIO、Runtime、Boss 浏览器按需工具等配置。Flyway 会初始化 `admin` 管理员和 `user` 普通用户，初始密码均为 `12345678`；公开部署后应立即在平台设置的用户管理中重置密码。需要使用 Boss 登录时，还必须通过 `openssl rand -base64 32` 生成并长期保存 `JOB_BUDDY_BOSS_CREDENTIAL_ENCRYPTION_KEY`，缺少该密钥时 Boss 凭据持久化会安全关闭。

单独启动：

```bash
cd agent-backend
java -version  # 必须为 17 或更高版本
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

curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"12345678"}'

curl -X POST http://localhost:8080/api/chat/ask \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"message":"请分析我的求职状态"}'

curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我分析收藏岗位"}'

# 项目概要列表只返回计数，材料和问题通过详情接口按需加载
curl http://localhost:8080/api/project-deep-dive/projects \
  -H 'Authorization: Bearer <token>'

curl http://localhost:8080/api/project-deep-dive/projects/<projectId> \
  -H 'Authorization: Bearer <token>'

# 手动新增问题，编辑/删除按 questionId 操作；手动维护的问题在重新生成时保留
curl -X POST http://localhost:8080/api/project-deep-dive/projects/<projectId>/questions \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"question":"这个模块的幂等是怎么保证的？","answer":"**要点：**\n- 唯一键约束\n- 重试去重","category":"技术难点","difficulty":"深入"}'

curl -X PUT http://localhost:8080/api/project-deep-dive/questions/<questionId> \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"question":"编辑后的问题","answer":"编辑后的参考答案"}'

curl -X DELETE http://localhost:8080/api/project-deep-dive/questions/<questionId> \
  -H 'Authorization: Bearer <token>'
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
- `BOSS_CLI_*`（Runtime `boss_browser` 工具使用，包含 Cookie、请求和限速参数）
- `JOB_BUDDY_MINIO_*`
- `JOB_BUDDY_DEFAULT_USER_ID`
- `JOB_BUDDY_BOSS_CREDENTIAL_ENCRYPTION_KEY`：32 字节随机密钥的 Base64 编码，用于 AES-256-GCM 加密 Boss Cookie；必须稳定保存并通过受控流程轮换
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
