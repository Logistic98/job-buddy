from pathlib import Path
from typing import Any, Dict

from app.core.common.constants import ToolRiskLevel
from app.core.security.workspace import resolve_workspace_path
from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult


class FileReadTool(BaseTool):
    name = "file_read"
    aliases = ["read", "view_file"]
    search_hint = "读取 文本 文件 内容"
    description = "读取工作区内文本文件内容，支持 offset 和 limit 控制输出大小。"
    risk_level = ToolRiskLevel.LOW
    input_schema = {
        "type": "object",
        "properties": {
            "path": {"type": "string", "description": "文件路径"},
            "offset": {"type": "integer", "description": "起始行号，从1开始"},
            "limit": {"type": "integer", "description": "最多读取行数"},
        },
        "required": ["path"],
    }
    tags = ["file", "read", "context"]
    read_only = True
    max_result_size_chars = 24000

    async def validate_input(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> ValidationResult:
        base = await super().validate_input(arguments, context)
        if not base.result:
            return base
        try:
            self._resolve_path(arguments["path"], context)
        except ValueError as exc:
            return ValidationResult(result=False, message=str(exc), error_code=403)
        return ValidationResult(result=True)

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        path = self._resolve_path(arguments["path"], context)
        if not path.exists() or not path.is_file():
            raise ValueError(f"文件不存在: {path}")
        offset = max(int(arguments.get("offset") or 1), 1)
        limit = int(arguments.get("limit") or 200)
        lines = path.read_text(encoding="utf-8", errors="ignore").splitlines()
        selected = lines[offset - 1 : offset - 1 + limit]
        return {
            "path": str(path),
            "content": "\n".join(selected),
            "total_lines": len(lines),
            "offset": offset,
            "limit": limit,
        }

    def _resolve_path(self, raw_path: str, context: ToolExecutionContext) -> Path:
        return resolve_workspace_path(raw_path, context.workspace_dir)
