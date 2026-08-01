"""兼容 SiliconFlow 协议的记忆候选重排序客户端。

Rerank 只调整已经通过权限、TTL 和混合召回过滤的候选顺序。服务未配置、响应
异常或调用失败时返回 None，由调用方保留本地 RRF 排序，避免外部模型故障阻断
长期记忆召回。
"""

from __future__ import annotations

import math
import os

import httpx
from loguru import logger

_PLACEHOLDER_API_KEYS = {"sk-xxx", "sk-example"}


def _env_flag(name: str) -> bool:
    return os.getenv(name, "").strip().lower() in {"1", "true", "yes", "on"}


def _configured_api_key(name: str) -> bool:
    value = os.getenv(name, "").strip()
    return bool(value) and value.lower() not in _PLACEHOLDER_API_KEYS


class RerankClient:
    @property
    def enabled(self) -> bool:
        return (
            _env_flag("AGENT_MEMORY_RERANK_ENABLED")
            and bool(os.getenv("AGENT_MEMORY_RERANK_BASE_URL", "").strip())
            and bool(os.getenv("AGENT_MEMORY_RERANK_MODEL", "").strip())
            and _configured_api_key("AGENT_MEMORY_RERANK_API_KEY")
        )

    async def rerank(self, query: str, documents: list[str], *, top_n: int) -> list[int] | None:
        """返回按相关性降序排列的输入下标，失败时由调用方保留原排序。"""
        if not self.enabled or not query.strip() or not documents:
            return None
        bounded_top_n = max(1, min(top_n, len(documents)))
        base_url = os.getenv("AGENT_MEMORY_RERANK_BASE_URL", "").strip()
        api_key = os.getenv("AGENT_MEMORY_RERANK_API_KEY", "").strip()
        model = os.getenv("AGENT_MEMORY_RERANK_MODEL", "").strip()
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        payload = {
            "model": model,
            "query": query,
            "documents": documents,
            "top_n": bounded_top_n,
            "return_documents": False,
        }
        try:
            timeout = float(os.getenv("AGENT_MEMORY_RERANK_TIMEOUT_SECONDS", "5"))
            if not math.isfinite(timeout) or timeout <= 0:
                raise ValueError("AGENT_MEMORY_RERANK_TIMEOUT_SECONDS 必须是正数")
            async with httpx.AsyncClient(timeout=timeout) as client:
                response = await client.post(base_url, json=payload, headers=headers)
                response.raise_for_status()
                return self._parse_indices(response.json(), len(documents), bounded_top_n)
        except Exception as exc:
            logger.warning("rerank 调用失败，检索保留本地 RRF 排序: error={}", exc)
            return None

    @staticmethod
    def _parse_indices(payload: object, document_count: int, expected_count: int) -> list[int] | None:
        if not isinstance(payload, dict) or not isinstance(payload.get("results"), list):
            logger.warning("rerank 响应缺少 results 数组")
            return None
        parsed: list[tuple[float, int]] = []
        seen: set[int] = set()
        for result in payload["results"]:
            if not isinstance(result, dict):
                return None
            index = result.get("index")
            score = result.get("relevance_score")
            if isinstance(index, bool) or not isinstance(index, int) or index < 0 or index >= document_count:
                logger.warning("rerank 响应包含越界下标: index={}", index)
                return None
            if index in seen:
                logger.warning("rerank 响应包含重复下标: index={}", index)
                return None
            try:
                numeric_score = float(score)
            except (TypeError, ValueError):
                return None
            if not math.isfinite(numeric_score):
                return None
            seen.add(index)
            parsed.append((numeric_score, index))
        if len(parsed) != expected_count:
            logger.warning("rerank 响应条数异常: expected={}, got={}", expected_count, len(parsed))
            return None
        parsed.sort(key=lambda item: item[0], reverse=True)
        return [index for _, index in parsed]
