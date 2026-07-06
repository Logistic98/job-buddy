
from __future__ import annotations

import asyncio
import hashlib
import json
import os
import time
from typing import Any, Dict

import httpx
from loguru import logger

from app.core.common.settings import settings
from app.models.schemas import TraceEvent

MAX_PAYLOAD_CHARS = 8000


class OtelExporter:
    """OTLP/HTTP JSON 导出器。

    不引入 OTel SDK，用 httpx 直接构造 OTLP JSON 载荷：Runtime 的 Trace 是点事件，
    每条事件映射为零时长 Span，同一 trace_id 哈希为同一条 trace。导出是 fire-and-forget
    旁路，失败仅记 warning，权威取证数据仍在 JSONL 落盘。
    """

    @property
    def enabled(self) -> bool:
        return bool(settings.config.observability.otel_enabled)

    def submit(self, item: TraceEvent):
        if not self.enabled:
            return
        try:
            asyncio.get_running_loop().create_task(self.export(item))
        except RuntimeError:
            # 无事件循环（同步调用场景）时放弃导出，不影响主流程。
            logger.warning(f"OTel 导出跳过：无运行中的事件循环, run_id={item.run_id}")

    async def export(self, item: TraceEvent):
        config = settings.config.observability
        try:
            async with httpx.AsyncClient(timeout=config.otel_timeout_seconds) as client:
                response = await client.post(config.otel_endpoint, json=self.build_payload(item))
                response.raise_for_status()
        except Exception as e:
            logger.warning(f"OTel 导出失败：run_id={item.run_id}, event={item.event}, error={e}")

    def build_payload(self, item: TraceEvent) -> Dict[str, Any]:
        now_nano = str(time.time_ns())
        payload_text = json.dumps(item.payload or {}, ensure_ascii=False, default=str)[:MAX_PAYLOAD_CHARS]
        span = {
            "traceId": self._trace_hex(item.trace_id),
            "spanId": os.urandom(8).hex(),
            "name": item.event,
            "kind": 1,
            "startTimeUnixNano": now_nano,
            "endTimeUnixNano": now_nano,
            "attributes": [
                {"key": "trace_id", "value": {"stringValue": item.trace_id}},
                {"key": "run_id", "value": {"stringValue": item.run_id or ""}},
                {"key": "payload", "value": {"stringValue": payload_text}},
            ],
        }
        return {
            "resourceSpans": [
                {
                    "resource": {
                        "attributes": [
                            {
                                "key": "service.name",
                                "value": {"stringValue": settings.config.observability.otel_service_name},
                            }
                        ]
                    },
                    "scopeSpans": [{"scope": {"name": "job-buddy-runtime.trace"}, "spans": [span]}],
                }
            ]
        }

    def _trace_hex(self, trace_id: str) -> str:
        return hashlib.md5(str(trace_id).encode("utf-8")).hexdigest()
