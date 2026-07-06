# Runtime Shell 工具沙箱化方案

## 为什么做

agent-runtime 内置工具 `shell_exec`（`app/tools_builtin/shell_tool.py`）此前通过 `asyncio.create_subprocess_shell` 直接在宿主机执行模型生成的 shell 命令，仅依赖 `shell_deny_patterns` 与 `shell_allow_prefixes` 两层字符串过滤做安全防护。这一实现违反了本仓库"CLI 与代码必须进沙箱"的设计原则：字符串过滤无法防御变形命令（如通过环境变量拼接、子 shell、编码绕过），一旦模型被注入或误判，宿主机文件系统与网络将直接暴露。仓库内 agent-sandbox 模块已经提供基于 `@anthropic-ai/sandbox-runtime`（srt）的沙箱执行服务，agent-tool 的 `sandbox_execute` 工具也已经验证了"通过 HTTP 调用 agent-sandbox 执行不可信命令"的可行路径，因此 runtime 的 shell 工具应当对齐该模式。

## 方案是什么

保留 `shell_exec` 现有的工具定义、入参 schema、白名单与工作区校验逻辑，将命令的实际执行从宿主机子进程替换为调用 agent-sandbox 的 `POST /v1/shell` 接口。字符串过滤降级为第一道快速拦截，沙箱成为真正的安全边界。沙箱不可用时直接返回明确错误，绝不回退到宿主机直接执行。

执行策略采用工作区只读模型：请求携带 `policy.filesystem.allowRead = [workspace_dir]`，不授予任何写路径，网络 allowlist 为空，敏感目录（`~/.ssh`、`~/.aws`、`~/.config/gcloud`、`~/.kube`）显式加入 denyRead。该策略与工具描述"建议只用于只读诊断命令"一致。

由于 agent-sandbox 的 `options.cwd` 仅允许系统临时目录，工作区目录切换通过命令前缀实现：最终下发命令为 `cd <shlex.quote(cwd)> && <command>`，其中 `cwd` 已在 `validate_input` 中被约束在工作区内。

## 具体怎么做

1. `app/core/common/settings.py` 的 `ToolRuntimeConfig` 新增三个字段：`shell_sandbox_enabled`（默认 true）、`shell_sandbox_base_url`（默认 `http://localhost:8061`）、`shell_sandbox_timeout_seconds`（默认 30）。
2. `config/config.yaml` 的 `tool_runtime` 段落新增对应配置项，`shell_sandbox_base_url` 通过 `${JOB_BUDDY_SANDBOX_BASE_URL:http://localhost:8061}` 环境变量注入，禁止硬编码部署地址。
3. `app/tools_builtin/shell_tool.py` 的 `_run` 改为：当 `shell_sandbox_enabled` 为 true 时，使用 httpx.AsyncClient 调用 `{base_url}/v1/shell`，请求体包含 `command`（带 cd 前缀）、`policy`（工作区只读）与 `options`（timeout、check=false）；连接失败、超时或非 2xx 响应时抛出带明确提示的 RuntimeError（提示检查 agent-sandbox 服务与 `JOB_BUDDY_SANDBOX_BASE_URL`）。当开关为 false 时保留原宿主机执行路径，仅用于本地开发调试，且在结果中附带 `sandboxed: false` 标记以便 Trace 审计。
4. 返回结构保持 `exit_code`、`stdout`、`stderr` 三字段兼容既有调用方与评估用例，stdout/stderr 维持 12000 字符截断，新增 `sandboxed` 布尔字段。
5. 测试改造：`tests/test_builtin_tools.py` 中放行路径的用例改为 monkeypatch httpx 模拟沙箱响应，不再依赖真实子进程；新增沙箱不可用时报错且不回退宿主机的用例；deny/allow 校验用例不受影响。

## 涉及模块与接口

agent-runtime（工具实现、配置模型、配置文件、测试）；agent-sandbox（仅作为下游依赖被调用，`POST /v1/shell` 接口不变更）。部署层面 runtime 新增对 agent-sandbox 服务的运行时依赖，`scripts/start-all.sh` 已默认启动 agent-sandbox（端口 8061），无需变更。

## 风险与注意事项

第一，sandbox 服务未启动时 `shell_exec` 将不可用，这是有意为之的失败关闭（fail-closed）设计；错误信息必须可定位，指明服务地址与环境变量名。第二，srt 在 macOS 与 Linux 的隔离机制不同（macOS 使用 sandbox-exec，Linux 依赖 bubblewrap），沙箱内命令的可用性可能存在平台差异，诊断类只读命令不受影响。第三，`cd` 前缀方案要求沙箱策略允许读取工作区路径，若未来工作区迁移到沙箱 denyRead 覆盖的目录会导致命令失败，属于策略正确拦截而非缺陷。

## 如何验证

`cd agent-runtime && uv run python -m pytest tests/test_builtin_tools.py tests/test_tool_runtime.py` 全部通过；`./.agent-harness/scripts/verify.sh agent-runtime --quick` 与 `gate.sh agent-runtime --quick` 全绿。有条件时启动 agent-sandbox 后手工验证 `pwd`、`ls` 类命令返回正常，`rm -rf` 类命令在 validate 层被拒绝。

## 后续演进

后续可将 policy 构造抽象为可配置项，支持按 profile 声明工具级沙箱策略；在权限模式与沙箱策略之间建立映射（只读模式对应 workspace_readonly，自动模式对应 workspace_readwrite）；并将 `file_write`、`file_edit` 等破坏性工具纳入同一沙箱治理框架。
