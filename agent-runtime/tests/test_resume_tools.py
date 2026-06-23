
"""简历工具单元测试。

通过桩 LLM 客户端验证 PDF 简历抽取、岗位匹配评分排序、字段兜底与路径越权拦截。
"""

import json
from uuid import uuid4

import pytest

from app.core.tool.base import ToolExecutionContext
from app.models.schemas import ToolCall
from app.tools_builtin import resume_tools
from app.tools_builtin.resume_tools import ResumeAnalyzeTool, ResumeMatchTool, ResumeParseTool, _extract_json


class _StubLLM:
    def __init__(self, content):
        self._content = content
        self.calls = []

    async def chat(self, messages, temperature=None, max_tokens=None):
        self.calls.append({"messages": messages, "temperature": temperature, "max_tokens": max_tokens})
        return {"content": self._content}


def _write_pdf_stub(path, text):
    path.write_bytes(b"%PDF-1.4\n% job-buddy test pdf stub\n")
    return text


def _context(workspace):
    return ToolExecutionContext(
        run_id=f"run_{uuid4().hex[:8]}",
        trace_id=f"trace_{uuid4().hex[:8]}",
        session_id=f"session_{uuid4().hex[:8]}",
        workspace_dir=str(workspace),
    )


@pytest.mark.asyncio
async def test_resume_parse_pdf(monkeypatch, workspace):
    resume_text = _write_pdf_stub(workspace / "demo.pdf", "张三\n5 年 Java 后端,熟悉 Spring Boot、MySQL、Kafka")
    monkeypatch.setattr(resume_tools, "_read_resume_text", lambda path: resume_text)

    llm_payload = {
        "name": "张三",
        "summary": "5 年 Java 后端",
        "years_experience": 5,
        "current_title": "高级 Java 工程师",
        "skills": ["Java", "Spring Boot", "MySQL", "Kafka"],
        "experiences": [],
        "projects": [],
    }
    stub = _StubLLM(content=json.dumps(llm_payload, ensure_ascii=False))
    tool = ResumeParseTool(llm_client=stub)

    call = ToolCall(id="c1", name="resume_parse", arguments={"file_path": "demo.pdf"})
    result = await tool.safe_run(call, _context(workspace))

    assert result.success is True
    assert result.output["resume"]["name"] == "张三"
    assert result.output["resume"]["years_experience"] == 5
    assert "Kafka" in result.output["resume"]["skills"]
    assert result.output["resume"]["expected_titles"] == []
    assert result.output["raw_text_chars"] > 0


@pytest.mark.asyncio
async def test_resume_parse_handles_fenced_json(monkeypatch, workspace):
    resume_text = _write_pdf_stub(workspace / "r.pdf", "候选人摘要")
    monkeypatch.setattr(resume_tools, "_read_resume_text", lambda path: resume_text)
    fenced = "```json\n" + json.dumps({"name": "李四", "skills": ["Go"]}, ensure_ascii=False) + "\n```"
    tool = ResumeParseTool(llm_client=_StubLLM(content=fenced))

    call = ToolCall(id="c2", name="resume_parse", arguments={"file_path": "r.pdf"})
    result = await tool.safe_run(call, _context(workspace))

    assert result.success is True
    assert result.output["resume"]["name"] == "李四"
    assert result.output["resume"]["skills"] == ["Go"]


def test_extract_json_reports_incomplete_json():
    with pytest.raises(ValueError) as exc_info:
        _extract_json('{"name":"张三"')

    assert "不是完整 JSON" in str(exc_info.value)


@pytest.mark.asyncio
async def test_resume_parse_path_escape_rejected(workspace, tmp_path):
    outside = tmp_path / "outside.md"
    outside.write_text("x", encoding="utf-8")

    tool = ResumeParseTool(llm_client=_StubLLM(content="{}"))
    call = ToolCall(id="c3", name="resume_parse", arguments={"file_path": "../outside.md"})
    result = await tool.safe_run(call, _context(workspace))

    assert result.success is False
    assert "越界" in result.error or "不存在" in result.error


@pytest.mark.asyncio
async def test_resume_parse_unsupported_suffix(workspace):
    (workspace / "r.md").write_text("x", encoding="utf-8")
    tool = ResumeParseTool(llm_client=_StubLLM(content="{}"))
    call = ToolCall(id="c4", name="resume_parse", arguments={"file_path": "r.md"})
    result = await tool.safe_run(call, _context(workspace))
    assert result.success is False
    assert "不支持" in result.error


