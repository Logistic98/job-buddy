"""在外部工具资源进入目录或上下文前执行结构预算。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import Any


class ResourceBudgetExceeded(ValueError):
    """不可信结构化内容超过预算前抛出的异常。"""


@dataclass(frozen=True)
class ResourceBudget:
    max_bytes: int
    max_nodes: int
    max_depth: int


def enforce_resource_budget(value: Any, budget: ResourceBudget, label: str) -> int:
    """递归校验结构深度、节点数和字节数，并返回总字节数。"""

    seen: set[int] = set()
    nodes = 0
    total_bytes = 0

    def visit(current: Any, depth: int) -> None:
        nonlocal nodes, total_bytes
        if depth > budget.max_depth:
            raise ResourceBudgetExceeded(f"{label} 嵌套深度超过 {budget.max_depth}")
        nodes += 1
        if nodes > budget.max_nodes:
            raise ResourceBudgetExceeded(f"{label} 节点数量超过 {budget.max_nodes}")

        # 容器按对象身份检测循环引用，标量统一累计 UTF-8 字节数。
        if current is None or isinstance(current, (bool, int, float)):
            total_bytes += len(str(current).encode("utf-8"))
        elif isinstance(current, str):
            total_bytes += len(current.encode("utf-8"))
        elif isinstance(current, dict):
            identity = id(current)
            if identity in seen:
                raise ResourceBudgetExceeded(f"{label} 包含循环引用")
            seen.add(identity)
            for key, item in current.items():
                visit(str(key), depth + 1)
                visit(item, depth + 1)
            seen.remove(identity)
        elif isinstance(current, (list, tuple, set)):
            identity = id(current)
            if identity in seen:
                raise ResourceBudgetExceeded(f"{label} 包含循环引用")
            seen.add(identity)
            for item in current:
                visit(item, depth + 1)
            seen.remove(identity)
        else:
            visit(str(current), depth + 1)

        if total_bytes > budget.max_bytes:
            raise ResourceBudgetExceeded(f"{label} 内容超过 {budget.max_bytes} 字节")

    visit(value, 0)
    return total_bytes
