# agent-backend

`agent-backend` 是 JobBuddy 的 Java 17 + Spring Boot 3 业务后端和 BFF/API 入口，负责认证授权、业务事务、关系数据、对象文件、会话持久化及下游编排。Boss 工具的具体实现位于 agent-tool，Backend 的业务请求经 Runtime 工具代理统一治理。

## 能力范围

| 领域         | 主要入口与职责                                                                                      |
| ------------ | --------------------------------------------------------------------------------------------------- |
| 基础与认证   | `/api/health`、`/api/auth/**`、`/api/admin/**`；统一响应、会话、动态 RBAC 和租户隔离                |
| 对话与 Agent | `/api/chat/**`、`/api/analysis-tasks/**`；Intent/Runtime 编排、SSE 中继、消息与任务恢复             |
| Boss 与岗位  | `/api/boss/**`、`/api/jobs/**`；登录状态、候选业务编排、收藏、详情和分析                            |
| 简历与旅程   | `/api/resume/**`、`/api/journey/**`；画像、PDF 与资源、撰写版本、投递台账                           |
| 练习与项目   | `/api/interview/**`、`/api/project-deep-dive/**`；题库、判题、项目材料和问题管理                    |
| 平台能力     | `/api/settings/**`、`/api/prompts/**`、`/api/workspace/**`；动态参数、记忆代理、Prompt 与工作区状态 |

## 技术栈

| 类别       | 选型                                          |
| ---------- | --------------------------------------------- |
| 运行与构建 | Java 17、Maven 3.8+；仓库不提供 `mvnw`        |
| Web 与数据 | Spring Boot 3.5.16、MyBatis Plus、Flyway      |
| 基础设施   | PostgreSQL、Redis、MinIO                      |
| API 文档   | SpringDoc OpenAPI、Knife4j UI；增强定制器关闭 |

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

先在仓库根目录准备 `.env`：

```bash
cp .env.example .env
```

配置 PostgreSQL、Redis、MinIO 和 Agent 服务地址后再启动。Flyway 创建 `admin` 与 `user`，初始密码均为 `12345678`；应在受控环境通过用户管理立即重置，再切换到 `JOB_BUDDY_ENVIRONMENT=production`，否则 Backend 会拒绝启动。生产环境也禁止关闭认证。Boss 凭据持久化还要求以 `openssl rand -base64 32` 生成并稳定保存 `JOB_BUDDY_BOSS_CREDENTIAL_ENCRYPTION_KEY`。

单独启动：

```bash
cd agent-backend
java -version  # 必须为 17 或更高版本
mvn spring-boot:run
```

使用根目录一键脚本启动时，会自动注入 `SERVER_PORT`、`AGENT_SANDBOX_URL`、`AGENT_RUNTIME_URL` 等运行参数；Boss 能力通过 Runtime 的 `boss_browser` 工具按需执行。以下命令同样在仓库根目录运行：

```bash
./scripts/start-all.sh
```

## 常用地址

- 健康检查：<http://localhost:8080/api/health>
- Knife4j 文档：<http://localhost:8080/doc.html>
- OpenAPI JSON：<http://localhost:8080/v3/api-docs>

## 最小接口验证

```bash
curl http://localhost:8080/api/health

curl -X POST http://localhost:8080/api/auth/login \
  -H 'Content-Type: application/json' \
  -d '{"username":"admin","password":"12345678"}'

curl -N -X POST http://localhost:8080/api/chat/stream \
  -H 'Authorization: Bearer <token>' \
  -H 'Content-Type: application/json' \
  -d '{"message":"帮我分析收藏岗位"}'
```

完整契约以 Controller、OpenAPI JSON 和 Knife4j 为准。

## 配置说明

根目录 [.env.example](../.env.example) 是配置键的唯一模板：`SPRING_DATASOURCE_*` 与 `SPRING_REDIS_*` 管理数据服务，`AGENT_*_URL` 管理下游地址，`JOB_BUDDY_MINIO_*` 管理对象存储，`JOB_BUDDY_BOSS_CREDENTIAL_ENCRYPTION_KEY` 管理 Boss 凭据加密，`JOB_BUDDY_RESUME_RUNTIME_WORKSPACE` 管理 Runtime 共享工作区。Boss 请求与限速参数使用 `BOSS_CLI_*`。配置变化必须同步模板与相应主题文档，不在本 README 复制默认值全集。

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

聊天主链路、SSE、Runtime 代理、Trace、Intent、工具或评估字段变化时，还要同步检查 `.agent-harness/scripts/evaluate.sh`、agent-eval 用例和评分器。跨模块设计见[系统架构与核心链路](../agent-doc/架构设计/系统架构与核心链路.md)，认证和接口细节见[账号认证与权限体系](../agent-doc/架构设计/账号认证与权限体系.md)。
