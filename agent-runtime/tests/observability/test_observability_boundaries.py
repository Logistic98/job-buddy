from unittest.mock import AsyncMock, Mock

import httpx
import pytest

from app.core.common.settings import settings
from app.core.observability import trace as module
from app.core.observability.otel import OtelExporter
from app.models.schemas import TraceEvent


def event(**changes):
    return TraceEvent(trace_id="trace", event="test", timestamp="2026-01-01T00:00:00", **changes)


def test_trace_binding_without_fields_preserves_context():
    token = module.bind_trace_context(user_id="user")
    try:
        nested = module.bind_trace_context()
        assert module.current_trace_context()["user_id"] == "user"
        module.unbind_trace_context(nested)
        assert module.current_trace_context()["user_id"] == "user"
    finally:
        module.unbind_trace_context(token)


@pytest.mark.asyncio
async def test_disabled_recording_does_not_persist_or_export(tmp_path, monkeypatch):
    monkeypatch.setattr(settings.config.observability, "enabled", False)
    exporter = Mock()
    recorder = module.TraceRecorder(str(tmp_path), exporter)
    await recorder.record("trace", "event")
    assert recorder.events == []
    assert not list(tmp_path.iterdir())
    exporter.submit.assert_not_called()


def test_trace_replay_skips_blank_lines_preserves_valid_prefix_on_corruption(tmp_path):
    recorder = module.TraceRecorder(str(tmp_path))
    item = event(run_id="run")
    (tmp_path / "run.jsonl").write_text("\n" + item.model_dump_json() + "\n\ninvalid", encoding="utf-8")
    assert recorder.load_persisted("run") == [item]
    recorder.events = [event(run_id="memory")]
    assert recorder.list_by_run("memory") == recorder.events
    assert recorder.load_persisted("") == []


def test_trace_write_failures_do_not_break_execution(tmp_path, monkeypatch):
    recorder = module.TraceRecorder(str(tmp_path / "blocked"))
    monkeypatch.setattr(settings.config.observability, "persist_enabled", False)
    recorder._persist(event())
    assert not (tmp_path / "blocked").exists()
    monkeypatch.setattr(settings.config.observability, "persist_enabled", True)
    (tmp_path / "blocked").write_text("file", encoding="utf-8")
    recorder._persist(event(run_id="run"))
    assert (tmp_path / "blocked").read_text() == "file"
    recorder._persist(TraceEvent(trace_id="", event="test", timestamp="now"))


def test_otel_without_event_loop_is_best_effort(monkeypatch):
    monkeypatch.setattr(settings.config.observability, "otel_enabled", True)
    exporter = OtelExporter()
    exporter.submit(event())


@pytest.mark.asyncio
async def test_otel_http_failure_is_best_effort(monkeypatch):
    client = AsyncMock()
    client.__aenter__.return_value = client
    client.post.side_effect = httpx.ConnectError("offline")
    monkeypatch.setattr("app.core.observability.otel.httpx.AsyncClient", Mock(return_value=client))
    await OtelExporter().export(event())
    client.post.assert_awaited_once()


@pytest.mark.asyncio
async def test_otel_success_posts_trace_and_checks_http_status(monkeypatch):
    client = AsyncMock()
    client.__aenter__.return_value = client
    response = Mock()
    client.post.return_value = response
    monkeypatch.setattr("app.core.observability.otel.httpx.AsyncClient", Mock(return_value=client))
    await OtelExporter().export(event(run_id="run"))
    response.raise_for_status.assert_called_once()
    payload = client.post.call_args.kwargs["json"]
    assert payload["resourceSpans"][0]["scopeSpans"][0]["spans"][0]["name"] == "test"
