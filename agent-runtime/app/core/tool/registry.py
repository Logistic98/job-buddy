from typing import Dict, List, Optional

from loguru import logger

from app.core.tool.base import BaseTool
from app.models.schemas import ToolDefinition


class ToolRegistry:
    """工具注册中心。

    参考 Claude Code 的工具池组装方式：按名称和别名索引、稳定排序、内置工具优先、禁用工具过滤。
    """

    def __init__(self):
        self._tools: Dict[str, BaseTool] = {}
        self._aliases: Dict[str, str] = {}

    def register(self, tool: BaseTool):
        if not tool.name:
            raise ValueError("工具名称不能为空")
        if tool.name in self._tools:
            raise ValueError(f"工具重复注册: {tool.name}")
        self._tools[tool.name] = tool
        for alias in tool.aliases:
            if alias in self._aliases and self._aliases[alias] != tool.name:
                raise ValueError(f"工具别名冲突: {alias}")
            self._aliases[alias] = tool.name
        logger.info(f"工具注册成功：tool={tool.name}, kind={tool.kind}")

    def unregister(self, name: str):
        tool = self.get(name)
        if not tool:
            return
        self._tools.pop(tool.name, None)
        for alias in list(self._aliases.keys()):
            if self._aliases[alias] == tool.name:
                self._aliases.pop(alias, None)

    def get(self, name: str) -> Optional[BaseTool]:
        primary_name = self._aliases.get(name, name)
        return self._tools.get(primary_name)

    def list_definitions(self, include_disabled: bool = False) -> List[ToolDefinition]:
        tools = [tool for tool in self._tools.values() if include_disabled or tool.is_enabled()]
        return [tool.definition() for tool in sorted(tools, key=lambda item: item.name)]

    def names(self) -> List[str]:
        return sorted(self._tools.keys())

    def has(self, name: str) -> bool:
        return self.get(name) is not None
