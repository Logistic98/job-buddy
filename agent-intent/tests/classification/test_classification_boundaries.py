"""分类入口、求职筛选边界和各层降级的行为契约。"""

from unittest.mock import Mock

import pytest

from app import api, service
from app.clarification import apply_clarification_gate
from app.domains import job
from app.models import IntentRequest, IntentResult
from app.scorer import score_intent
from app.transcript_review import TranscriptReviewRequest


def test_api_health_classification_and_review_envelopes(monkeypatch):
    assert api.health()["data"]["status"] == "UP"
    result = IntentResult(
        domain="job", intent="job.consult", confidence=0.9, risk="low", needs_clarification=False, next_action="answer"
    )
    monkeypatch.setattr(api, "classify_intent", lambda _: result)
    response = api.classify(IntentRequest(message="query"))
    assert response["code"] == 200
    assert len(response["data"]["trace_id"]) == 12
    result.trace_id = "existing-trace"
    assert api.classify(IntentRequest(message="query"))["data"]["trace_id"] == "existing-trace"
    assert api.review(TranscriptReviewRequest())["data"]["decision"] == "approve"


@pytest.mark.parametrize("operation", ["classify", "review"])
def test_api_errors_are_sanitized_and_use_standard_envelope(monkeypatch, operation):
    dependency = "classify_intent" if operation == "classify" else "review_transcript"
    monkeypatch.setattr(api, dependency, Mock(side_effect=RuntimeError("private dependency detail")))
    request = IntentRequest(message="query") if operation == "classify" else TranscriptReviewRequest()
    response = getattr(api, operation)(request)
    assert response["code"] == 500
    assert response["data"] == {}
    assert "private dependency detail" not in response["message"]


@pytest.mark.parametrize(
    ("text", "years", "category"),
    [
        ("二十年", 20, "十年以上"),
        ("十一年", 11, "十年以上"),
        ("二十三年", 23, "十年以上"),
        ("3-5年", 3, "三到五年"),
        ("0年", 0, "应届"),
        ("2年", 2, "一到三年"),
        ("8年", 8, "五到十年"),
        ("十十十十年", None, None),
    ],
)
def test_experience_boundaries_and_unrecognized_chinese_numbers(text, years, category):
    assert job._extract_experience(text) == (years, category)


@pytest.mark.parametrize(
    ("low", "high", "category"),
    [
        (0, None, None),
        (2, None, "3k以下"),
        (4, None, "3-5k"),
        (8, None, "5-10k"),
        (15, None, "10-20k"),
        (40, None, "20-50k"),
        (60, None, "50以上"),
        (None, 8, "5-10k"),
    ],
)
def test_salary_enum_boundaries(low, high, category):
    assert job._map_salary_enum(low, high) == category


def test_salary_range_and_clarification_keep_known_filters():
    assert job._extract_salary("期望8-15k") == (8, 15, "5-10k")
    assert job._extract_salary("期望60k") == (60, None, "50以上")
    city = job._build_clarification({"role": "Java"}, ["city"])
    assert "城市" in city.slots["clarification_question"]
    filters = job._build_clarification({"role": "Java", "city": "上海"}, ["salary"])
    assert "薪资" in filters.slots["clarification_question"]
    assert filters.slots["role"] == "Java"
    assert job.classify_job("") is None
    assert score_intent("") is None


def test_optional_llm_result_and_short_fallback_use_clarification_gate(monkeypatch):
    monkeypatch.setattr(service, "score_intent", lambda _: None)
    result = IntentResult(
        domain="open_domain",
        intent="answer",
        confidence=0.9,
        risk="low",
        needs_clarification=False,
        next_action="answer",
    )
    monkeypatch.setattr(service, "classify_with_llm", lambda _: result)
    assert service.classify_intent("普通话题") is result
    monkeypatch.setattr(service, "classify_with_llm", lambda _: None)
    assert service.classify_intent("嗯").needs_clarification
    result.confidence = 0.1
    result.secondary = ["low_confidence"]
    result.slots = {"clarification_question": "原有澄清问题"}
    assert apply_clarification_gate(result).secondary == ["low_confidence"]
    assert result.slots["clarification_question"] == "原有澄清问题"
