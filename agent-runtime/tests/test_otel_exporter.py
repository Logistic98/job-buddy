
import asyncio
import json
from unittest.mock import patch

from app.core.observability.otel import MAX_PAYLOAD_CHARS, OtelExporter
from app.core.observability.trace import TraceRecorder
from app.models.schemas import TraceEvent


def _event(event="planner_decision", payload=None):
    return TraceEvent(
        trace_id="trace-abc",
        run_id="run-001",
        event=event,
        timestamp="2026-07-05 12:00:00",
        payload=payload or {"step": "s1"},
    )


def test_disabled_by_default_makes_no_network_call():
    exporter = OtelExporter()
    assert exporter.enabled is False
    with patch("app.core.observability.otel.httpx.AsyncClient") as client:
        exporter.submit(_event())
        client.assert_not_called()


def test_build_payload_structure():
    exporter = OtelExporter()
    payload = exporter.build_payload(_event(payload={"目标": "筛选岗位"}))
    resource_span = payload["resourceSpans"][0]
    resource_attrs = {item["key"]: item["value"]["stringValue"] for item in resource_span["resource"]["attributes"]}
    assert resource_attrs["service.name"] == "job-buddy-runtime"
    span = resource_span["scopeSpans"][0]["spans"][0]
    assert span["name"] == "planner_decision"
    assert len(span["traceId"]) == 32
    assert len(span["spanId"]) == 16
    assert span["startTimeUnixNano"] == span["endTimeUnixNano"]
    attrs = {item["key"]: item["value"]["stringValue"] for item in span["attributes"]}
    assert attrs["trace_id"] == "trace-abc"
    assert attrs["run_id"] == "run-001"
    assert json.loads(attrs["payload"]) == {"目标": "筛选岗位"}


def test_same_trace_id_maps_to_stable_trace_hex():
    exporter = OtelExporter()
    first = exporter.build_payload(_event(event="a"))
    second = exporter.build_payload(_event(event="b"))
    span_a = first["resourceSpans"][0]["scopeSpans"][0]["spans"][0]
    span_b = second["resourceSpans"][0]["scopeSpans"][0]["spans"][0]
    assert span_a["traceId"] == span_b["traceId"]
    assert span_a["spanId"] != span_b["spanId"]


def test_long_payload_is_truncated():
    exporter = OtelExporter()
    payload = exporter.build_payload(_event(payload={"text": "x" * (MAX_PAYLOAD_CHARS * 2)}))
    span = payload["resourceSpans"][0]["scopeSpans"][0]["spans"][0]
    attrs = {item["key"]: item["value"]["stringValue"] for item in span["attributes"]}
    assert len(attrs["payload"]) == MAX_PAYLOAD_CHARS


async def test_export_failure_is_swallowed():
    exporter = OtelExporter()
    with patch("app.core.observability.otel.httpx.AsyncClient", side_effect=RuntimeError("collector down")):
        await exporter.export(_event())


async def test_record_submits_to_otel_when_enabled(tmp_path, monkeypatch):
    from app.core.common.settings import get_settings

    monkeypatch.setattr(get_settings().config.observability, "otel_enabled", True)
    recorder = TraceRecorder(persist_dir=str(tmp_path))
    with patch.object(OtelExporter, "export", autospec=True) as export:
        await recorder.record("trace-abc", "run_start", {"k": "v"}, run_id="run-001")
        await asyncio.sleep(0)
        assert export.call_count == 1
        exported = export.call_args.args[1]
        assert exported.event == "run_start"
    # JSONL 落盘行为不受 OTel 旁路影响
    persisted = recorder.load_persisted("run-001")
    assert len(persisted) == 1 and persisted[0].event == "run_start"
