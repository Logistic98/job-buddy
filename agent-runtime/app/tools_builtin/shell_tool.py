import asyncio
import os
import shlex
from pathlib import Path
from typing import Any, Dict

import httpx

from app.core.common.constants import ToolKind, ToolRiskLevel
from app.core.common.settings import settings
from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult

_SENSITIVE_DENY_READ = ["~/.ssh", "~/.aws", "~/.config/gcloud", "~/.kube"]


class ShellTool(BaseTool):
    name = "shell_exec"
    aliases = ["bash", "shell"]
    search_hint = "执行 shell 命令 诊断"
    description = "执行受控 Shell 命令。高风险工具，默认需要权限确认；建议只用于只读诊断命令。"
    kind = ToolKind.CLI
    risk_level = ToolRiskLevel.HIGH
    timeout_seconds = 20
    input_schema = {
        "type": "object",
        "properties": {
            "command": {"type": "string", "description": "需要执行的 shell 命令"},
            "cwd": {"type": "string", "description": "执行目录"},
        },
        "required": ["command"],
    }
    tags = ["cli", "shell", "diagnostic"]
    read_only = False
    destructive = True
    concurrency_safe = False

    async def validate_input(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> ValidationResult:
        base = await super().validate_input(arguments, context)
        if not base.result:
            return base
        command = str(arguments.get("command") or "").strip()
        for pattern in settings.config.tool_runtime.shell_deny_patterns:
            if pattern and pattern in command:
                return ValidationResult(result=False, message=f"命令命中禁止规则: {pattern}", error_code=403)
        allow_prefixes = settings.config.tool_runtime.shell_allow_prefixes
        if allow_prefixes and not any(
            command == prefix or command.startswith(f"{prefix} ") for prefix in allow_prefixes
        ):
            return ValidationResult(result=False, message="命令不在 shell_allow_prefixes 允许范围内", error_code=403)
        cwd = self._resolve_cwd(arguments.get("cwd"), context)
        workspace = Path(context.workspace_dir).expanduser().resolve()
        if not self._is_within_workspace(cwd, workspace):
            return ValidationResult(result=False, message=f"执行目录超出工作区: {cwd}", error_code=403)
        return ValidationResult(result=True)

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        command = arguments["command"]
        cwd = self._resolve_cwd(arguments.get("cwd"), context)
        if settings.config.tool_runtime.shell_sandbox_enabled:
            return await self._run_in_sandbox(command, cwd)
        return await self._run_on_host(command, cwd)

    async def _run_in_sandbox(self, command: str, cwd: Path) -> Dict[str, Any]:
        config = settings.config.tool_runtime
        base_url = config.shell_sandbox_base_url.rstrip("/")
        timeout = float(config.shell_sandbox_timeout_seconds)
        payload = {
            "command": f"cd {shlex.quote(str(cwd))} && {command}",
            "policy": {
                "network": {"allowedDomains": []},
                "filesystem": {
                    "allowRead": [str(cwd)],
                    "denyRead": _SENSITIVE_DENY_READ,
                    "allowWrite": [],
                    "denyWrite": [".env", "secrets/"],
                },
            },
            "options": {"timeout": float(self.timeout_seconds), "check": False},
        }
        headers = {}
        token = os.getenv("AGENT_INTERNAL_SERVICE_TOKEN", "").strip()
        if token:
            headers["X-Internal-Service-Token"] = token
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                response = await client.post(f"{base_url}/v1/shell", json=payload, headers=headers)
                response.raise_for_status()
                body = response.json()
        except httpx.TimeoutException as exc:
            raise RuntimeError(f"agent-sandbox 执行超时（{base_url}，{timeout}s），命令未在宿主机回退执行") from exc
        except (httpx.HTTPError, ValueError) as exc:
            raise RuntimeError(
                f"agent-sandbox 不可用（{base_url}）：{exc}。"
                "请确认 agent-sandbox 服务已启动，或检查 JOB_BUDDY_SANDBOX_BASE_URL 配置；"
                "沙箱不可用时命令不会在宿主机回退执行"
            ) from exc
        if not isinstance(body, dict):
            raise RuntimeError("agent-sandbox 返回结构非法，命令未在宿主机回退执行")
        return {
            "exit_code": body.get("returncode"),
            "stdout": str(body.get("stdout") or "")[-12000:],
            "stderr": str(body.get("stderr") or "")[-12000:],
            "sandboxed": True,
        }

    async def _run_on_host(self, command: str, cwd: Path) -> Dict[str, Any]:
        # 仅限本地调试：shell_sandbox_enabled=false 时的宿主机直执路径。
        proc = await asyncio.create_subprocess_shell(
            command,
            cwd=str(cwd),
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=self.timeout_seconds)
        return {
            "exit_code": proc.returncode,
            "stdout": stdout.decode("utf-8", errors="ignore")[-12000:],
            "stderr": stderr.decode("utf-8", errors="ignore")[-12000:],
            "sandboxed": False,
        }

    def _resolve_cwd(self, raw_cwd: Any, context: ToolExecutionContext) -> Path:
        workspace = Path(context.workspace_dir).expanduser().resolve()
        if raw_cwd is None or str(raw_cwd).strip() == "":
            return workspace
        path = Path(str(raw_cwd)).expanduser()
        if not path.is_absolute():
            path = workspace / path
        return path.resolve()

    def _is_within_workspace(self, path: Path, workspace: Path) -> bool:
        try:
            path.relative_to(workspace)
            return True
        except ValueError:
            return False
