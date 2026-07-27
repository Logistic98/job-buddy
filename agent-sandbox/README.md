# agent-sandbox

基于 [anthropic-experimental/sandbox-runtime](https://github.com/anthropic-experimental/sandbox-runtime) 的 Python SDK 封装，用于在 Python 项目中通过 `srt` CLI 对任意命令、CLI 工具、脚本和代码片段进行沙箱执行。

## 依赖安装

`sandbox-runtime` 目前以 npm 包发布，Python 侧通过 CLI 调用：

```bash
$ npm install -g @anthropic-ai/sandbox-runtime
```

Linux 还需要按上游项目说明安装 `bubblewrap`、`socat`、`ripgrep`；macOS 需要 `ripgrep`。

## 工程结构

```text
agent-sandbox/
├── app/
│   ├── core/              # 配置、模型、策略、异常、底层 srt runtime
│   ├── sdk/               # 面向业务方的高层 SandboxClient
│   └── server/            # FastAPI 服务接口
├── tests/                 # 单元测试，使用 fake srt，不依赖真实 srt 安装
├── server.py              # 服务态启动入口
├── Dockerfile             # Linux 运行镜像，内置 srt 及依赖
├── pyproject.toml
└── README.md
```

## SDK API

推荐使用高层入口 `SandboxClient`，底层 `SandboxRuntime` 仅作为高级扩展入口保留。

```python
from app import SandboxClient, SandboxPolicies, CodeSpec

client = SandboxClient(
    SandboxPolicies.workspace_readonly("/path/to/workspace"),
    cwd="/path/to/workspace",
    default_timeout=60,
)

# 任意 argv 命令
result = client.command(["python", "-c", "print('hello sandbox')"])

# 任意 CLI 工具
result = client.cli("pytest", ["-q"])

# shell 命令
result = client.shell("cat README.md | head -n 5")

# Python 代码片段
result = client.python_code("print('hello from python code')")

# Python 脚本
result = client.python_script("scripts/job.py", args=["--debug"])

# 通用代码文件，支持任意解释器
result = client.code_file(CodeSpec(
    code="console.log('hello node')",
    suffix=".js",
    interpreter="node",
))
```

核心封装对象：

- `SandboxClient`：生产推荐入口，提供命令、CLI、shell、脚本、代码文件执行能力。
- `CommandSpec` / `ExecutionOptions` / `CodeSpec`：标准请求模型。
- `SandboxPolicies`：常用沙箱策略工厂。
- `SandboxRuntime`：低层 `srt` CLI 适配器。

## 配置策略

封装中的配置类与上游 `~/.srt-settings.json` 结构保持一致：

- `NetworkConfig.allowedDomains`：网络白名单，空数组表示禁止网络访问。
- `FilesystemConfig.denyRead` / `allowRead`：读权限为 deny-then-allow。
- `FilesystemConfig.allowWrite` / `denyWrite`：写权限为 allow-only，再做 deny 例外。

默认推荐对不可信代码使用：

```python
from app import workspace_only_config

config = workspace_only_config("/path/to/workspace", allow_write=False)
```

该配置会拒绝读取用户目录下工作区以外的文件，同时默认禁止网络和写入。

## 服务启动

本项目只保留服务态入口，根目录启动文件为 `server.py`：

```bash
$ uv sync --extra dev
$ uv run python server.py
```

默认仅监听 `127.0.0.1:8061`，可通过环境变量调整。监听任何非回环地址（包括
Compose 容器内的 `0.0.0.0`）时必须配置 `AGENT_INTERNAL_SERVICE_TOKEN`，否则服务拒绝启动：

```bash
AGENT_INTERNAL_SERVICE_TOKEN=replace-with-a-random-token HOST=0.0.0.0 PORT=8061 uv run python server.py
```

`production` / `prod` 环境即使绑定回环地址也必须配置该令牌。配置后除 `/health` 外的接口都要求 `X-Internal-Service-Token`。

底层 Runtime 将 stdout/stderr 写入临时文件并按字节上限读取，避免不可信代码通过大输出耗尽内存；HTTP 层再按字符上限截断响应。两层限制分别配置：

```bash
AGENT_SANDBOX_MAX_CAPTURE_BYTES=1048576
AGENT_SANDBOX_MAX_OUTPUT_CHARS=200000
```

服务并发由 `AGENT_SANDBOX_MAX_CONCURRENCY` 控制，默认 4；超出时返回繁忙错误，不创建无界执行线程。

主要接口：

- `GET /health`
- `POST /v1/commands`
- `POST /v1/cli`
- `POST /v1/shell`
- `POST /v1/python/code`
- `POST /v1/code-file`

## Docker

构建镜像：

```bash
$ docker build -t job-buddy-sandbox:1.0.0 .
```

运行基础校验：

```bash
$ docker run --rm \
  -e AGENT_INTERNAL_SERVICE_TOKEN=replace-with-a-random-token \
  -p 127.0.0.1:8061:8061 \
  job-buddy-sandbox:1.0.0
```

生产镜像只包含运行依赖和服务源码，不复制测试目录，也不安装 `pytest`。单元测试应在源码目录使用下文命令执行；容器验证以镜像启动、`GET /health` 和受鉴权执行接口为准。

注意：上游 `sandbox-runtime` 在 Linux 下依赖 `bubblewrap`、`socat`、`ripgrep`，Dockerfile 已内置这些依赖。部分宿主机或容器运行时可能需要额外放开 user namespace 能力，具体限制以运行环境安全策略为准。

## 测试

```bash
$ uv run python -m pytest -q
```

测试用例中提供了 fake `srt` fixture，会模拟 `srt --settings <file> <command...>` 的调用方式，因此单元测试不依赖真实的 `@anthropic-ai/sandbox-runtime` 安装。真实沙箱能力仍需安装上游 `srt` 后在集成环境中验证。

测试覆盖 argv、字符串、CLI、Shell、Python 片段与脚本、临时代码文件、cwd/env 透传、非零退出码，以及输出截断和服务并发边界。
