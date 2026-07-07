from app.clarification import apply_clarification_gate
from app.models import IntentResult
from app.service import classify_intent


def _result(confidence: float, risk: str = "low", **overrides) -> IntentResult:
    payload = {
        "domain": "open_domain",
        "intent": "complex_question_answering",
        "confidence": confidence,
        "risk": risk,
        "needs_clarification": False,
        "next_action": "direct_answer_with_trace",
        "router": "scorer",
    }
    payload.update(overrides)
    return IntentResult(**payload)


def test_low_confidence_forces_clarification():
    result = apply_clarification_gate(_result(0.3))
    assert result.needs_clarification is True
    assert result.next_action == "clarify"
    assert "low_confidence" in result.secondary
    assert result.slots["clarification_question"]


def test_confidence_at_threshold_passes_through():
    result = apply_clarification_gate(_result(0.5))
    assert result.needs_clarification is False
    assert result.next_action == "direct_answer_with_trace"


def test_high_risk_result_is_not_downgraded_to_clarify():
    result = apply_clarification_gate(
        _result(0.3, risk="high", next_action="request_human_confirmation", needs_clarification=True)
    )
    assert result.next_action == "request_human_confirmation"
    assert "low_confidence" not in result.secondary


def test_existing_clarification_question_is_not_overwritten():
    result = apply_clarification_gate(_result(0.3, slots={"clarification_question": "请问岗位方向？"}))
    assert result.slots["clarification_question"] == "请问岗位方向？"


def test_threshold_is_configurable(monkeypatch):
    monkeypatch.setenv("AGENT_INTENT_CLARIFY_CONFIDENCE_THRESHOLD", "0.9")
    result = apply_clarification_gate(_result(0.78))
    assert result.needs_clarification is True
    assert result.next_action == "clarify"


def test_classify_intent_exit_applies_gate():
    # 空消息走澄清兜底（0.2 低于阈值），出口澄清门补充标准化澄清问题。
    result = classify_intent("")
    assert result.needs_clarification is True
    assert result.next_action == "clarify"
    assert result.slots.get("clarification_question")


def test_classify_intent_normal_path_unchanged():
    result = classify_intent("帮我推荐一下上海的 Java 后端岗位")
    assert result.domain == "job"
    assert result.needs_clarification is False or result.slots.get("clarification_question")
