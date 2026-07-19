"""MCP 客户端。

仅支持 streamable-http，按调用创建短连接 Session。
"""

import json
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional

from loguru import logger

from app.core.common.settings import McpServerConfig


class McpProtocolError(RuntimeError):
    """MCP 协议层异常,用于和业务异常区分。"""


class McpClient:
    """MCP streamable-http 客户端薄封装。"""

    def __init__(self, server_id: str, config: McpServerConfig):
        self.server_id = server_id
        self.config = config

    @asynccontextmanager
    async def _session(self):
        if self.config.transport != "streamable_http":
            raise McpProtocolError(f"暂不支持的 MCP 传输类型: {self.config.transport}")
        if not self.config.url:
            raise McpProtocolError(f"MCP 服务 {self.server_id} 未配置 url")

        try:
            from mcp import ClientSession
            from mcp.client.streamable_http import streamablehttp_client
        except ImportError as e:
            raise McpProtocolError(f"未安装 mcp Python SDK: {e}")

        headers = dict(self.config.headers or {})
        async with streamablehttp_client(self.config.url, headers=headers) as (read_stream, write_stream, _):
            async with ClientSession(read_stream, write_stream) as session:
                await session.initialize()
                yield session

    async def list_tools(self) -> List[Dict[str, Any]]:
        async with self._session() as session:
            response = await session.list_tools()
            return [self._dump_tool(item) for item in response.tools]

    async def call_tool(self, tool_name: str, arguments: Dict[str, Any]) -> Dict[str, Any]:
        async with self._session() as session:
            result = await session.call_tool(tool_name, arguments=arguments or {})
            return self._dump_call_result(result)

    def _dump_tool(self, tool: Any) -> Dict[str, Any]:
        return {
            "name": getattr(tool, "name", ""),
            "description": getattr(tool, "description", "") or "",
            "input_schema": getattr(tool, "inputSchema", None) or {"type": "object", "properties": {}},
        }

    def _dump_call_result(self, result: Any) -> Dict[str, Any]:
        """将 MCP CallToolResult 归一化为 dict。"""

        is_error = bool(getattr(result, "isError", False))
        structured = getattr(result, "structuredContent", None)
        if structured is not None:
            return {"is_error": is_error, "structured": structured, "text": None}

        contents = getattr(result, "content", []) or []
        text_chunks: List[str] = []
        raw_items: List[Dict[str, Any]] = []
        for item in contents:
            item_type = getattr(item, "type", None)
            if item_type == "text":
                text_chunks.append(getattr(item, "text", "") or "")
            else:
                try:
                    raw_items.append(item.model_dump() if hasattr(item, "model_dump") else dict(item))
                except Exception:
                    raw_items.append({"type": item_type, "repr": str(item)})

        text = "\n".join(chunk for chunk in text_chunks if chunk)
        parsed = self._try_parse_json(text) if text else None
        return {
            "is_error": is_error,
            "structured": parsed,
            "text": text or None,
            "raw": raw_items or None,
        }

    @staticmethod
    def _try_parse_json(text: str) -> Optional[Any]:
        stripped = text.strip()
        if not stripped:
            return None
        if stripped[0] not in "{[":
            return None
        try:
            return json.loads(stripped)
        except json.JSONDecodeError:
            return None

    async def probe(self, timeout_seconds: float) -> bool:
        """启动期快速探测 MCP 服务可用性。"""

        import asyncio

        try:
            await asyncio.wait_for(self.list_tools(), timeout=timeout_seconds)
            return True
        except Exception as e:
            logger.warning(f"MCP 服务探测失败：server={self.server_id}, error={e}")
            return False
