import json
from unittest.mock import AsyncMock, Mock

import pytest

from app.core.llm.openai_client import LLMServiceError
from app.core.tool.base import ToolExecutionContext
from app.tools_builtin import interview_tools as module

CONTEXT = ToolExecutionContext(run_id="run", trace_id="trace", session_id="session")
CANDIDATES = [{"question_id": "one", "title": "Question"}]
GENERATION = {
    "topic": "Python",
    "bank_type": "qa",
    "category": "Python",
    "difficulty": "简单",
    "question_type": "问答题",
    "count": 1,
}


@pytest.mark.parametrize("value", ["", "invalid", "[]"])
def test_generated_json_requires_complete_object(value):
    with pytest.raises(ValueError):
        module._extract_json_object(value)


@pytest.mark.parametrize("url", ["http://leetcode.com/problems/test/", "https://leetcode.com/not-problem"])
def test_source_requires_https_problem_path(url):
    with pytest.raises(ValueError):
        module._validate_source_url(url)


@pytest.mark.parametrize(
    "tests",
    [
        [{}] * 3,
        [{"args": [], "expected": 1}] * 3,
        [{"args": [1], "expected": 1}, {"args": [1, 2], "expected": 2}, {"args": [1], "expected": 1}],
    ],
)
def test_coding_examples_require_consistent_function_arguments(tests):
    with pytest.raises(ValueError):
        module._normalize_tests(tests)


@pytest.mark.parametrize("changes", [{"language": "java"}, {"functionName": "invalid-name"}, {"template": "other"}])
def test_coding_template_must_match_requested_language_and_entrypoint(changes):
    meta = {"language": "python", "functionName": "solve", "template": "def solve(x): pass", **changes}
    with pytest.raises(ValueError):
        module._normalize_coding_meta(meta, "python")
    with pytest.raises(ValueError):
        module._normalize_coding_meta(None, "python")


@pytest.mark.parametrize("rows", [[], [None], CANDIDATES * 2, CANDIDATES * 201])
def test_paper_candidates_are_bounded_unique_objects(rows):
    with pytest.raises(ValueError):
        module._normalize_paper_candidates(rows)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "changes",
    [
        {"bank_type": "invalid"},
        {"count": "invalid"},
        {"count": 21},
        {"bank_type": "leetcode", "language": "invalid"},
        {"difficulty": "invalid"},
        {"source_url": "https://evil.invalid"},
    ],
)
async def test_generation_validation_rejects_invalid_contract(changes):
    tool = module.InterviewQuestionGenerateTool()
    assert (await tool.validate_input({**GENERATION, **changes}, CONTEXT)).result is False
    assert (await tool.validate_input({}, CONTEXT)).result is False


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "arguments",
    [
        {},
        {"requirements": "short", "candidates": CANDIDATES},
        {"requirements": "long enough requirements", "candidates": []},
    ],
)
async def test_paper_input_validation(arguments):
    assert (await module.InterviewPaperComposeTool().validate_input(arguments, CONTEXT)).result is False


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", ["paper", "generation"])
@pytest.mark.parametrize("failure", ["prompt", "transport"])
async def test_generation_dependencies_fail_explicitly(monkeypatch, kind, failure):
    client = Mock(chat=AsyncMock(side_effect=LLMServiceError("offline")))
    monkeypatch.setattr(module, "OpenAICompatibleClient", lambda: client)
    loader = Mock()
    loader.load.return_value = "" if failure == "prompt" else "instructions"
    tool = (
        module.InterviewPaperComposeTool(prompt_loader=loader)
        if kind == "paper"
        else module.InterviewQuestionGenerateTool(prompt_loader=loader)
    )
    arguments = (
        {"requirements": "long enough requirements", "candidates": CANDIDATES} if kind == "paper" else GENERATION
    )
    with pytest.raises(RuntimeError):
        await tool._run(arguments, CONTEXT)
    assert client.chat.await_count == (0 if failure == "prompt" else 1)


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "changes",
    [
        {"duration_minutes": "invalid"},
        {"duration_minutes": 241},
        {"show_answer": "true"},
        {"question_ids": []},
        {"question_ids": [""]},
        {"question_ids": ["one", "one"]},
        {"question_ids": ["outside"]},
    ],
)
async def test_model_paper_plan_cannot_escape_candidate_contract(changes):
    payload = {
        "title": "Paper",
        "duration_minutes": 30,
        "show_answer": True,
        "question_ids": ["one"],
        "selection_summary": "Chosen",
        **changes,
    }
    client = Mock(chat=AsyncMock(return_value={"content": json.dumps(payload)}))
    tool = module.InterviewPaperComposeTool(
        llm_client=client, prompt_loader=Mock(load=Mock(return_value="instructions"))
    )
    with pytest.raises(ValueError):
        await tool._run({"requirements": "long enough requirements", "candidates": CANDIDATES}, CONTEXT)


def test_question_normalization_rejects_wrong_shape_and_uses_requested_difficulty():
    with pytest.raises(ValueError):
        module._normalize_item([], "qa", "Python", "简单", "问答题", "python")
    result = module._normalize_item(
        {"title": "Question", "content": "Content", "difficulty": "invalid"}, "qa", "Python", "简单", "问答题", "python"
    )
    assert result["difficulty"] == "简单"


@pytest.mark.parametrize("value", [None, "", "   "])
def test_required_generated_fields_cannot_be_blank(value):
    with pytest.raises(ValueError, match="title"):
        module._required_text(value, "title")


def test_coding_metadata_and_minimum_example_count_are_required():
    with pytest.raises(ValueError, match="codingMeta"):
        module._normalize_coding_meta(None, "python")
    with pytest.raises(ValueError, match="至少需要 3"):
        module._normalize_tests([])


@pytest.mark.asyncio
async def test_generation_rejects_empty_topic_and_wrong_batch_size():
    client = Mock(chat=AsyncMock(return_value={"content": '{"items": []}'}))
    tool = module.InterviewQuestionGenerateTool(
        llm_client=client, prompt_loader=Mock(load=Mock(return_value="instructions"))
    )
    assert not (await tool.validate_input({**GENERATION, "topic": ""}, CONTEXT)).result
    assert (await tool.validate_input(GENERATION, CONTEXT)).result
    with pytest.raises(ValueError, match="模型应返回 1 道"):
        await tool._run(GENERATION, CONTEXT)


def test_coding_examples_default_first_sample_when_model_omits_sample_flag():
    tests, parameter_count = module._normalize_tests(
        [{"args": [1], "expected": 2}, {"args": [2], "expected": 3}, {"args": [3], "expected": 4}]
    )
    assert tests[0]["sample"] is True
    assert parameter_count == 1
