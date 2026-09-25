from types import SimpleNamespace
from unittest.mock import AsyncMock

import pytest

from app.core.capability.models import CapabilityCard, ConversationShortcut, ProfileDefinition
from app.core.intent.task_understanding import TaskUnderstandingService
from app.models.schemas import AgentRunRequest, ChatMessage, TaskUnderstandingResult


@pytest.fixture
def service():
    return TaskUnderstandingService(allow_semantic_fallback=True)


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("截至2020", ("", "2020-12-31")),
        ("截至2020年2月", None),
        ("2020-02-29", ("", "2020-02-29")),
        ("2020-02-31", None),
    ],
)
def test_explicit_time_bounds_require_valid_complete_calendar_dates(service, text, expected):
    assert service._latest_time_bounds(text, require_latest=False) == expected


def test_json_and_reference_normalization_rejects_invalid_rows(service):
    assert service._extract_json('```json\n{"answer":"ok"}\n```') == {"answer": "ok"}
    assert service._extract_json('Response: {"answer":"ok"}') == {"answer": "ok"}
    assert service._extract_json("[]") == {}
    references = service._resolved_references([None, {}, {"text": "prior", "confidence": "invalid"}])
    assert len(references) == 1 and references[0].confidence == 0
    compact = service._compact_message(
        ChatMessage(role="tool", content="long text", name="echo", tool_call_id="call"), limit=4
    )
    assert compact == {"role": "tool", "content": "long...(truncated)", "name": "echo", "tool_call_id": "call"}
    assert service._last_user_message(AgentRunRequest(messages=[])) == ""


def test_code_and_rendering_rules_do_not_invent_missing_capabilities(service):
    profile = ProfileDefinition(id="empty", name="Empty")
    assert service._build_code_generation_result(profile, "write Python code", "trace") is None
    assert service._understand_with_content_rendering_rule(profile, "", "trace") is None
    assert service._understand_with_content_rendering_rule(profile, "编写 Python 代码", "trace") is None
    assert service._has_strong_literal_code("```python\nprint(1)\n```")
    assert not service._is_complete_code_line("   ")
    assert not service._requests_new_code("不要编写代码，只解释原理")
    assert service._score_capabilities(profile, "question", {})[0][2].id == "general.chat"


@pytest.mark.parametrize("confidence", [None, "invalid", -1, 2, 0.1])
def test_intent_hint_rejects_missing_invalid_or_low_confidence(service, confidence):
    profile = ProfileDefinition(id="test", name="Test", intent_hint_fast_path={"enabled": True})
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="question")],
        metadata={"intent_hint": {"router": "rule", "needs_clarification": False, "confidence": confidence}},
    )
    assert service._understand_with_validated_intent_hint(profile, request, "question", "trace") is None


@pytest.mark.parametrize(
    ("capability", "allowed"),
    [(None, []), (CapabilityCard(id="chat", name="Chat", intent="chat", risk="high"), ["chat"])],
)
def test_intent_hint_cannot_authorize_missing_or_high_risk_capability(service, capability, allowed):
    profile = ProfileDefinition(
        id="test",
        name="Test",
        capabilities=[capability] if capability else [],
        intent_hint_fast_path={"enabled": True, "allowed_capability_ids": allowed},
    )
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="question")],
        metadata={"intent_hint": {"router": "rule", "needs_clarification": False, "confidence": 1, "intent": "chat"}},
    )
    assert service._understand_with_validated_intent_hint(profile, request, "question", "trace") is None


def test_directive_carries_missing_slots_clarification_and_existing_answer(service):
    profile = ProfileDefinition(id="test", name="Test", directive_type="test")
    task = TaskUnderstandingResult(
        original_query="goal",
        slots={"missing_required": ["city"]},
        clarification={"needed": True, "question": "Which city?"},
        answer="Please clarify",
    )
    directive = service.build_directive(profile, task)
    assert directive["slots"]["missing_slots"] == ["city"]
    assert directive["slots"]["clarification_question"] == "Which city?"
    assert directive["answer"] == "Please clarify"


@pytest.mark.asyncio
async def test_model_failure_falls_back_to_semantic_configuration(service):
    service.llm_client = SimpleNamespace(chat=AsyncMock(side_effect=ValueError("malformed response")))
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="解释一个概念")])
    task = await service.understand(request, "session", "run", "trace")
    assert task.trace_id == "trace"
    assert task.routing.selected_capability is not None
    service.llm_client.chat.assert_awaited_once()


def test_invalid_shortcut_regex_does_not_prevent_other_routes(service):
    profile = ProfileDefinition(
        id="test",
        name="Test",
        conversation_shortcuts=[ConversationShortcut(id="invalid", capability_id="chat", patterns=["["])],
    )
    assert service._match_shortcut(profile, "next", {"page": 1}) is None
    for confidence in ["invalid", 0.1]:
        assert (
            service._inherited_latest_time_bounds(
                "最新呢",
                {"resolved_references": [{"source": "history", "confidence": confidence, "resolved_to": "2020"}]},
                [],
            )
            is None
        )
    assert (
        service._inherited_latest_time_bounds(
            "Please find the latest engineering article without referring to prior messages", {}, []
        )
        is None
    )


def test_default_capability_uses_stable_id_order_and_no_missing_slots_needs_no_question(service):
    profile = ProfileDefinition(
        id="test",
        name="Test",
        capabilities=[CapabilityCard(id="z", name="Z", intent="z"), CapabilityCard(id="a", name="A", intent="a")],
    )
    assert service.result_builder.default_capability(profile).id == "a"
    assert service.result_builder._default_clarification_question([]) is None
    assert (
        service._understand_with_content_rendering_rule(
            profile, "仅输出 Markdown 代码块，代码内容为：print(1)，不要解释", "trace"
        )
        is None
    )


def test_rule_hint_missing_required_slot_requires_clarification(service):
    capability = CapabilityCard(
        id="lookup",
        name="Lookup",
        intent="lookup",
        execution_mode="STABLE_WORKFLOW",
        required_slots=["city"],
        keywords=["lookup"],
    )
    profile = ProfileDefinition(
        id="test",
        name="Test",
        capabilities=[capability],
        intent_hint_fast_path={"enabled": True, "allowed_capability_ids": ["lookup"], "min_semantic_confidence": 0},
    )
    request = AgentRunRequest(
        messages=[ChatMessage(role="user", content="lookup")],
        metadata={
            "intent_hint": {
                "router": "rule",
                "needs_clarification": False,
                "confidence": 1,
                "domain": "general",
                "intent": "lookup",
                "next_action": "direct_answer",
                "risk": "low",
            }
        },
    )
    assert service._understand_with_validated_intent_hint(profile, request, "lookup", "trace") is None
    result = service._understand_with_semantic_config(profile, request, "lookup", {}, "trace")
    assert result.clarification.needed
    assert "city" in result.clarification.question


def test_semantic_shortcut_and_model_answer_preserve_declared_content(service):
    capability = CapabilityCard(id="chat", name="Chat", intent="chat")
    profile = ProfileDefinition(
        id="test",
        name="Test",
        capabilities=[capability],
        conversation_shortcuts=[ConversationShortcut(id="next", capability_id="chat", phrases=["next"])],
    )
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="next")])
    result = service._understand_with_semantic_config(profile, request, "next", {"page": 1}, "trace")
    assert result.router == "semantic_config_shortcut"
    result = service._normalize_model_result(
        profile, {"selected_capability_id": "chat", "answer": "verified answer"}, "question", {}, "trace"
    )
    assert result.answer == "verified answer"
