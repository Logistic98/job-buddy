from app.llm_classifier import _parse_result, classify_with_llm
from app.scorer import score_intent
from app.service import classify_intent


def test_complex_engineering_intent():
    result = classify_intent("帮我实现一个复杂问答工作流")
    assert result.domain == "runtime"
    assert result.intent == "complex_engineering_qa"
    assert result.confidence > 0.8
    assert result.router == "rule"


def test_high_risk_intent_needs_clarification():
    result = classify_intent("删除生产 token")
    assert result.risk == "high"
    assert result.needs_clarification is True
    assert result.router == "rule"


def test_job_domain_result_tagged_as_rule():
    result = classify_intent("帮我找上海的 Java 岗位")
    assert result.domain == "job"
    assert result.router == "rule"


def test_scorer_catches_fuzzy_job_consult():
    result = classify_intent("拿到 offer 之后怎么谈薪")
    assert result.domain == "job"
    assert result.intent == "job.consult"
    assert result.router == "scorer"
    assert 0.6 <= result.confidence <= 0.9


def test_scorer_returns_none_below_threshold():
    assert score_intent("今天天气怎么样") is None


def test_fallback_open_domain_when_all_layers_miss():
    result = classify_intent("宇宙的尽头是什么")
    assert result.domain == "open_domain"
    assert result.router == "fallback"


def test_empty_message_clarifies():
    result = classify_intent("   ")
    assert result.needs_clarification is True
    assert result.next_action == "clarify"
    assert result.router == "fallback"


def test_llm_disabled_returns_none(monkeypatch):
    monkeypatch.delenv("AGENT_INTENT_LLM_ENABLED", raising=False)
    assert classify_with_llm("随便一句话") is None


def test_llm_enabled_but_unconfigured_degrades(monkeypatch):
    monkeypatch.setenv("AGENT_INTENT_LLM_ENABLED", "true")
    monkeypatch.delenv("AGENT_INTENT_LLM_BASE_URL", raising=False)
    monkeypatch.delenv("AGENT_INTENT_LLM_API_KEY", raising=False)
    monkeypatch.delenv("AGENT_INTENT_LLM_MODEL", raising=False)
    assert classify_with_llm("随便一句话") is None


def test_llm_parse_valid_payload():
    content = '{"domain": "job", "intent": "job.consult", "confidence": 0.93, "risk": "low", "needs_clarification": false, "next_action": "direct_answer_with_trace"}'
    result = _parse_result(content)
    assert result is not None
    assert result.domain == "job"
    assert result.router == "llm"
    assert result.confidence == 0.93


def test_llm_parse_rejects_illegal_domain():
    content = '{"domain": "hacker", "intent": "x", "confidence": 0.9, "risk": "low", "needs_clarification": false, "next_action": "y"}'
    assert _parse_result(content) is None


def test_llm_parse_rejects_invalid_json():
    assert _parse_result("不是 JSON") is None
