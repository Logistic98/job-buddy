"""agent-memory HTTP 客户端。

按配置开启（memory.enabled，默认关闭）。检索失败一律静默降级返回空列表,
只记录 warning 日志，不阻塞 Agent 主链路。
"""

from __future__ import annotations

import os
from typing import Any, Dict, List, Optional

import httpx
from loguru import logger

from app.core.common.settings import settings
from app.core.tool.injection_probe import probe_text

_MAX_CONTENT_CHARS = 300


class MemoryClient:
    """调用 agent-memory 的记忆检索客户端。

    配置在每次调用时读取，保证测试与运行期的 reload_settings 生效。
    """

    @property
    def enabled(self) -> bool:
        return bool(settings.config.memory.enabled)

    def search(
        self,
        query: str,
        scope: Optional[str] = None,
        trace_id: Optional[str] = None,
        tenant_id: Optional[str] = None,
        operator_id: Optional[str] = None,
    ) -> List[Dict[str, Any]]:
        config = settings.config.memory
        if not config.enabled:
            return []
        query = (query or "").strip()
        if not query:
            return []
        params = {"q": query, "scope": scope or config.default_scope}
        headers = {
            "X-Tenant-Id": (tenant_id or "default-tenant").strip(),
            "X-Operator-Id": (operator_id or "anonymous").strip(),
        }
        token = os.getenv("AGENT_INTERNAL_SERVICE_TOKEN", "").strip()
        if token:
            headers["X-Internal-Service-Token"] = token
        url = f"{config.base_url.rstrip('/')}/v1/memories/search"
        try:
            response = httpx.get(url, params=params, headers=headers, timeout=config.timeout_seconds)
            response.raise_for_status()
            body = response.json()
        except Exception as e:
            logger.warning(f"memory_search 降级: error={e}, base_url={config.base_url}, trace_id={trace_id}")
            return []
        items = body.get("data") if isinstance(body, dict) else None
        if not isinstance(items, list):
            return []
        return [self._normalize(item) for item in items[: config.top_k] if isinstance(item, dict)]

    def _normalize(self, item: Dict[str, Any]) -> Dict[str, Any]:
        content = str(item.get("content") or "")[:_MAX_CONTENT_CHARS]
        injection_hits = probe_text(content)
        return {
            "source": "agent-memory",
            "id": item.get("id"),
            "scope": item.get("scope"),
            "content": "" if injection_hits else content,
            "created_at": item.get("created_at"),
            "untrusted": bool(injection_hits),
            "injection_hits": injection_hits,
        }
