from pathlib import Path
from typing import Any, Dict

from app.core.common.constants import ToolRiskLevel
from app.core.security.workspace import resolve_workspace_path
from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult


class FileWriteTool(BaseTool):
    name = "file_write"
    aliases = ["write_file"]
    search_hint = "写入 创建 覆盖 文件"
    description = "在工作区内创建或覆盖文本文件。写操作默认高风险，需要权限策略允许。"
    risk_level = ToolRiskLevel.HIGH
    read_only = False
    destructive = True
    concurrency_safe = False
    input_schema = {
        "type": "object",
        "properties": {
            "path": {"type": "string", "description": "文件路径"},
            "content": {"type": "string", "description": "文件内容"},
            "overwrite": {"type": "boolean", "description": "文件存在时是否覆盖"},
        },
        "required": ["path", "content"],
    }
    tags = ["file", "write"]

    async def validate_input(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> ValidationResult:
        base = await super().validate_input(arguments, context)
        if not base.result:
            return base
        try:
            path = self._resolve_path(arguments["path"], context)
        except ValueError as exc:
            return ValidationResult(result=False, message=str(exc), error_code=403)
        if path.exists() and not bool(arguments.get("overwrite", False)):
            return ValidationResult(result=False, message="文件已存在，overwrite=false", error_code=409)
        return ValidationResult(result=True)

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        path = self._resolve_path(arguments["path"], context)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(arguments["content"], encoding="utf-8")
        return {"path": str(path), "bytes": path.stat().st_size}

    def _resolve_path(self, raw_path: str, context: ToolExecutionContext) -> Path:
        return resolve_workspace_path(raw_path, context.workspace_dir)