@pytest.mark.asyncio
async def test_resume_analyze_outputs_sections(monkeypatch, workspace):
    resume_text = _write_pdf_stub(workspace / "a.pdf", "张三\nJava 后端\n项目: Agent 平台")
    monkeypatch.setattr(resume_tools, "_read_resume_text", lambda path: resume_text)
    payload = {
        "overall_score": 82,
        "summary": "工程背景清晰",
        "advantages": [{"title": "后端扎实", "detail": "Java 经历明确", "evidence": "Java 后端"}],
        "disadvantages": [],
        "problems": [],
        "interview_deep_dive_points": [{"topic": "Agent", "question": "工具调用如何设计", "reason": "项目相关", "preparation": "准备架构图"}],
        "layout_issues": [],
        "typo_issues": [],
        "action_items": ["补充量化指标"],
    }
    tool = ResumeAnalyzeTool(llm_client=_StubLLM(content=json.dumps(payload, ensure_ascii=False)))
    call = ToolCall(id="c7", name="resume_analyze", arguments={"file_path": "a.pdf", "parsed": {"skills": ["Java"]}})
    result = await tool.safe_run(call, _context(workspace))
    assert result.success is True
    assert result.output["analysis"]["overall_score"] == 82
    assert result.output["analysis"]["interview_deep_dive_points"][0]["topic"] == "Agent"


@pytest.mark.asyncio
async def test_resume_match_sorts_and_clamps_scores_with_evidence(workspace):
    resume = {
        "summary": "Java 后端 5 年",
        "years_experience": 5,
        "skills": ["Java", "Spring Boot"],
    }
    jobs = [
        {"securityId": "j1", "jobName": "Java 后端", "skills": ["Java"], "salaryDesc": "20-40K"},
        {"securityId": "j2", "jobName": "前端", "skills": ["React"], "salaryDesc": "15-25K"},
    ]

    llm_response = {
        "evaluation_schema": "evidence_based_resume_job_match_v2",
        "matches": [
            {
                "id": "j1",
                "score": 88,
                "score_confidence": "high",
                "evidence": [{"resume_evidence": "Java 后端 5 年", "job_requirement": "Java", "assessment": "技术栈匹配"}],
                "hits": ["技术栈匹配"],
                "gaps": [],
                "reasoning": "对口",
                "recommendation": "推荐",
            },
            {
                "id": "j2",
                "score": 150,
                "score_confidence": "medium",
                "evidence": [{"resume_evidence": "无前端经历", "job_requirement": "React", "assessment": "不匹配"}],
                "hits": [],
                "gaps": ["技术栈不符"],
                "reasoning": "不对口",
                "recommendation": "不建议",
            },
        ],
    }
    stub = _StubLLM(content=json.dumps(llm_response, ensure_ascii=False))
    tool = ResumeMatchTool(llm_client=stub)

    call = ToolCall(id="c5", name="resume_match", arguments={"resume": resume, "jobs": jobs})
    result = await tool.safe_run(call, _context(workspace))

    assert result.success is True
    matches = result.output["matches"]
    assert [m["id"] for m in matches] == ["j2", "j1"]  # 150 clamped to 100, comes first
    assert matches[0]["score"] == 100
    assert matches[1]["score"] == 88
    assert matches[1]["score_confidence"] == "high"
    assert matches[1]["evidence"][0]["assessment"] == "技术栈匹配"
    assert result.output["evaluation_schema"] == "evidence_based_resume_job_match_v2"
    assert result.output["scored_count"] == 2


@pytest.mark.asyncio
async def test_resume_match_falls_back_on_missing_id(workspace):
    resume = {"skills": ["Python"]}
    jobs = [{"jobName": "Python 工程师", "skills": ["Python"]}]
    llm_response = {"matches": [{"score": 70}]}
    tool = ResumeMatchTool(llm_client=_StubLLM(content=json.dumps(llm_response)))

    call = ToolCall(id="c6", name="resume_match", arguments={"resume": resume, "jobs": jobs})
    result = await tool.safe_run(call, _context(workspace))

    assert result.success is True
    assert result.output["matches"][0]["id"] == "job_0"
    assert result.output["matches"][0]["hits"] == []
    assert result.output["matches"][0]["score_confidence"] == "low"


@pytest.mark.asyncio
async def test_resume_match_rejects_empty_jobs(workspace):
    tool = ResumeMatchTool(llm_client=_StubLLM(content="[]"))
    call = ToolCall(id="c7", name="resume_match", arguments={"resume": {}, "jobs": []})
    result = await tool.safe_run(call, _context(workspace))
    assert result.success is False
    assert "非空" in result.error
