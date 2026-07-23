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
        self._sources: Dict[str, set[str]] = {}

    def register(self, tool: BaseTool, source: str | None = None):
        if not tool.name:
            raise ValueError("工具名称不能为空")
        if tool.name in self._tools:
            raise ValueError(f"工具重复注册: {tool.name}")
        self._tools[tool.name] = tool
        for alias in tool.aliases:
            if alias in self._aliases and self._aliases[alias] != tool.name:
                raise ValueError(f"工具别名冲突: {alias}")
            self._aliases[alias] = tool.name
        if source:
            self._sources.setdefault(source, set()).add(tool.name)
        logger.info(f"工具注册成功：tool={tool.name}, kind={tool.kind}")

    def unregister(self, name: str):
        tool = self.get(name)
        if not tool:
            return
        self._tools.pop(tool.name, None)
        for alias in list(self._aliases.keys()):
            if self._aliases[alias] == tool.name:
                self._aliases.pop(alias, None)
        for source in list(self._sources):
            self._sources[source].discard(tool.name)
            if not self._sources[source]:
                self._sources.pop(source, None)

    def replace_source(self, source: str, tools: List[BaseTool]) -> None:
        old_names = set(self._sources.get(source, set()))
        staged_names: set[str] = set()
        staged_aliases: set[str] = set()
        for tool in tools:
            if not tool.name or tool.name in staged_names:
                raise ValueError(f"工具来源 {source} 包含空名称或重复名称: {tool.name}")
            if tool.name in self._tools and tool.name not in old_names:
                raise ValueError(f"工具重复注册: {tool.name}")
            staged_names.add(tool.name)
            for alias in tool.aliases:
                existing = self._aliases.get(alias)
                if alias in staged_aliases or (existing and existing not in old_names):
                    raise ValueError(f"工具别名冲突: {alias}")
                staged_aliases.add(alias)

        for name in list(old_names):
            self.unregister(name)
        for tool in tools:
            self.register(tool, source=source)

    def unregister_source(self, source: str) -> None:
        for name in list(self._sources.get(source, set())):
            self.unregister(name)

    def source_ids(self, prefix: str = "") -> List[str]:
        return sorted(source for source in self._sources if source.startswith(prefix))

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
