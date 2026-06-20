# job_buddy_runtime

基于 LangGraph 的智能体运行时核心实现，面向二期 ToolOps 工具体系设计。项目结构参考 `job-buddy-engine` 的 Python 工程风格，并吸收 `claude-code-fork-main` 中工具自描述、权限前置、工具注册、Agent Loop、检查点和可观测的核心实现思想。

## 核心能力

- Runtime 主链路：会话入口、目标理解、Planner、Tool Search、预算检查、工具执行、观察与结束判断。
- 工具体系：工具定义、工具注册中心、别名索引、工具检索、权限检查、统一 Tool Runtime；Boss 浏览器能力在 Runtime 中仅保留 `boss_browser` 代理工具，具体实现位于 agent-tool。
- LangGraph 编排：使用状态图组织目标理解、上下文收集、Tool Search、Planner、预算、执行、观察和结束判断。
- 检查点：每个关键阶段写入 JSON 检查点，支持中断恢复和审计追踪。
- OpenAI 兼容模型：默认接入 DeepSeek v4 Pro，统一从 YAML 读取模型服务配置，支持完整 chat/completions URL、重试、超时和工具 Schema。
- Prompt Cache：Planner 将稳定系统提示和稳定排序的候选工具目录放在动态上下文之前，适配 DeepSeek 服务端基于公共前缀的自动缓存。
- 权限安全：支持 allow/deny、只读工具、破坏性工具、高风险工具、Shell allow/deny 规则。
- 可观测：记录 run_start、plan_created、permission_check、tool_execute_end、finalize 等 Trace 事件。
- FastAPI 服务：提供运行接口、工具列表接口、配置脱敏查看接口和 Trace 查询接口。

## 目录结构

```text
project/
├── app/                    # 应用主包
│   ├── api/                # FastAPI 接口
│   ├── core/               # Runtime 核心实现
│   │   ├── agent/          # LangGraph Agent Loop 与执行器
│   │   ├── checkpoint/     # 检查点存储
│   │   ├── common/         # 配置、常量
│   │   ├── llm/            # OpenAI 兼容模型客户端
│   │   ├── observability/  # Trace 记录
│   │   ├── planner/        # Planner 实现
│   │   ├── tool/           # 工具基类、注册、检索、权限、运行时
│   │   └── utils/          # 通用工具
│   ├── models/             # Pydantic 数据模型
│   ├── tools_builtin/      # 内置工具
│   └── server.py           # FastAPI app 工厂
├── config/                 # YAML 配置文件
│   └── config.yaml         # 模型服务和运行时配置
├── tests/                  # 单元测试
├── scripts/                # 开发脚本
├── main.py                 # 函数调用示例入口
├── server.py               # Uvicorn 服务入口
├── Dockerfile              # 容器构建文件
└── pyproject.toml          # 项目依赖与构建配置
```

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
docker build -t job_buddy_runtime:latest .
docker run --name job_buddy_runtime -p 8010:8010 -d job_buddy_runtime:latest
```

模型服务统一通过 [config/config.yaml](config/config.yaml) 声明配置结构，连接地址、密钥等敏感值通过环境变量注入；如需在容器中挂载外部配置文件：

```shell
docker run --name job_buddy_runtime \
  -p 8010:8010 \
  -v $(pwd)/config/config.yaml:/app/config/config.yaml \
  -d job_buddy_runtime:latest
```

## API 示例

```shell
curl -X POST http://localhost:8010/v1/runtime/runs \
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

模型服务相关配置统一写在 [config/config.yaml](config/config.yaml)，但真实连接信息通过环境变量注入。配置文件支持 `${ENV_NAME:default}` 占位符，禁止提交真实 API Key、数据库密码或中间件密码：

```yaml
llm_service:
  base_url: "${JOB_BUDDY_LLM_BASE_URL:https://api.deepseek.com/chat/completions}"
  api_key: "${JOB_BUDDY_LLM_API_KEY:}"
  model_name: "${JOB_BUDDY_LLM_MODEL_NAME:deepseek-v4-pro}"
  timeout_seconds: "${JOB_BUDDY_LLM_TIMEOUT_SECONDS:60}"
  prompt_cache_enabled: "${JOB_BUDDY_LLM_PROMPT_CACHE_ENABLED:true}"
  prompt_cache_strategy: "${JOB_BUDDY_LLM_PROMPT_CACHE_STRATEGY:stable-prefix}"

tool_search:
  enabled: "${JOB_BUDDY_TOOL_SEARCH_ENABLED:true}"
  limit: "${JOB_BUDDY_TOOL_SEARCH_LIMIT:8}"
  fallback_limit: "${JOB_BUDDY_TOOL_SEARCH_FALLBACK_LIMIT:5}"
```

本地开发可复制根目录 [.env.example](../.env.example) 为 `.env` 后填写真实值，`.env` 已加入 `.gitignore`。

运行时、权限、工具执行、检查点和观测配置也在同一个 YAML 文件中维护。每个配置项都已在 [config/config.yaml](config/config.yaml) 中写明用途说明，核心结构如下：

```yaml
runtime:
  use_llm_planner: true
  max_turns: 12
  max_tool_calls: 20
  max_failures: 3

checkpoint:
  enabled: true
  dir: ".runtime_checkpoints"

permission:
  default_mode: "default"
  allow_high_risk_in_default: false
  deny_tools: []

tool_runtime:
  max_retries: 1
  shell_allow_prefixes: ["pwd", "ls", "cat"]
  shell_deny_patterns: ["rm -rf", "sudo"]
```

默认读取 `config/config.yaml`。如需指定其他配置文件路径，可设置：

```shell
export JOB_BUDDY_CONFIG=/path/to/config.yaml
```

运行中可通过接口重新加载配置文件，适合挂载配置更新后热切换：

```shell
curl -X POST 'http://localhost:8010/v1/runtime/config/reload'
curl -X POST 'http://localhost:8010/v1/runtime/config/reload?config_path=/path/to/config.yaml'
```

## 设计映射

- Claude Code `ToolDef` 思想：对应 `BaseTool.definition()`、`ToolDefinition`、`ToolRegistry`。
- Claude Code 权限钩子思想：对应 `PermissionService` 和 `ToolRuntime.execute()`。
- Claude Code `QueryEngine` 主循环思想：对应 `AgentGraphBuilder` 的 LangGraph 状态图。
- 二期文档 Runtime 主流程：目标理解、Tool Search、Planner、预算、权限、执行、观察、检查点、Trace 已落到核心链路。
