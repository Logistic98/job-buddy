import asyncio
import contextvars
import json
import os
from pathlib import Path
from typing import Any, Dict, List, Optional

from loguru import logger

from app.core.common.settings import settings
from app.core.observability.otel import OtelExporter
from app.core.security.redaction import redact_sensitive
from app.core.utils.time_utils import TimeUtils
from app.models.schemas import TraceEvent

_trace_context: contextvars.ContextVar[Optional[Dict[str, str]]] = contextvars.ContextVar("trace_context", default=None)


def bind_trace_context(**fields: Optional[str]) -> contextvars.Token:
    if not fields:
        return _trace_context.set(_trace_context.get())
    source = _trace_context.get() or {}
    merged = {**source}
    for key, value in fields.items():
        if value is None or value == "":
            continue
        merged[key] = value
    return _trace_context.set(merged)


def unbind_trace_context(token: contextvars.Token):
    _trace_context.reset(token)


def current_trace_context() -> Dict[str, str]:
    return dict(_trace_context.get() or {})


class TraceRecorder:
    """Trace 记录器：内存窗口供实时查询，JSONL 落盘支撑回放与评估取证。"""

    def __init__(self, persist_dir: Optional[str] = None, otel_exporter: Optional[OtelExporter] = None):
        self.events: List[TraceEvent] = []
        self._persist_dir = persist_dir
        self._otel = otel_exporter or OtelExporter()

    async def record(
        self,
        trace_id: str,
        event: str,
        payload: Dict[str, Any] = None,
        run_id: Optional[str] = None,
        session_id: Optional[str] = None,
        request_id: Optional[str] = None,
        node_id: Optional[str] = None,
        stage: Optional[str] = None,
        component: Optional[str] = None,
        actor: Optional[str] = None,
        status: str = "success",
        span_id: Optional[str] = None,
        error: Optional[str] = None,
        duration_ms: Optional[int] = None,
    ):
        if not settings.config.observability.enabled:
            return
        ambient = current_trace_context()
        request_id = request_id or ambient.get("request_id")
        session_id = session_id or ambient.get("session_id")
        user_id = ambient.get("user_id")
        actor = actor or ambient.get("actor")
        component = component or ambient.get("component") or "agent-runtime"
        status = status or "success"
        span_id = span_id or ambient.get("span_id")
        request_path = ambient.get("request_path")

        item = TraceEvent(
            trace_id=trace_id,
            run_id=run_id or ambient.get("run_id"),
            event=event,
            timestamp=TimeUtils.get_formatted_time(),
            payload=redact_sensitive(payload or {}),
            request_id=request_id,
            session_id=session_id,
            user_id=user_id,
            environment=ambient.get("environment"),
            request_path=request_path,
            node_id=node_id,
            stage=stage,
            component=component,
            actor=actor,
            status=status,
            span_id=span_id,
            error=redact_sensitive(error),
            duration_ms=duration_ms,
        )
        self.events.append(item)
        max_events = settings.config.observability.max_events
        if max_events > 0 and len(self.events) > max_events:
            self.events = self.events[-max_events:]
        # 落盘是阻塞文件 IO；放到线程池执行，避免在流式问答期间反复卡住事件循环、
        # 拖慢逐字下发。record 仍被顺序 await，单个 run 内的事件写入顺序保持不变。
        await asyncio.to_thread(self._persist, item)
        self._otel.submit(item)
        if settings.config.observability.log_events:
            logger.info(
                f"Trace 事件：trace_id={trace_id}, run_id={run_id}, event={event}, status={status}, node_id={node_id or '-'}"
            )

    def list_by_run(self, run_id: str) -> List[TraceEvent]:
        in_memory = [event for event in self.events if event.run_id == run_id]
        # 内存窗口是所有并发 run 共享的滚动窗口，高并发下早期事件可能被 max_events 截断，
        # 导致某个 run 在内存里只剩部分事件。落盘文件按 run 追加、内容完整，因此当它不少于
        # 内存结果时以落盘为准，避免返回被并发挤掉的残缺 trace。
        persisted = self.load_persisted(run_id)
        if len(persisted) >= len(in_memory):
            return persisted
        return in_memory

    def load_persisted(self, run_id: str) -> List[TraceEvent]:
        path = self._run_path(run_id)
        if path is None or not path.exists():
            return []
        events: List[TraceEvent] = []
        try:
            for line in path.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line:
                    continue
                events.append(TraceEvent(**json.loads(line)))
        except Exception as e:
            logger.warning(f"Trace 回放失败：run_id={run_id}, error={e}")
        return events

    def _persist(self, item: TraceEvent):
        if not settings.config.observability.persist_enabled:
            return
        path = self._run_path(item.run_id or item.trace_id)
        if path is None:
            return
        try:
            path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            os.chmod(path.parent, 0o700)
            descriptor = os.open(path, os.O_APPEND | os.O_CREAT | os.O_WRONLY, 0o600)
            os.fchmod(descriptor, 0o600)
            with os.fdopen(descriptor, "a", encoding="utf-8") as f:
                f.write(json.dumps(item.model_dump(), ensure_ascii=False) + "\n")
        except Exception as e:
            logger.warning(f"Trace 落盘失败：run_id={item.run_id}, error={e}")

    def _run_path(self, run_id: Optional[str]) -> Optional[Path]:
        if not run_id:
            return None
        base = Path(self._persist_dir or settings.config.observability.persist_dir)
        safe_run_id = "".join(ch if ch.isalnum() or ch in "-_." else "_" for ch in run_id)
        return base / f"{safe_run_id}.jsonl"
