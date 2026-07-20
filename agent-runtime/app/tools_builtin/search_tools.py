import fnmatch
from pathlib import Path
from typing import Any, Dict

from app.core.security.workspace import is_within_workspace
from app.core.tool.base import BaseTool, ToolExecutionContext


class GlobTool(BaseTool):
    name = "glob"
    aliases = ["find_files"]
    search_hint = "按 glob 查找 文件"
    description = "在工作区内按 glob 模式查找文件。"
    input_schema = {
        "type": "object",
        "properties": {
            "pattern": {"type": "string", "description": "glob 模式，例如 **/*.py"},
            "limit": {"type": "integer", "description": "最大返回数量"},
        },
        "required": ["pattern"],
    }
    tags = ["file", "search"]
    read_only = True

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        root = Path(context.workspace_dir).expanduser().resolve()
        pattern = arguments["pattern"]
        limit = int(arguments.get("limit") or 200)
        rows = []
        for path in root.glob(pattern):
            if path.is_file():
                rows.append(str(path.relative_to(root)))
            if len(rows) >= limit:
                break
        return {"pattern": pattern, "files": rows, "count": len(rows)}


class GrepTool(BaseTool):
    name = "grep"
    aliases = ["search_text"]
    search_hint = "搜索 文本 内容"
    description = "在工作区文件中搜索文本，返回匹配文件和行号。"
    input_schema = {
        "type": "object",
        "properties": {
            "pattern": {"type": "string", "description": "搜索文本，当前按普通子串匹配"},
            "glob": {"type": "string", "description": "文件过滤 glob，例如 **/*.py"},
            "limit": {"type": "integer", "description": "最大返回数量"},
        },
        "required": ["pattern"],
    }
    tags = ["file", "search", "grep"]
    read_only = True
    max_result_size_chars = 24000

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        root = Path(context.workspace_dir).expanduser().resolve()
        needle = arguments["pattern"]
        glob_pattern = arguments.get("glob") or "**/*"
        limit = int(arguments.get("limit") or 100)
        matches = []
        for path in root.rglob("*"):
            try:
                relative_path = path.relative_to(root)
                resolved = path.resolve()
            except (OSError, ValueError):
                continue
            if not is_within_workspace(resolved, root) or not resolved.is_file():
                continue
            if not fnmatch.fnmatch(str(relative_path), glob_pattern):
                continue
            try:
                lines = resolved.read_text(encoding="utf-8", errors="ignore").splitlines()
            except (OSError, UnicodeError):
                continue
            for idx, line in enumerate(lines, start=1):
                if needle in line:
                    matches.append({"path": str(relative_path), "line": idx, "text": line[:500]})
                    if len(matches) >= limit:
                        return {"pattern": needle, "matches": matches, "count": len(matches)}
        return {"pattern": needle, "matches": matches, "count": len(matches)}
