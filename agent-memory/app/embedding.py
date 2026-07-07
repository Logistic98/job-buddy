"""OpenAI 兼容协议的 Embedding 客户端。

为混合检索提供向量一路的语义信号：默认关闭，开启后调用外部 Embedding 服务，
失败静默降级返回 None，由调用方退化为词法 + 时间两路融合。内容向量按哈希做
进程内 FIFO 缓存，避免候选池内同一记忆反复 embedding。
"""

from __future__ import annotations

import hashlib
import os
from collections import OrderedDict

import httpx
from loguru import logger

_CACHE_MAX_ENTRIES = 2048


def _env_flag(name: str) -> bool:
    return os.getenv(name, "").strip().lower() in {"1", "true", "yes", "on"}


def vector_min_similarity() -> float:
    return float(os.getenv("AGENT_MEMORY_VECTOR_MIN_SIMILARITY", "0.3"))


class EmbeddingClient:
    def __init__(self):
        self._cache: OrderedDict[str, list[float]] = OrderedDict()

    @property
    def enabled(self) -> bool:
        return _env_flag("AGENT_MEMORY_EMBEDDING_ENABLED") and bool(os.getenv("AGENT_MEMORY_EMBEDDING_BASE_URL", "").strip())

    async def embed(self, texts: list[str]) -> list[list[float]] | None:
        """批量向量化文本，任一环节失败返回 None，由调用方降级。"""
        if not self.enabled or not texts:
            return None
        missing = [text for text in texts if self._cache_key(text) not in self._cache]
        if missing:
            fetched = await self._fetch(missing)
            if fetched is None:
                return None
            for text, vector in zip(missing, fetched):
                self._cache_put(self._cache_key(text), vector)
        try:
            return [self._cache[self._cache_key(text)] for text in texts]
        except KeyError:
            return None

    async def _fetch(self, texts: list[str]) -> list[list[float]] | None:
        base_url = os.getenv("AGENT_MEMORY_EMBEDDING_BASE_URL", "").strip()
        api_key = os.getenv("AGENT_MEMORY_EMBEDDING_API_KEY", "").strip()
        model = os.getenv("AGENT_MEMORY_EMBEDDING_MODEL", "").strip()
        timeout = float(os.getenv("AGENT_MEMORY_EMBEDDING_TIMEOUT_SECONDS", "5"))
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                response = await client.post(base_url, json={"model": model, "input": texts}, headers=headers)
                response.raise_for_status()
                data = response.json().get("data") or []
                vectors = [item.get("embedding") for item in sorted(data, key=lambda entry: entry.get("index", 0))]
                if len(vectors) != len(texts) or any(not isinstance(vector, list) for vector in vectors):
                    logger.warning("embedding 响应条数或结构异常: expected={}, got={}", len(texts), len(vectors))
                    return None
                return vectors
        except Exception as exc:
            logger.warning("embedding 调用失败，检索降级为词法+时间融合: error={}", exc)
            return None

    @staticmethod
    def _cache_key(text: str) -> str:
        return hashlib.sha1(text.encode("utf-8")).hexdigest()

    def _cache_put(self, key: str, vector: list[float]) -> None:
        self._cache[key] = vector
        self._cache.move_to_end(key)
        while len(self._cache) > _CACHE_MAX_ENTRIES:
            self._cache.popitem(last=False)
