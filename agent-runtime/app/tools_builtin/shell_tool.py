
import asyncio
from typing import Any, Dict

from app.core.common.constants import ToolKind, ToolRiskLevel
from app.core.common.settings import settings
from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult


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
        if allow_prefixes and not any(command == prefix or command.startswith(f"{prefix} ") for prefix in allow_prefixes):
            return ValidationResult(result=False, message="命令不在 shell_allow_prefixes 允许范围内", error_code=403)
        return ValidationResult(result=True)

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        command = arguments["command"]
        cwd = arguments.get("cwd") or context.workspace_dir
        proc = await asyncio.create_subprocess_shell(
            command,
            cwd=cwd,
            stdout=asyncio.subprocess.PIPE,
            stderr=asyncio.subprocess.PIPE,
        )
        stdout, stderr = await asyncio.wait_for(proc.communicate(), timeout=self.timeout_seconds)
        return {
            "exit_code": proc.returncode,
            "stdout": stdout.decode("utf-8", errors="ignore")[-12000:],
            "stderr": stderr.decode("utf-8", errors="ignore")[-12000:],
        }
