# job_buddy_runtime

基于 LangGraph 的智能体运行时核心实现，承载 job-buddy 的通用 Agent Core、ToolOps、检查点和可观测能力。

## 核心能力

- Runtime 主链路：会话入口、目标理解、Planner、Tool Search、预算检查、工具执行、观察与结束判断。
- 工具体系：工具定义、工具注册中心、别名索引、工具检索、权限检查、统一 Tool Runtime；Boss 浏览器能力在 Runtime 中仅保留 `boss_browser` 代理工具，具体实现位于 agent-tool。
- LangGraph 编排：声明必需工具的任务使用状态图组织目标理解、上下文收集、Tool Search、Planner、预算、执行、全量观察、反思和结束判断；无工具纯生成任务使用受同等预算、Trace 和安全约束的 direct synthesis 快路径。
- Workflow 注册与路由：启动时加载并校验 `config/workflows/`，按 Profile 与 entry capability 将只读流程元数据加入任务理解、directive 和 Trace；外部业务动作仍由声明的 Backend/BFF 执行。
- 检查点：每个关键阶段写入 PostgreSQL 检查点，支持中断恢复和审计追踪；启用但未配置 PostgreSQL DSN 时明确告警并仅在本地内存兜底。持久化前删除原始消息、可重建的个人上下文及摘要副本，仅保留非个人上下文骨架并执行递归脱敏。
- OpenAI 兼容模型：默认模型名为 `deepseek-chat`，统一从 YAML 读取模型服务配置，支持完整 chat/completions URL、重试、超时和工具 Schema。
- Prompt Cache：Planner 将稳定系统提示和稳定排序的候选工具目录放在动态上下文之前，适配 DeepSeek 服务端基于公共前缀的自动缓存。
- 权限安全：支持 allow/deny、只读工具、破坏性工具、高风险工具、独立 transcript 复核和 Shell allow/deny 规则。
- 可观测：记录 run_start、plan_created、permission_check、tool_execute_end、observe、reflect、finalize 等 Trace 事件；澄清、预算、权限和失败终态保留明确的 status 与 stop_reason。
- FastAPI 服务：提供运行接口、工具列表接口、配置脱敏查看接口和 Trace 查询接口。

## 代码组织

`app/api/` 提供 HTTP 与 SSE 接口，`app/core/` 承载 Runtime 通用能力，
`app/models/` 定义稳定契约，`app/tools_builtin/` 保存内置工具；Profile、Workflow 和 Prompt
位于 `config/`。具体模块由源码和测试自动发现，架构职责以
[系统架构与核心链路](../agent-doc/架构设计/系统架构与核心链路.md)为准。

## 本地运行

```shell
uv sync --extra dev
python main.py
uvicorn server:app --host 0.0.0.0 --port 8010 --reload
```

也可以使用脚本启动：

```shell
./scripts/run_dev.sh
```

## Docker 部署

```shell
docker build -t job_buddy_runtime:1.0.0 .
docker run --name job_buddy_runtime -p 8010:8010 -d job_buddy_runtime:1.0.0
```

模型服务统一通过 [config/config.yaml](config/config.yaml) 声明配置结构，连接地址、密钥等敏感值通过环境变量注入；如需在容器中挂载外部配置文件：

```shell
docker run --name job_buddy_runtime \
  -p 8010:8010 \
  -v $(pwd)/config/config.yaml:/app/config/config.yaml \
  -d job_buddy_runtime:1.0.0
```

## API 示例

```shell
curl -X POST http://localhost:8010/v1/agent/runs \
  -H 'Content-Type: application/json' \
  -d '{"messages":[{"role":"user","content":"请回显 hello runtime"}]}'
```

查看已注册工具：

```shell
curl http://localhost:8010/v1/runtime/tools
```

通过 Runtime 代理调用 agent-tool 中 Boss 按需工具的非 Boss 访问类状态操作：

```shell
curl -X POST http://localhost:8010/v1/runtime/tools/boss_browser/invoke \
  -H 'Content-Type: application/json' \
  -d '{"arguments":{"operation":"rate","payload":{}}}'
```

查看脱敏后的运行时配置：

```shell
curl http://localhost:8010/v1/runtime/config
```

查看 Trace 事件：

```shell
curl 'http://localhost:8010/v1/runtime/trace-events?run_id=run_xxx'
```

## 配置

模型、预算、工具、权限、安全、检查点和观测配置统一维护在
[config/config.yaml](config/config.yaml)，支持 `${ENV_NAME:default}` 占位符；本地完整栈使用根目录
[.env.example](../.env.example) 作为键集合模板。README 不复制配置字段和默认值，避免与可执行配置漂移。
真实 API Key、数据库密码或中间件密码不得提交。

默认读取 `config/config.yaml`。如需指定其他配置文件路径，可设置：

```shell
export JOB_BUDDY_CONFIG=/path/to/config.yaml
```

运行中可通过接口重新加载配置文件，适合挂载配置更新后热切换：

```shell
curl -X POST 'http://localhost:8010/v1/runtime/config/reload'
curl -X POST 'http://localhost:8010/v1/runtime/config/reload?config_path=/path/to/config.yaml'
```
