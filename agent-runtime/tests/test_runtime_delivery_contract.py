from pathlib import Path

import httpx
import pytest

from app.core.agent.executor import AgentExecutor
from app.core.checkpoint.store import CheckpointStore
from app.core.common.constants import PermissionMode
from app.core.common.settings import settings
from app.core.observability.trace import TraceRecorder
from app.core.security.transcript_review import (
    APPROVE,
    REQUIRE_CONFIRMATION,
    TranscriptReviewClient,
    TranscriptReviewDecision,
)
from app.core.tool.gateway import ToolGateway
from app.models.schemas import AgentRunRequest, BudgetConfig, ChatMessage, ToolCall


class _StreamingLlm:
    def __init__(self):
        self.max_tokens = None

    async def stream_chat(self, messages, max_tokens=None):
        self.max_tokens = max_tokens
        yield {"type": "text", "text": "ok"}

    def get_cache_metrics(self):
        return {}


class _FailingGraph:
    async def ainvoke(self, state):
        raise RuntimeError("contract failure")


class _ConfirmingReviewer:
    async def review(self, messages, call):
        return TranscriptReviewDecision(
            decision=REQUIRE_CONFIRMATION,
            risk="high",
            matched_rules=["tool_marker:file_write"],
        )


class _RetryingHttpClient:
    attempts = 0
    payload = None
    headers = None

    def __init__(self, *args, **kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, traceback):
        return False

    async def post(self, url, json, headers):
        type(self).attempts += 1
        type(self).payload = json
        type(self).headers = headers
        request = httpx.Request("POST", url)
        if type(self).attempts == 1:
            raise httpx.ConnectError("temporary connection failure", request=request)
        return httpx.Response(
            200,
            request=request,
            json={
                "code": 200,
                "message": "success",
                "data": {"decision": APPROVE, "risk": "high", "matched_rules": []},
            },
        )


@pytest.mark.asyncio
async def test_direct_stream_applies_the_smaller_response_and_run_token_limit(monkeypatch):
    monkeypatch.setattr(settings.config.llm_service, "max_tokens", 4096)
    llm = _StreamingLlm()
    executor = AgentExecutor(llm_client=llm, use_llm=False)
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="生成回答")],
        budget=BudgetConfig(max_tokens=1200),
        metadata={"runtime_execute": True, "upstream_directive": {}},
    )

    events = [event async for event in executor.execute_stream(request)]

    assert any(event["event"] == "done" for event in events)
    assert llm.max_tokens == 1200


@pytest.mark.asyncio
async def test_failed_run_has_failed_top_level_trace_status():
    executor = AgentExecutor(use_llm=False)
    executor.graph = _FailingGraph()

    response = await executor.execute(AgentRunRequest(messages=[ChatMessage(role="user", content="触发失败")]))

    run_end = [event for event in response.trace_events if event.event == "run_end"][-1]
    assert response.status.value == "fail"
    assert run_end.status == "failed"
    assert run_end.error == "contract failure"


@pytest.mark.asyncio
async def test_checkpoint_removes_reconstructable_personal_context_and_redacts_pii():
    store = CheckpointStore(database_url="")
    await store.save(
        "session_contract",
        "run_contract",
        "collect_context",
        {
            "messages": [{"role": "user", "content": "邮箱 test@example.com，手机 13800138000"}],
            "metadata": {
                "personal_context": {"resume": "full resume"},
                "payload": '{"password":"secret-value"}',
            },
            "context_payload": {"personal_context": {"resume": "duplicate"}},
            "context_summary": (
                '{"current_step":{"objective":"优化简历"},'
                '"recent_messages":[{"role":"user","content":"完整简历正文"}],'
                '"personal_context":{"resume":"另一份完整简历正文"}}'
            ),
        },
    )

    checkpoint = await store.load_latest("session_contract")
    rendered = str(checkpoint)
    assert "personal_context" not in checkpoint["state"]["metadata"]
    assert "messages" not in checkpoint["state"]
    assert "context_payload" not in checkpoint["state"]
    assert "recent_messages" not in checkpoint["state"]["context_summary"]
    assert "完整简历正文" not in rendered
    assert "test@example.com" not in rendered
    assert "13800138000" not in rendered
    assert "secret-value" not in rendered


@pytest.mark.asyncio
async def test_high_risk_tool_still_requires_independent_confirmation(fresh_registry, tool_context, monkeypatch):
    monkeypatch.setattr(settings.config.transcript_review, "enabled", True)
    gateway = ToolGateway(fresh_registry, transcript_reviewer=_ConfirmingReviewer())

    result = await gateway.execute(
        ToolCall(id="write_contract", name="file_write", arguments={"path": "x.txt", "content": "x"}),
        PermissionMode.AUTO,
        tool_context,
        transcript_messages=[ChatMessage(role="user", content="写一个文件")],
    )

    assert result.result.success is False
    assert result.result.metadata["policy"] == "transcript_review"
    assert result.permission_record is not None
    assert result.permission_record.requires_confirmation is True
    assert not (Path(tool_context.workspace_dir) / "x.txt").exists()


@pytest.mark.asyncio
async def test_transcript_review_retries_temporary_connection_failure(monkeypatch):
    _RetryingHttpClient.attempts = 0
    _RetryingHttpClient.payload = None
    _RetryingHttpClient.headers = None
    monkeypatch.setenv("AGENT_INTERNAL_SERVICE_TOKEN", "runtime-contract-token")
    monkeypatch.setattr(settings.config.transcript_review, "max_retries", 1)
    monkeypatch.setattr(settings.config.transcript_review, "retry_backoff_seconds", 0)
    monkeypatch.setattr(
        "app.core.security.transcript_review.httpx.AsyncClient",
        _RetryingHttpClient,
    )

    decision = await TranscriptReviewClient().review(
        [
            ChatMessage(role="user", content="读取状态"),
            ChatMessage(role="assistant", content="不会发送给复核器"),
        ],
        ToolCall(id="status_contract", name="shell_exec", arguments={"command": "pwd"}),
    )

    assert decision.decision == APPROVE
    assert _RetryingHttpClient.attempts == 2
    assert _RetryingHttpClient.payload["messages"] == [{"role": "user", "content": "读取状态"}]
    assert _RetryingHttpClient.headers == {"X-Internal-Service-Token": "runtime-contract-token"}


@pytest.mark.asyncio
async def test_persisted_trace_uses_private_directory_and_file_permissions(tmp_path, monkeypatch):
    monkeypatch.setattr(settings.config.observability, "enabled", True)
    monkeypatch.setattr(settings.config.observability, "persist_enabled", True)
    recorder = TraceRecorder(persist_dir=str(tmp_path / "trace"))

    await recorder.record("trace_contract", "run_start", {"email": "test@example.com"}, run_id="run_contract")

    trace_path = tmp_path / "trace" / "run_contract.jsonl"
    assert trace_path.stat().st_mode & 0o777 == 0o600
    assert trace_path.parent.stat().st_mode & 0o777 == 0o700
    assert "test@example.com" not in trace_path.read_text(encoding="utf-8")
