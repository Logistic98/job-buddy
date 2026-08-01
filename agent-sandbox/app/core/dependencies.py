"""一次性 Python 依赖声明的安全校验。"""

from __future__ import annotations

import re
from collections.abc import Sequence

MAX_PYTHON_DEPENDENCIES = 8
MAX_PYTHON_DEPENDENCY_CHARS = 128

# 只接受 PyPI 分发名和可选的精确版本。URL、VCS、本地路径、extras、marker 与 CLI
# 选项均不进入依赖安装 argv，避免调用方扩大网络和文件系统边界。
_PYTHON_DEPENDENCY_PATTERN = re.compile(r"^[A-Za-z0-9][A-Za-z0-9._-]{0,127}(?:==[A-Za-z0-9][A-Za-z0-9.!+_-]{0,63})?$")


def normalize_python_dependencies(values: Sequence[str] | None) -> list[str]:
    """校验并返回稳定的 Python 依赖列表。"""

    dependencies = list(values or [])
    if len(dependencies) > MAX_PYTHON_DEPENDENCIES:
        raise ValueError(f"dependencies 最多允许 {MAX_PYTHON_DEPENDENCIES} 项")

    normalized: list[str] = []
    seen: set[str] = set()
    for value in dependencies:
        if not isinstance(value, str):
            raise ValueError("Python 依赖必须是字符串")
        dependency = value.strip()
        if len(dependency) > MAX_PYTHON_DEPENDENCY_CHARS or not _PYTHON_DEPENDENCY_PATTERN.fullmatch(dependency):
            raise ValueError("Python 依赖格式仅支持 PyPI 包名或 package==version")
        identity = dependency.split("==", 1)[0].lower().replace("_", "-").replace(".", "-")
        if identity in seen:
            raise ValueError(f"Python 依赖重复: {dependency}")
        seen.add(identity)
        normalized.append(dependency)
    return normalized
