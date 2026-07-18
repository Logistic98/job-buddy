from __future__ import annotations

import re
from functools import lru_cache
from pathlib import Path
from typing import Any, Dict

from app.core.common.settings import settings


class PromptTemplateLoader:
    """Prompt Runtime。

    Prompt 是行为代码，统一从 config/prompts 加载；动态变量只在末尾渲染，稳定前缀由调用方控制。
    """

    def __init__(self, prompts_dir: str | None = None):
        self.prompts_dir = Path(prompts_dir or settings.prompts_dir)

    @lru_cache(maxsize=128)
    def load(self, relative_path: str, fallback: str = "") -> str:
        path = self.prompts_dir / relative_path
        if path.exists() and path.is_file():
            return path.read_text(encoding="utf-8").strip()
        return fallback.strip()

    def render(self, template: str, variables: Dict[str, Any]) -> str:
        def replace(match: re.Match[str]) -> str:
            key = match.group(1).strip()
            value = variables.get(key, "")
            return str(value)

        return re.sub(r"\{\{\s*([A-Za-z0-9_.-]+)\s*\}\}", replace, template)
