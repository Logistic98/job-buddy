import sys
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from app.core.llm.openai_client import LLMServiceError
from app.core.tool.base import ToolExecutionContext
from app.tools_builtin import resume_tools as module


@pytest.fixture
def context(tmp_path):
    return ToolExecutionContext(run_id="run", trace_id="trace", session_id="session", workspace_dir=str(tmp_path))


def test_supplemental_evidence_deduplicates_and_enforces_both_budgets():
    short = [f"github project {i}" for i in range(30)]
    assert module._extract_supplemental_evidence("\n".join([short[0], *short])) == short[:24]
    long = [f"github {i} " + "x" * 490 for i in range(20)]
    result = module._extract_supplemental_evidence("\n".join(long))
    assert sum(map(len, result)) <= 5000
    assert len(result) < len(long)


def test_resume_paths_require_existing_file(tmp_path):
    with pytest.raises(ValueError, match="不存在"):
        module._resolve_workspace_path("missing.pdf", str(tmp_path))
    with pytest.raises(ValueError, match="不是文件"):
        module._resolve_workspace_path(".", str(tmp_path))
    with pytest.raises(ValueError, match="不支持"):
        module._read_resume_text(tmp_path / "resume.txt")
    assert module._truncate("abcdef", 3).startswith("abc\n")


def test_pdf_page_failure_is_tolerated_but_empty_document_is_rejected(monkeypatch, tmp_path):
    import pypdf

    bad = Mock()
    bad.extract_text.side_effect = ValueError("encrypted page")
    good = Mock()
    good.extract_text.return_value = "readable page"
    monkeypatch.setattr(pypdf, "PdfReader", lambda _: SimpleNamespace(pages=[bad, good]))
    assert module._read_resume_text(tmp_path / "resume.pdf") == "readable page"
    good.extract_text.return_value = ""
    with pytest.raises(RuntimeError, match="抽取为空"):
        module._extract_pdf_text(tmp_path / "resume.pdf")
    monkeypatch.setitem(sys.modules, "pypdf", None)
    with pytest.raises(RuntimeError, match="未安装"):
        module._extract_pdf_text(tmp_path / "resume.pdf")


@pytest.mark.parametrize("value", ["", "text {broken} [broken]", "```json\n{bad}\n```"])
def test_invalid_json_is_rejected(value):
    with pytest.raises(ValueError):
        module._extract_json(value)


def test_fenced_array_and_surrounding_array_are_parsed():
    assert module._extract_json("```json\n[1, 2]\n```") == [1, 2]
    assert module._extract_json("prefix [1, 2] suffix") == [1, 2]


@pytest.mark.parametrize("score", ["invalid", float("nan"), float("inf")])
def test_resume_scores_require_finite_numbers(score):
    with pytest.raises(ValueError, match="不是.*数字"):
        module._normalize_resume_score_breakdown({"content_completeness": {"score": score}})
    with pytest.raises(ValueError, match="缺少"):
        module._normalize_resume_score_breakdown([])


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", ["ResumeParseTool", "ResumeAnalyzeTool", "JobProfileSummaryTool", "ResumeMatchTool"])
@pytest.mark.parametrize("failure", ["transport", "schema"])
async def test_resume_llm_failures_are_not_reported_as_success(monkeypatch, tmp_path, context, kind, failure):
    (tmp_path / "resume.pdf").write_bytes(b"test")
    monkeypatch.setattr(module, "_read_resume_text", lambda _: "resume text")
    client = Mock()
    client.chat = AsyncMock(return_value={"content": "[]"})
    if failure == "transport":
        client.chat.side_effect = LLMServiceError("offline")
    monkeypatch.setattr(module, "OpenAICompatibleClient", Mock(return_value=client))
    tool = getattr(module, kind)()
    arguments = {"file_path": "resume.pdf", "profile": {}, "resume": {}, "jobs": [{"id": "job"}]}
    with pytest.raises(RuntimeError if failure == "transport" else ValueError):
        await tool._run(arguments, context)
    client.chat.assert_awaited_once()


@pytest.mark.asyncio
async def test_profile_requires_object_and_nonempty_summary(context):
    client = Mock(chat=AsyncMock(return_value={"content": '{"summary": " "}'}))
    tool = module.JobProfileSummaryTool(llm_client=client)
    with pytest.raises(ValueError, match="必须是对象"):
        await tool._run({"profile": []}, context)
    with pytest.raises(ValueError, match="摘要为空"):
        await tool._run({"profile": {}}, context)
    compact = tool._compact_profile({"expectations": ["invalid"], "status": ["invalid"]})
    assert compact["expected_titles"] == []


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "changes",
    [
        {"resume": []},
        {"jobs": []},
        {"top_k": -1},
        {"evaluation_mode": "invalid"},
        {"jobs": [{"id": "same"}, {"id": "same"}]},
    ],
)
async def test_match_input_contracts_fail_before_model(context, changes):
    client = Mock(chat=AsyncMock())
    tool = module.ResumeMatchTool(llm_client=client)
    with pytest.raises(ValueError):
        await tool._run({"resume": {}, "jobs": [{"id": "job"}], **changes}, context)
    client.chat.assert_not_called()


def test_match_shape_and_legacy_record_normalization():
    tool = module.ResumeMatchTool
    assert tool._extract_match_rows({"match": {"score": 1}}) == [{"score": 1}]
    assert tool._extract_match_rows({"score": 1}) == [{"score": 1}]
    with pytest.raises(ValueError, match="ID 重复"):
        tool._align_match_rows([], [{"id": "same"}, {"id": "same"}])
    assert tool._compact_recommendation_list_match({"evidence": ["invalid"]})["evidence"] == []
    compact = tool._compact_resume({"experiences": "legacy", "projects": ["text"]})
    assert compact["work_experiences"] == ["legacy"]
    assert compact["project_experiences"] == ["text"]
    assert tool._normalize_dimensions({"education_fit": {"score": "invalid"}})["education_fit"]["score"] == 50
    normalized = tool._normalize_match({"score": "invalid"}, 0, [{"skills": ["Java"]}])
    assert normalized["score"] is None
    assert normalized["recommendation"] == "证据不足"
