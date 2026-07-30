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

底层 Runtime 将 stdout/stderr 写入临时文件，执行期间持续检查总输出字节数并在超限时终止整个进程组；进程完成后只读取有界内容，HTTP 层再按字符上限截断响应。三层限制分别配置：

```bash
AGENT_SANDBOX_MAX_CAPTURE_BYTES=1048576
AGENT_SANDBOX_MAX_PROCESS_OUTPUT_BYTES=16777216
AGENT_SANDBOX_MAX_OUTPUT_CHARS=200000
```

服务并发由 `AGENT_SANDBOX_MAX_CONCURRENCY` 控制，默认 4；超出时返回繁忙错误，不创建无界执行线程。

主要接口：

- `GET /health`
- `GET /ready`，使用真实 srt 最小执行验证运行时就绪状态并短时缓存结果
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
  --init \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --security-opt seccomp=unconfined \
  --security-opt apparmor=unconfined \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=268435456 \
  --pids-limit 256 \
  --memory 1g \
  --cpus 1 \
  -e AGENT_INTERNAL_SERVICE_TOKEN=replace-with-a-random-token \
  -e AGENT_SANDBOX_ENABLE_WEAKER_NESTED_SANDBOX=true \
  -p 127.0.0.1:8061:8061 \
  job-buddy-sandbox:1.0.0
```

生产镜像只包含运行依赖和服务源码，不复制测试目录，也不安装 `pytest`。容器必须启用轻量 init 作为 PID 1，负责回收 `srt` 退出后被重新收养的 `socat`、`bwrap` 等孙进程，避免 zombie 持续占用 `pids_limit`。每次执行还会使用独立进程组，并在正常结束、异常或超时时统一终止残留进程树；SIGTERM 宽限时间由 `AGENT_SANDBOX_PROCESS_TERMINATION_GRACE_SECONDS` 配置，之后升级为 SIGKILL。服务仍以固定非 root 用户运行，镜像内提供 Python、Java 和 JavaScript 判题所需运行时。单元测试应在源码目录使用下文命令执行；容器验证以镜像启动、`GET /ready`、受鉴权的三语言执行接口和无 zombie/活进程泄漏为准。

macOS 原生启动时，`scripts/start.sh` 会在未显式配置 `JAVA_HOME` 时通过 `/usr/libexec/java_home` 发现本机 JDK。Sandbox 只把经 `bin/java` 验证的 JDK 根目录加入可信只读运行时路径，并向子进程透传非敏感的 `JAVA_HOME`；HTTP policy 不能借此放宽其他文件系统路径。没有安装 JDK 时，Python 与 JavaScript 仍可运行，但 Java 判题会明确失败。

上游 `sandbox-runtime` 在 Linux 下依赖 `bubblewrap`、`socat`、`ripgrep`，Dockerfile 已内置这些依赖。Docker 默认 seccomp 和 `docker-default` AppArmor profile 会阻止嵌套 namespace 与 mount propagation，因此 Compose 只对 Sandbox 容器使用 `seccomp=unconfined`、`apparmor=unconfined` 和上游的 weaker nested compatibility mode，避免 macOS 与 Linux 宿主机额外安装安全策略。该设置不关闭宿主机全局 AppArmor，也不影响其他容器；Sandbox 继续以非 root、丢弃全部 capability、`no-new-privileges`、只读根文件系统、受限临时目录和资源上限提供外层隔离。不得单独开启兼容模式，也不得改用 `privileged`、添加 `CAP_SYS_ADMIN` 或挂载 Docker socket。

真实容器冒烟验证：

```bash
$ ./.agent-harness/scripts/smoke_sandbox_container.sh
```

## 测试

```bash
$ uv run python -m pytest -q
```

测试用例中提供了 fake `srt` fixture，会模拟 `srt --settings <file> <command...>` 的调用方式，因此单元测试不依赖真实的 `@anthropic-ai/sandbox-runtime` 安装。真实沙箱能力仍需安装上游 `srt` 后在集成环境中验证。

测试覆盖 argv、字符串、CLI、Shell、Python 片段与脚本、临时代码文件、cwd/env 透传、非零退出码，以及输出截断和服务并发边界。
