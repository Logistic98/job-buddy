"""MCP 客户端。

仅支持 streamable-http，按调用创建短连接 Session。
"""

import json
from contextlib import asynccontextmanager
from typing import Any, Dict, List, Optional

from loguru import logger

from app.core.common.settings import McpConfig, McpServerConfig
from app.core.tool.resource_budget import (
    ResourceBudget,
    ResourceBudgetExceeded,
    enforce_resource_budget,
)


class McpProtocolError(RuntimeError):
    """MCP 协议层异常,用于和业务异常区分。"""


class McpClient:
    """MCP streamable-http 客户端薄封装。"""

    def __init__(self, server_id: str, config: McpServerConfig, limits: McpConfig):
        self.server_id = server_id
        self.config = config
        self.limits = limits

    @asynccontextmanager
    async def _session(self):
        if self.config.transport != "streamable_http":
            raise McpProtocolError(f"暂不支持的 MCP 传输类型: {self.config.transport}")
        if not self.config.url:
            raise McpProtocolError(f"MCP 服务 {self.server_id} 未配置 url")

        try:
            from mcp import ClientSession
            from mcp.client.streamable_http import streamablehttp_client
        except ImportError as exc:
            raise McpProtocolError(f"未安装 mcp Python SDK: {exc}") from exc

        headers = dict(self.config.headers or {})
        async with streamablehttp_client(self.config.url, headers=headers) as (read_stream, write_stream, _):
            async with ClientSession(read_stream, write_stream) as session:
                await session.initialize()
                yield session

    async def list_tools(self) -> List[Dict[str, Any]]:
        async with self._session() as session:
            response = await session.list_tools()
            tools = response.tools or []
            if len(tools) > self.limits.max_tools_per_server:
                raise McpProtocolError(f"MCP 服务 {self.server_id} 工具数量超过 {self.limits.max_tools_per_server}")
            dumped: List[Dict[str, Any]] = []
            catalog_bytes = 0
            for item in tools:
                tool = self._dump_tool(item)
                catalog_bytes += self._validate_tool_definition(tool)
                if catalog_bytes > self.limits.max_catalog_bytes:
                    raise McpProtocolError(f"MCP 服务 {self.server_id} 目录超过 {self.limits.max_catalog_bytes} 字节")
                dumped.append(tool)
            return dumped

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

    def _validate_tool_definition(self, tool: Dict[str, Any]) -> int:
        name = str(tool.get("name") or "")
        description = str(tool.get("description") or "")
        if not name or len(name) > self.limits.max_tool_name_chars:
            raise McpProtocolError(
                f"MCP 服务 {self.server_id} 工具名称为空或超过 {self.limits.max_tool_name_chars} 字符"
            )
        if len(description) > self.limits.max_tool_description_chars:
            raise McpProtocolError(f"MCP 工具 {name} 描述超过 {self.limits.max_tool_description_chars} 字符")
        try:
            schema_bytes = enforce_resource_budget(
                tool.get("input_schema"),
                ResourceBudget(
                    max_bytes=self.limits.max_schema_bytes,
                    max_nodes=self.limits.max_schema_nodes,
                    max_depth=self.limits.max_schema_depth,
                ),
                f"MCP 工具 {name} Schema",
            )
        except ResourceBudgetExceeded as exc:
            raise McpProtocolError(str(exc)) from exc
        return len(name.encode("utf-8")) + len(description.encode("utf-8")) + schema_bytes

    def _dump_call_result(self, result: Any) -> Dict[str, Any]:
        """将 MCP CallToolResult 归一化为 dict。"""

        is_error = bool(getattr(result, "isError", False))
        structured = getattr(result, "structuredContent", None)
        if structured is not None:
            self._validate_result_value(structured, "MCP structuredContent")
            return {"is_error": is_error, "structured": structured, "text": None}

        contents = getattr(result, "content", []) or []
        if len(contents) > self.limits.max_result_items:
            raise McpProtocolError(f"MCP 结果条目超过 {self.limits.max_result_items}")
        text_chunks: List[str] = []
        raw_items: List[Dict[str, Any]] = []
        result_bytes = 0
        for item in contents:
            item_type = getattr(item, "type", None)
            if item_type == "text":
                chunk = getattr(item, "text", "") or ""
                result_bytes += len(chunk.encode("utf-8"))
                if result_bytes > self.limits.max_result_bytes:
                    raise McpProtocolError(f"MCP 文本结果超过 {self.limits.max_result_bytes} 字节")
                text_chunks.append(chunk)
            else:
                try:
                    raw = item.model_dump() if hasattr(item, "model_dump") else dict(item)
                except Exception:
                    raw = {"type": item_type, "repr": str(item)}
                result_bytes += self._validate_result_value(raw, "MCP 非文本结果")
                if result_bytes > self.limits.max_result_bytes:
                    raise McpProtocolError(f"MCP 结果超过 {self.limits.max_result_bytes} 字节")
                raw_items.append(raw)

        text = "\n".join(chunk for chunk in text_chunks if chunk)
        parsed = self._try_parse_json(text) if text else None
        if parsed is not None:
            self._validate_result_value(parsed, "MCP JSON 结果")
        return {
            "is_error": is_error,
            "structured": parsed,
            "text": text or None,
            "raw": raw_items or None,
        }

    def _validate_result_value(self, value: Any, label: str) -> int:
        try:
            return enforce_resource_budget(
                value,
                ResourceBudget(
                    max_bytes=self.limits.max_result_bytes,
                    max_nodes=self.limits.max_result_nodes,
                    max_depth=self.limits.max_result_depth,
                ),
                label,
            )
        except ResourceBudgetExceeded as exc:
            raise McpProtocolError(str(exc)) from exc

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
