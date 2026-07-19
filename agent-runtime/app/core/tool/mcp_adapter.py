"""MCP 工具适配器。

将 MCP 工具按 BaseTool 协议接入 ToolRegistry。
"""

import asyncio
from typing import Any, Dict, List

from loguru import logger

from app.core.common.constants import ToolKind, ToolRiskLevel
from app.core.common.settings import McpConfig
from app.core.tool.base import BaseTool, ToolExecutionContext
from app.core.tool.mcp_client import McpClient, McpProtocolError
from app.core.tool.registry import ToolRegistry


class McpToolAdapter(BaseTool):
    """单个 MCP 工具的本地代理。"""

    kind = ToolKind.MCP
    risk_level = ToolRiskLevel.MEDIUM
    read_only = False
    destructive = False
    concurrency_safe = False

    def __init__(
        self,
        client: McpClient,
        remote_tool_name: str,
        display_name: str,
        description: str,
        input_schema: Dict[str, Any],
        tool_tag: str = "",
        timeout_seconds: int = 30,
    ):
        self._client = client
        self._remote_tool_name = remote_tool_name
        self.name = display_name
        self.description = description or f"MCP 工具: {remote_tool_name}"
        self.input_schema = input_schema or {"type": "object", "properties": {}}
        self.timeout_seconds = timeout_seconds
        self.tags = [tag for tag in ["mcp", tool_tag] if tag]
        self.search_hint = f"MCP {client.server_id} {remote_tool_name} {tool_tag}".strip()

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        try:
            result = await self._client.call_tool(self._remote_tool_name, arguments)
        except McpProtocolError as e:
            raise RuntimeError(f"MCP 调用协议异常: {e}")

        if result.get("is_error"):
            text = result.get("text") or ""
            structured = result.get("structured") or {}
            message = structured.get("message") if isinstance(structured, dict) else None
            raise RuntimeError(message or text or "MCP 工具返回错误")

        if result.get("structured") is not None:
            return result["structured"]
        if result.get("text") is not None:
            return {"text": result["text"]}
        return result.get("raw")


async def register_mcp_tools(registry: ToolRegistry, mcp_config: McpConfig) -> List[str]:
    """根据配置批量注册 MCP 工具。

    返回成功注册的工具名列表。任何单个服务连接失败都不阻塞其他服务的注册。
    """

    if not mcp_config or not mcp_config.enabled:
        logger.info("MCP 接入未启用，跳过注册")
        return []

    registered: List[str] = []
    for server_id, server_cfg in mcp_config.servers.items():
        if not server_cfg.enabled:
            logger.info(f"MCP 服务已禁用，跳过注册：server={server_id}")
            continue

        client = McpClient(server_id, server_cfg)
        connect_timeout = float(mcp_config.connect_timeout_seconds or 8)
        try:
            tool_defs = await asyncio.wait_for(client.list_tools(), timeout=connect_timeout)
        except Exception as e:
            logger.warning(f"MCP 服务连接失败，跳过注册：server={server_id}, url={server_cfg.url}, error={e}")
            continue

        prefix = server_cfg.name_prefix or ""
        for tool_def in tool_defs:
            remote_name = tool_def["name"]
            display_name = f"{prefix}{remote_name}"
            if registry.has(display_name):
                logger.warning(f"MCP 工具冲突，跳过注册：name={display_name}, server={server_id}")
                continue
            adapter = McpToolAdapter(
                client=client,
                remote_tool_name=remote_name,
                display_name=display_name,
                description=tool_def.get("description", ""),
                input_schema=tool_def.get("input_schema") or {"type": "object", "properties": {}},
                tool_tag=server_cfg.tool_tag,
                timeout_seconds=server_cfg.timeout_seconds,
            )
            registry.register(adapter)
            registered.append(display_name)

        logger.info(f"MCP 工具注册完成：server={server_id}, tools={len(tool_defs)}, registered={len(registered)}")

    return registered
