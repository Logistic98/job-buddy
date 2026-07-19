from typing import Any, Dict

from app.core.tool.base import BaseTool, ToolExecutionContext


class EchoTool(BaseTool):
    name = "echo"
    aliases = ["print_text"]
    search_hint = "回显文本 测试 连通性"
    description = "回显输入内容，用于连通性测试和无外部依赖的基础响应。"
    input_schema = {
        "type": "object",
        "properties": {"text": {"type": "string", "description": "需要回显的文本"}},
        "required": ["text"],
    }
    tags = ["test", "basic"]
    read_only = True
    always_load = True

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        return {"text": arguments.get("text", "")}
