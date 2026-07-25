"""对高风险工具调用执行失败关闭的 Transcript 授权复核。"""

from __future__ import annotations

import asyncio
import os
from typing import Any, Iterable

import httpx
from pydantic import BaseModel, Field

from app.core.common.settings import settings
from app.models.schemas import ToolCall

APPROVE = "approve"
REQUIRE_CONFIRMATION = "require_human_confirmation"
DENY = "deny"
_VALID_DECISIONS = {APPROVE, REQUIRE_CONFIRMATION, DENY}


class TranscriptReviewDecision(BaseModel):
    decision: str
    risk: str = "high"
    matched_rules: list[str] = Field(default_factory=list)


class TranscriptReviewClient:
    """高风险工具调用的独立终审客户端。"""

    async def review(self, messages: Iterable[Any], call: ToolCall) -> TranscriptReviewDecision:
        config = settings.config.transcript_review
        # 终审仅查看用户原话与拟执行调用，剥离助手解释以降低提示注入影响。
        payload = {
            "messages": [
                {"role": "user", "content": str(self._message_value(message, "content") or "")}
                for message in messages or []
                if str(self._message_value(message, "role") or "") == "user"
            ],
            "tool_calls": [{"name": call.name, "arguments": call.arguments}],
        }
        endpoint = config.base_url.rstrip("/") + "/v1/intent/review-transcript"
        headers = {}
        internal_token = os.getenv("AGENT_INTERNAL_SERVICE_TOKEN", "").strip()
        if internal_token:
            headers["X-Internal-Service-Token"] = internal_token
        last_error: Exception | None = None
        # 只重试临时网络和服务端故障；协议异常按失败关闭处理。
        for attempt in range(config.max_retries + 1):
            if attempt:
                await asyncio.sleep(config.retry_backoff_seconds * (2 ** (attempt - 1)))
            try:
                async with httpx.AsyncClient(timeout=config.timeout_seconds, trust_env=False) as client:
                    response = await client.post(endpoint, json=payload, headers=headers)
                    if response.status_code >= 500 or response.status_code == 429:
                        response.raise_for_status()
                    if response.status_code >= 400:
                        raise RuntimeError(f"transcript reviewer rejected request with HTTP {response.status_code}")
                    body = response.json()
                if not isinstance(body, dict) or int(body.get("code") or 0) != 200:
                    raise RuntimeError("transcript reviewer returned a non-success envelope")
                data = body.get("data")
                if not isinstance(data, dict):
                    raise RuntimeError("transcript reviewer returned invalid data")
                result = TranscriptReviewDecision.model_validate(data)
                if result.decision not in _VALID_DECISIONS:
                    raise RuntimeError("transcript reviewer returned an unknown decision")
                return result
            except (httpx.TimeoutException, httpx.ConnectError, httpx.HTTPStatusError) as exc:
                last_error = exc
                if (
                    isinstance(exc, httpx.HTTPStatusError)
                    and exc.response.status_code < 500
                    and exc.response.status_code != 429
                ):
                    break
            except (TypeError, ValueError, RuntimeError) as exc:
                last_error = exc
                break
        raise RuntimeError("high-risk transcript review is unavailable") from last_error

    def _message_value(self, message: Any, key: str) -> Any:
        if isinstance(message, dict):
            return message.get(key)
        return getattr(message, key, None)
