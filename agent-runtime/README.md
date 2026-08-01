# agent-runtime

基于 LangGraph 的智能体运行时核心实现，承载 job-buddy 的通用 Agent Core、ToolOps、检查点和可观测能力。

## 核心能力

Runtime 以 LangGraph 组织“任务理解—上下文—Planner—工具搜索—权限与预算—执行观察—验证终态”。声明工具的任务进入完整状态图，无工具生成任务可走 direct synthesis，但仍受相同的预算、Trace 和安全约束。Profile、Workflow 与 Prompt 从 `config/` 加载；Workflow 只提供路由元数据，外部业务动作仍由 Backend 在事务边界内执行。

工具层提供注册、别名、搜索、权限和统一 Tool Runtime，`boss_browser` 仅为 agent-tool 的代理。高风险动作还要经过独立 Transcript 复核，Shell 受命令规则和 Sandbox 双重约束。`sandbox_code_execute` 的 Python 调用可声明有界 PyPI 依赖，由 agent-sandbox 在单次执行目录安装 wheel；依赖安装与候选代码执行分别使用 90 秒和 25 秒超时，Runtime 不接受解释器、索引地址、cwd 或网络策略扩权。

模型通过 OpenAI 兼容协议接入，连接、重试、超时和工具 Schema 由 YAML 与环境变量配置。稳定 Prompt 和工具目录位于动态上下文之前，以支持服务端前缀缓存。每个关键阶段写入 Checkpoint 并记录 Trace；完整部署使用 `AGENT_RUNTIME_DATABASE_URL` 持久化到 PostgreSQL，未配置 DSN 的进程内实现只用于本地验证。持久化前会移除原始消息和可重建的个人上下文，并递归脱敏。

## 代码组织

`app/api/` 提供 HTTP 与 SSE 接口，`app/core/` 承载 Runtime 通用能力，
`app/models/` 定义稳定契约，`app/tools_builtin/` 保存内置工具；Profile、Workflow 和 Prompt
位于 `config/`。具体模块由源码和测试自动发现，架构职责以
[系统架构与核心链路](../agent-doc/架构设计/系统架构与核心链路.md)为准。

## 本地运行

```shell
$ uv sync --extra dev
$ uv run uvicorn server:app --host 0.0.0.0 --port 8010 --reload
```

`uv run python main.py` 只执行不调用外部模型的最小 Runtime 示例，不启动 HTTP 服务。开发服务也可以使用脚本启动：

```shell
$ uv run ./scripts/run_dev.sh
```

## Docker 部署

```shell
$ docker build -t job-buddy-runtime:1.0.0 .
$ docker run --name job-buddy-runtime -p 8010:8010 -d job-buddy-runtime:1.0.0
```

模型服务统一通过 [config/config.yaml](config/config.yaml) 声明配置结构，连接地址、密钥等敏感值通过环境变量注入；如需在容器中挂载外部配置文件：

```shell
$ docker run --name job-buddy-runtime \
  -p 8010:8010 \
  -v $(pwd)/config/config.yaml:/app/config/config.yaml \
  -d job-buddy-runtime:1.0.0
```

## API

| 方法与路径                             | 用途                 |
| -------------------------------------- | -------------------- |
| `POST /v1/agent/runs`                  | 非流式运行           |
| `POST /v1/agent/runs/stream`           | SSE 运行             |
| `GET /v1/runtime/tools`                | 查看已注册工具       |
| `POST /v1/runtime/tools/{name}/invoke` | 受治理的工具调用     |
| `GET /v1/runtime/config`               | 查看脱敏配置         |
| `POST /v1/runtime/config/reload`       | 重载配置             |
| `GET /v1/runtime/trace-events`         | 查询 Trace           |
| `GET /v1/runtime/checkpoints`          | 查询 Checkpoint 摘要 |

最小运行示例：

```shell
$ curl -X POST http://localhost:8010/v1/agent/runs \
  -H 'X-Internal-Service-Token: <internal-token>' \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"请回显 hello runtime"}]}'
```

失败或中断的图执行可在同一会话中按来源运行标识继续。恢复请求必须携带当前鉴权身份对应的
`metadata.tenant_id`、`metadata.user_id`，并使用原始用户消息和附件引用重建已从持久化快照剥离的上下文：

```shell
$ curl -X POST http://localhost:8010/v1/agent/runs/stream \
  -H 'X-Internal-Service-Token: <internal-token>' \
  -H 'Content-Type: application/json' \
  -d '{"session_id":"<session-id>","resume_from_run_id":"<failed-run-id>","messages":[{"role":"user","content":"<original-message>"}],"metadata":{"tenant_id":"<tenant-id>","user_id":"<user-id>","turn_id":"<original-turn-id>"}}'
```

恢复会创建新的 `run_id` 和 `trace_id`，并通过 `resumed_from_run_id`、`resumed_from_stage`
保留血缘。来源运行只能领取一次；成功终态、待确认终态、归属或原始请求不匹配以及可能重放非只读工具的现场都会被拒绝。
若 Graph 已完成而最终答案流中断，恢复只重新执行答案合成，不重新运行 Graph 或工具。历史缺少租户、用户或原始 `turn_id` 归属的快照不允许自动续跑。

`GET /v1/runtime/checkpoints` 只返回当前归属范围内的快照摘要。所有查询必须同时传入
`X-Tenant-Id` 与 `X-Operator-Id`，接口不会返回内部恢复状态正文。

通过 Runtime 代理调用 Boss 工具的只读限速状态：

```shell
$ curl -X POST http://localhost:8010/v1/runtime/tools/boss_browser/invoke \
  -H 'X-Internal-Service-Token: <internal-token>' \
  -H 'Content-Type: application/json' \
  -d '{"arguments":{"operation":"rate","payload":{}}}'
```

未配置 `AGENT_INTERNAL_SERVICE_TOKEN` 的本地开发环境可以省略该请求头；production/prod 环境必须配置并传递。

## 配置

模型、预算、工具、权限、安全、检查点和观测配置统一维护在
[config/config.yaml](config/config.yaml)，支持 `${ENV_NAME:default}` 占位符；本地完整栈使用根目录
[.env.example](../.env.example) 作为键集合模板。README 不复制配置字段和默认值，避免与可执行配置漂移。
真实 API Key、数据库密码或中间件密码不得提交。

默认读取 `config/config.yaml`。如需指定其他配置文件路径，可设置：

```shell
$ export JOB_BUDDY_CONFIG=/path/to/config.yaml
```

运行中可通过接口重新加载配置文件，适合挂载配置更新后热切换：

```shell
$ curl -X POST 'http://localhost:8010/v1/runtime/config/reload'
$ curl -X POST 'http://localhost:8010/v1/runtime/config/reload?config_path=/path/to/config.yaml'
```
