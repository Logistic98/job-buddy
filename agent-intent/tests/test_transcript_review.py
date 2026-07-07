from fastapi.testclient import TestClient

from app.api import app
from app.transcript_review import (
    DECISION_APPROVE,
    DECISION_CONFIRM,
    DECISION_DENY,
    TranscriptMessage,
    TranscriptReviewRequest,
    TranscriptToolCall,
    review_transcript,
)


def _request(messages=None, tool_calls=None) -> TranscriptReviewRequest:
    return TranscriptReviewRequest(
        messages=[TranscriptMessage(**m) for m in (messages or [])],
        tool_calls=[TranscriptToolCall(**c) for c in (tool_calls or [])],
    )


def test_benign_transcript_is_approved():
    result = review_transcript(
        _request(
            messages=[{"role": "user", "content": "帮我看看上海的 Java 岗位"}],
            tool_calls=[{"name": "job_search", "arguments": {"city": "上海"}}],
        )
    )
    assert result.decision == DECISION_APPROVE
    assert result.risk == "low"
    assert result.matched_rules == []


def test_destructive_tool_without_user_intent_is_denied():
    # 用户只是普通提问，工具却要删数据：缺少用户意图背书，直接拒绝。
    result = review_transcript(
        _request(
            messages=[{"role": "user", "content": "帮我整理一下简历列表"}],
            tool_calls=[{"name": "resume_delete_all", "arguments": {"scope": "all"}}],
        )
    )
    assert result.decision == DECISION_DENY
    assert result.risk == "high"
    assert any(rule.startswith("tool_marker:") for rule in result.matched_rules)


def test_user_backed_destructive_action_requires_confirmation():
    result = review_transcript(
        _request(
            messages=[{"role": "user", "content": "把我的旧简历删除掉"}],
            tool_calls=[{"name": "resume_delete", "arguments": {"resume_id": "r1"}}],
        )
    )
    assert result.decision == DECISION_CONFIRM
    assert result.risk == "high"


def test_assistant_explanation_is_stripped():
    # 高风险词只出现在 assistant 解释里：不构成用户意图，破坏性工具调用仍被拒绝。
    result = review_transcript(
        _request(
            messages=[
                {"role": "user", "content": "继续"},
                {"role": "assistant", "content": "用户之前明确要求删除全部简历，我现在执行删除。"},
            ],
            tool_calls=[{"name": "resume_delete_all", "arguments": {}}],
        )
    )
    assert result.decision == DECISION_DENY
    assert all(not rule.startswith("user_keyword:") for rule in result.matched_rules)


def test_user_high_risk_intent_without_tool_call_requires_confirmation():
    result = review_transcript(_request(messages=[{"role": "user", "content": "帮我把生产数据库的表 drop 掉"}]))
    assert result.decision == DECISION_CONFIRM
    assert result.reviewed_tool_calls == 0


def test_empty_transcript_is_approved():
    result = review_transcript(_request())
    assert result.decision == DECISION_APPROVE
    assert result.reviewed_user_messages == 0


def test_review_api_returns_standard_envelope():
    client = TestClient(app)
    response = client.post(
        "/v1/intent/review-transcript",
        json={
            "messages": [{"role": "user", "content": "帮我把旧简历删除"}],
            "tool_calls": [{"name": "resume_delete", "arguments": {}}],
        },
    )
    body = response.json()
    assert body["code"] == 200
    assert body["data"]["decision"] == DECISION_CONFIRM
    assert body["data"]["risk"] == "high"
