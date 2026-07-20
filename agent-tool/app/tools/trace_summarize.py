"""core_trace_summarize：把核心链路 Trace 事件列表压缩为可展示摘要。

纯函数实现,输入为 runtime /trace-events 返回的事件数组,
不依赖任何外部服务。
"""

from collections import Counter
from typing import Any, Dict

from ..models import ToolError, ToolResult


def run_trace_summarize(arguments: Dict[str, Any], trace_id: str = None) -> ToolResult:
    events = arguments.get("events")
    if not isinstance(events, list) or not events:
        return ToolResult(
            status="error",
            summary="缺少有效的 events 参数",
            trace_id=trace_id,
            error=ToolError(
                code="invalid_arguments",
                message="arguments.events 必须是非空数组",
                retryable=False,
                suggested_action="传入 runtime GET /v1/runtime/trace-events 返回的事件数组",
            ),
        )

    event_names = [str(item.get("event", "unknown")) for item in events]
    counter = Counter(event_names)
    run_ids = sorted({str(item.get("run_id")) for item in events if item.get("run_id")})
    errors = [item for item in events if "error" in (item.get("payload") or {})]
    timestamps = [item.get("timestamp") for item in events if item.get("timestamp")]

    data = {
        "total_events": len(events),
        "event_counts": dict(counter),
        "run_ids": run_ids,
        "error_count": len(errors),
        "errors": [{"event": item.get("event"), "error": (item.get("payload") or {}).get("error")} for item in errors],
        "started_at": min(timestamps) if timestamps else None,
        "ended_at": max(timestamps) if timestamps else None,
    }
    summary = f"共 {len(events)} 个事件,覆盖 {len(run_ids)} 个 run,错误 {len(errors)} 个"
    warnings = ["Trace 中包含错误事件,建议人工查看"] if errors else []
    return ToolResult(status="success", summary=summary, data=data, warnings=warnings, trace_id=trace_id)
