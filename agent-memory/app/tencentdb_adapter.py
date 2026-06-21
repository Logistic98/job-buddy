import os
import time
from typing import Any, Dict, List, Optional

import httpx


class TencentDBMemoryAdapter:
    """TencentDB Agent Memory Gateway 适配器。

    通过 /health、/capture、/recall 薄适配，保留本项目 /v1/memories API。
    """

    def __init__(self, base_url: Optional[str] = None, api_key: Optional[str] = None):
        configured_base_url = base_url if base_url is not None else os.getenv("TDAI_MEMORY_GATEWAY_URL", "")
        self.base_url = configured_base_url.strip().rstrip("/")
        self.enabled = bool(self.base_url)
        self.cooldown_seconds = float(os.getenv("TDAI_MEMORY_GATEWAY_COOLDOWN_SECONDS", "60"))
        self._disabled_until = 0.0
        raw_api_key = api_key if api_key is not None else os.getenv("TDAI_GATEWAY_API_KEY", "")
        self.api_key = self._normalize_api_key(raw_api_key)

    @staticmethod
    def _normalize_api_key(api_key: Optional[str]) -> str:
        """返回可用 Gateway API Key，占位值视为未启用鉴权。"""
        if api_key is None:
            return ""
        value = api_key.strip()
        if value.lower() in {"", "change-me", "changeme", "none", "null"}:
            return ""
        return value

    def _headers(self) -> Dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self.api_key:
            headers["Authorization"] = f"Bearer {self.api_key}"
        return headers

    def is_available(self) -> bool:
        return self.enabled and time.monotonic() >= self._disabled_until

    def _ensure_available(self) -> None:
        if not self.enabled:
            raise RuntimeError("TencentDB Agent Memory Gateway 未配置")
        if not self.is_available():
            raise RuntimeError("TencentDB Agent Memory Gateway 暂不可用")

    def _mark_unavailable(self) -> None:
        self._disabled_until = time.monotonic() + self.cooldown_seconds

    async def health(self) -> Dict[str, Any]:
        self._ensure_available()
        async with httpx.AsyncClient(timeout=3, trust_env=False) as client:
            try:
                resp = await client.get(f"{self.base_url}/health", headers=self._headers())
                resp.raise_for_status()
            except Exception:
                self._mark_unavailable()
                raise
            self._disabled_until = 0.0
            return {"status": "UP", "gateway": self.base_url, "raw": resp.text[:200]}

    async def capture(self, session_key: str, content: str, role: str = "user") -> Dict[str, Any]:
        self._ensure_available()
        payload = {"session_key": session_key, "role": role, "content": content}
        async with httpx.AsyncClient(timeout=3, trust_env=False) as client:
            try:
                resp = await client.post(f"{self.base_url}/capture", json=payload, headers=self._headers())
                resp.raise_for_status()
            except Exception:
                self._mark_unavailable()
                raise
            return resp.json() if resp.headers.get("content-type", "").startswith("application/json") else {"text": resp.text}

    async def recall(self, query: str, session_key: str, max_results: int = 5) -> List[Dict[str, Any]]:
        self._ensure_available()
        payload = {"query": query, "session_key": session_key, "max_results": max_results}
        async with httpx.AsyncClient(timeout=3, trust_env=False) as client:
            try:
                resp = await client.post(f"{self.base_url}/recall", json=payload, headers=self._headers())
                resp.raise_for_status()
            except Exception:
                self._mark_unavailable()
                raise
            data = resp.json()
        if isinstance(data, list):
            return data
        if isinstance(data, dict):
            for key in ("memories", "data", "results", "items"):
                value = data.get(key)
                if isinstance(value, list):
                    return value
            return [data]
        return []
