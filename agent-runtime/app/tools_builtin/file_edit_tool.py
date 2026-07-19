from pathlib import Path
from typing import Any, Dict

from app.core.common.constants import ToolRiskLevel
from app.core.security.workspace import resolve_workspace_path
from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult


class FileEditTool(BaseTool):
    name = "file_edit"
    aliases = ["edit_file", "replace_text"]
    search_hint = "替换 编辑 文件 文本"
    description = "在工作区内对文本文件执行精确字符串替换。要求 old_text 在文件中唯一匹配。"
    risk_level = ToolRiskLevel.HIGH
    read_only = False
    destructive = True
    concurrency_safe = False
    input_schema = {
        "type": "object",
        "properties": {
            "path": {"type": "string", "description": "文件路径"},
            "old_text": {"type": "string", "description": "待替换文本，必须唯一匹配"},
            "new_text": {"type": "string", "description": "替换后的文本"},
        },
        "required": ["path", "old_text", "new_text"],
    }
    tags = ["file", "edit"]

    async def validate_input(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> ValidationResult:
        base = await super().validate_input(arguments, context)
        if not base.result:
            return base
        try:
            path = self._resolve_path(arguments["path"], context)
        except ValueError as exc:
            return ValidationResult(result=False, message=str(exc), error_code=403)
        if not path.exists():
            return ValidationResult(result=False, message=f"文件不存在: {path}", error_code=404)
        content = path.read_text(encoding="utf-8", errors="ignore")
        count = content.count(arguments["old_text"])
        if count != 1:
            return ValidationResult(result=False, message=f"old_text 匹配次数必须为 1，实际为 {count}", error_code=409)
        return ValidationResult(result=True)

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        path = self._resolve_path(arguments["path"], context)
        content = path.read_text(encoding="utf-8", errors="ignore")
        updated = content.replace(arguments["old_text"], arguments["new_text"], 1)
        path.write_text(updated, encoding="utf-8")
        return {"path": str(path), "replacements": 1, "bytes": path.stat().st_size}

    def _resolve_path(self, raw_path: str, context: ToolExecutionContext) -> Path:
        return resolve_workspace_path(raw_path, context.workspace_dir)
