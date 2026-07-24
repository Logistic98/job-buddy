"""简历工具单元测试。

通过桩 LLM 客户端验证 PDF 简历抽取、岗位匹配评分排序、字段兜底与路径越权拦截。
"""

import json
from uuid import uuid4

import pytest

from app.core.tool.base import ToolExecutionContext
from app.models.schemas import ToolCall
from app.tools_builtin import resume_tools
from app.tools_builtin.resume_tools import (
    JobProfileSummaryTool,
    ResumeAnalyzeTool,
    ResumeMatchTool,
    ResumeParseTool,
    _extract_json,
    _normalize_resume_score_breakdown,
)


class _StubLLM:
    def __init__(self, content):
        self._content = content
        self.calls = []

    async def chat(self, messages, temperature=None, max_tokens=None, disable_thinking=False):
        self.calls.append(
            {
                "messages": messages,
                "temperature": temperature,
                "max_tokens": max_tokens,
                "disable_thinking": disable_thinking,
            }
        )
        return {"content": self._content}


def test_extract_json_merges_multiple_fenced_object_blocks():
    payload = _extract_json(
        """
        ```json
        {"evaluation_schema": "evidence_based_resume_job_match_v4"}
        ```
        ```json
        {"matches": [{"id": "j1", "score": 80}]}
        ```
        ```json
        {"limitations": []}
        ```
        """
    )

    assert payload == {
        "evaluation_schema": "evidence_based_resume_job_match_v4",
        "matches": [{"id": "j1", "score": 80}],
        "limitations": [],
    }


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


def _score_breakdown(score=82):
    return {
        key: {"score": score, "evidence": f"{definition['label']}证据"}
        for key, definition in resume_tools.RESUME_SCORE_DIMENSIONS.items()
    }


@pytest.mark.asyncio
async def test_resume_parse_pdf(monkeypatch, workspace):
    resume_text = _write_pdf_stub(workspace / "sample.pdf", "张三\n5 年 Java 后端,熟悉 Spring Boot、MySQL、Kafka")
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

    call = ToolCall(id="c1", name="resume_parse", arguments={"file_path": "sample.pdf"})
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
        "overall_score": 12,
        "score_breakdown": _score_breakdown(82),
        "summary": "工程背景清晰",
        "advantages": [{"title": "后端扎实", "detail": "Java 经历明确", "evidence": "Java 后端"}],
        "disadvantages": [],
        "problems": [],
        "interview_deep_dive_points": [
            {"topic": "Agent", "question": "工具调用如何设计", "reason": "项目相关", "preparation": "准备架构图"}
        ],
        "content_quality": [
            {
                "title": "成果证据不足",
                "detail": "项目结果缺少量化指标",
                "evidence": "仅描述平台研发",
                "suggestion": "补充性能或业务指标",
            }
        ],
        "experience_value": [
            {"title": "平台建设经验", "detail": "具备 Agent 平台研发经历", "evidence": "项目: Agent 平台"}
        ],
        "action_items": ["补充量化指标"],
    }
    stub = _StubLLM(content=json.dumps(payload, ensure_ascii=False))
    tool = ResumeAnalyzeTool(llm_client=stub)
    call = ToolCall(id="c7", name="resume_analyze", arguments={"file_path": "a.pdf", "parsed": {"skills": ["Java"]}})
    result = await tool.safe_run(call, _context(workspace))
    assert result.success is True
    assert result.output["analysis"]["overall_score"] == 85
    assert result.output["analysis"]["score_breakdown"]["achievement_evidence"]["weight"] == 25
    assert result.output["analysis"]["interview_deep_dive_points"][0]["topic"] == "Agent"
    assert result.output["analysis"]["content_quality"][0]["title"] == "成果证据不足"
    assert result.output["analysis"]["experience_value"][0]["title"] == "平台建设经验"
    assert "layout_issues" not in result.output["analysis"]
    assert "typo_issues" not in result.output["analysis"]
    system_prompt = stub.calls[0]["messages"][0].content
    assert "content_quality" in system_prompt
    assert "experience_value" in system_prompt
    assert "最终综合分由系统" in system_prompt
    assert "不要以完美简历或理想候选人为默认基准" in system_prompt
    assert "常规优秀简历" in system_prompt
    assert "achievement_evidence 不得高于74" in system_prompt
    assert "layout_issues" not in system_prompt
    assert "typo_issues" not in system_prompt


@pytest.mark.asyncio
async def test_resume_analyze_returns_only_requested_partial_sections(monkeypatch, workspace):
    resume_text = _write_pdf_stub(workspace / "partial.pdf", "Java Agent 项目经历")
    monkeypatch.setattr(resume_tools, "_read_resume_text", lambda path: resume_text)
    stub = _StubLLM(
        content=json.dumps(
            {
                "overall_score": 99,
                "score_breakdown": _score_breakdown(86),
                "summary": "具备 Agent 工程经验",
                "advantages": ["Agent 项目"],
                "action_items": ["不应在本分组返回"],
            },
            ensure_ascii=False,
        )
    )
    tool = ResumeAnalyzeTool(llm_client=stub)

    result = await tool.safe_run(
        ToolCall(
            id="partial-resume",
            name="resume_analyze",
            arguments={"file_path": "partial.pdf", "sections": ["overall_score", "summary", "advantages"]},
        ),
        _context(workspace),
    )

    analysis = result.output["analysis"]
    assert analysis["overall_score"] == 89
    assert analysis["summary"] == "具备 Agent 工程经验"
    assert "advantages" in analysis
    assert "action_items" not in analysis
    assert "score_breakdown" in analysis
    system_prompt = stub.calls[0]["messages"][0].content
    assert "本次只生成这些字段" in system_prompt
    assert "overall_score" not in system_prompt.split("本次只生成这些字段:", 1)[1]


def test_resume_score_is_weighted_from_dimensions_instead_of_model_total():
    score, breakdown = _normalize_resume_score_breakdown(
        {
            "content_completeness": {"score": 80, "evidence": "章节完整"},
            "achievement_evidence": {"score": 90, "evidence": "包含三项量化成果"},
            "experience_impact": {"score": 70, "evidence": "说明业务影响"},
            "complexity": {"score": 60, "evidence": "描述系统复杂度"},
            "ownership": {"score": 75, "evidence": "明确个人负责范围"},
            "consistency": {"score": 95, "evidence": "时间与技能自洽"},
        }
    )

    assert score == 81
    assert breakdown["achievement_evidence"] == {
        "label": "成果证据",
        "score": 93,
        "weight": 25,
        "evidence": "包含三项量化成果",
    }


def test_resume_score_normalization_clamps_values_and_caps_missing_evidence():
    raw = _score_breakdown(70)
    raw["content_completeness"]["score"] = 130
    raw["achievement_evidence"] = {"score": "88", "evidence": []}

    score, breakdown = _normalize_resume_score_breakdown(raw)

    assert breakdown["content_completeness"]["score"] == 100
    assert breakdown["achievement_evidence"]["score"] == 70
    assert breakdown["achievement_evidence"]["evidence"] == "简历未提供可核验依据"
    assert score == 76


def test_resume_score_requires_every_dimension():
    with pytest.raises(ValueError, match="缺少维度"):
        _normalize_resume_score_breakdown({"content_completeness": {"score": 80, "evidence": "章节完整"}})


def test_job_profile_summary_cleaning_preserves_meaningful_leading_number():
    assert JobProfileSummaryTool._clean_summary("6年研发经验，近3年聚焦大模型应用开发。") == (
        "6年研发经验，近3年聚焦大模型应用开发。"
    )
    assert JobProfileSummaryTool._clean_summary("1. 具备6年研发经验。") == "具备6年研发经验。"


def test_resume_match_schema_declares_evaluation_modes():
    schema = ResumeMatchTool.input_schema["properties"]["evaluation_mode"]

    assert schema["enum"] == ["recommendation_list", "full_jd_analysis"]
    assert schema["default"] == "full_jd_analysis"


@pytest.mark.asyncio
async def test_job_profile_summary_preserves_complete_content_beyond_220_characters(workspace):
    long_summary = (
        "具备六年研发经验，当前聚焦大模型应用与智能体平台研发，能够承担需求分析、架构设计、核心研发和生产交付。"
        "熟悉Java、Python、Spring Boot、FastAPI、RAG、LangGraph、MCP与Agent Runtime等技术体系。"
        "主导多个智能问答平台和智能体应用从零到一落地，具备跨团队协作、复杂问题排查和工程质量治理经验。"
        "目标岗位为Agent研发工程师或AI应用研发工程师，期望在上海从事大模型平台与智能体方向研发。"
        "能够结合业务目标完成技术选型、方案拆解、研发推进、上线验证和持续迭代，并重视可观测性与稳定性建设。"
        "硬性排除项为外包、劳务派遣和驻场。"
    )
    payload = {
        "summary": long_summary,
        "highlights": ["六年研发经验", "智能体平台"],
        "missing_fields": ["期望薪资"],
    }
    stub = _StubLLM(content=json.dumps(payload, ensure_ascii=False))
    tool = JobProfileSummaryTool(llm_client=stub)

    call = ToolCall(id="profile-summary", name="job_profile_summary", arguments={"profile": {"name": "胡军"}})
    result = await tool.safe_run(call, _context(workspace))

    assert len(long_summary) > 220
    assert result.success is True
    assert result.output["summary"] == long_summary
    assert result.output["summary"].endswith("硬性排除项为外包、劳务派遣和驻场。")
    assert stub.calls[0]["max_tokens"] == resume_tools.MAX_PROFILE_SUMMARY_TOKENS
    system_prompt = stub.calls[0]["messages"][0].content
    assert "使用完整主谓结构和自然衔接" in system_prompt
    assert "工作年限必须保留明确数字或范围" in system_prompt


@pytest.mark.asyncio
async def test_resume_match_sorts_and_clamps_scores_with_evidence(workspace):
    resume = {
        "summary": "上海 Java 大模型应用开发 5 年",
        "years_experience": 5,
        "skills": ["Java", "Spring Boot", "Spring AI"],
    }
    jobs = [
        {"securityId": "j1", "jobName": "Java 大模型应用开发", "skills": ["Java", "Spring AI"], "salaryDesc": "40-50K"},
        {"securityId": "j2", "jobName": "Java 大数据平台开发", "skills": ["Java", "Flink"], "salaryDesc": "40-50K"},
    ]

    llm_response = {
        "evaluation_schema": "evidence_based_resume_job_match_v4",
        "matches": [
            {
                "id": "j1",
                "score": 88,
                "score_confidence": "high",
                "evidence": [
                    {
                        "resume_evidence": "上海 Java 大模型应用开发 5 年",
                        "job_requirement": "Java、Spring AI",
                        "assessment": "技术栈匹配",
                    }
                ],
                "hits": ["技术栈匹配"],
                "gaps": [],
                "reasoning": "对口",
                "recommendation": "推荐",
            },
            {
                "id": "j2",
                "score": 150,
                "score_confidence": "medium",
                "evidence": [
                    {"resume_evidence": "缺少大数据平台经历", "job_requirement": "Flink", "assessment": "不匹配"}
                ],
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
    assert result.output["evaluation_schema"] == "evidence_based_resume_job_match_v4"
    assert set(matches[1]["dimensions"]) == {
        "technical_skill",
        "seniority",
        "education_fit",
        "project_relevance",
        "domain_fit",
        "constraints",
    }
    assert result.output["scored_count"] == 2
    assert stub.calls[0]["disable_thinking"] is True
    system_prompt = stub.calls[0]["messages"][0].content
    assert "是否值得投递" in system_prompt
    assert "投递前可执行动作" in system_prompt
    assert "具体追问方向" in system_prompt
    assert "education_fit" in system_prompt
    assert "上述六个维度" in system_prompt
    assert "education_fit.score 必须输出 0-100 整数" in system_prompt
    assert "专业名称不要求严格一致" in system_prompt
    assert "相近专业、交叉学科" in system_prompt
    assert "与匹配分高低、推荐结果和差距数量无关" in system_prompt
    assert "不得仅因存在技能差距、风险项或不建议投递就输出 low" in system_prompt


@pytest.mark.asyncio
async def test_resume_match_uses_structured_list_metadata_as_prefilter_evidence(workspace):
    stub = _StubLLM(
        content=json.dumps(
            {
                "matches": [
                    {
                        "id": "j1",
                        "score": 65,
                        "score_confidence": "low",
                        "recommendation": "证据不足",
                        "evidence": [
                            {
                                "resume_evidence": "6 年 Python、LangGraph 与大模型应用开发经验",
                                "job_requirement": "大模型应用开发、Python、5-10 年",
                                "assessment": "方向、技能和年限均有列表证据支持",
                            }
                        ],
                        "limitations": [],
                    }
                ]
            },
            ensure_ascii=False,
        )
    )
    tool = ResumeMatchTool(llm_client=stub)

    result = await tool.safe_run(
        ToolCall(
            id="recommendation-list-evidence",
            name="resume_match",
            arguments={
                "evaluation_mode": "recommendation_list",
                "resume": {
                    "summary": "6 年 Python、LangGraph 与大模型应用开发经验",
                    "years_experience": 6,
                    "skills": ["Python", "LangGraph"],
                },
                "jobs": [
                    {
                        "securityId": "j1",
                        "jobName": "大模型应用开发工程师",
                        "skills": ["Python", "LangGraph"],
                        "jobLabels": ["大模型", "Agent"],
                        "jobExperience": "5-10年",
                        "jobDegree": "本科",
                        "cityName": "上海",
                        "salaryDesc": "40-50K",
                    }
                ],
            },
        ),
        _context(workspace),
    )

    assert result.success is True
    match = result.output["matches"][0]
    assert match["score_confidence"] == "medium"
    assert match["recommendation"] == "可尝试"
    assert "缺少完整岗位描述，当前结论仅用于列表预筛。" in match["limitations"]
    assert set(match) == {"id", *resume_tools.RECOMMENDATION_LIST_MATCH_FIELDS}
    assert result.output["evaluation_mode"] == "recommendation_list"
    assert result.output["evidence_policy"] == "recommendation_list_structured_metadata_prefilter"
    system_prompt = stub.calls[0]["messages"][0].content
    assert "结构化列表元数据共同构成合法的预筛证据" in system_prompt
    assert "列表预筛的置信度上限也是 medium" in system_prompt
    assert "每个输入 id 恰好返回一次" in system_prompt
    assert "evidence 最多 1 项" in system_prompt
    assert "dimensions" not in system_prompt
    assert "risks" not in system_prompt
    assert "interview_focus" not in system_prompt
    assert "improvement_actions" not in system_prompt
    assert stub.calls[0]["max_tokens"] == resume_tools.MAX_RECOMMENDATION_LIST_MATCH_TOKENS
    user_payload = json.loads(stub.calls[0]["messages"][1].content)
    assert user_payload["evaluation_mode"] == "recommendation_list"


@pytest.mark.asyncio
async def test_resume_match_compacts_recommendation_list_output(workspace):
    repeated = "这是需要截断的列表预筛内容" * 12
    stub = _StubLLM(
        content=json.dumps(
            {
                "matches": [
                    {
                        "id": "j1",
                        "score": 72,
                        "score_confidence": "medium",
                        "recommendation": "可尝试",
                        "reasoning": repeated,
                        "evidence": [
                            {
                                "resume_evidence": repeated,
                                "job_requirement": repeated,
                                "assessment": repeated,
                            },
                            {
                                "resume_evidence": "第二条证据",
                                "job_requirement": "第二条要求",
                                "assessment": "第二条判断",
                            },
                        ],
                        "hits": [repeated, "第二个命中点"],
                        "gaps": [repeated, "第二个差距"],
                        "limitations": [repeated, "第二个限制"],
                        "dimensions": {"technical_skill": {"score": 72}},
                        "risks": ["不应进入列表结果"],
                        "interview_focus": ["不应进入列表结果"],
                    }
                ]
            },
            ensure_ascii=False,
        )
    )
    tool = ResumeMatchTool(llm_client=stub)

    result = await tool.safe_run(
        ToolCall(
            id="compact-recommendation-list",
            name="resume_match",
            arguments={
                "evaluation_mode": "recommendation_list",
                "resume": {"skills": ["Python"]},
                "jobs": [{"securityId": "j1", "jobName": "Python 工程师", "skills": ["Python"]}],
            },
        ),
        _context(workspace),
    )

    assert result.success is True
    match = result.output["matches"][0]
    assert set(match) == {"id", *resume_tools.RECOMMENDATION_LIST_MATCH_FIELDS}
    assert len(match["reasoning"]) == resume_tools.RECOMMENDATION_LIST_REASONING_CHARS
    assert len(match["evidence"]) == 1
    assert all(len(value) <= resume_tools.RECOMMENDATION_LIST_EVIDENCE_CHARS for value in match["evidence"][0].values())
    assert len(match["hits"]) == 1
    assert len(match["hits"][0]) == resume_tools.RECOMMENDATION_LIST_ITEM_CHARS
    assert len(match["gaps"]) == 1
    assert len(match["gaps"][0]) == resume_tools.RECOMMENDATION_LIST_ITEM_CHARS
    assert len(match["limitations"]) == 1
    assert len(match["limitations"][0]) <= resume_tools.RECOMMENDATION_LIST_LIMITATION_CHARS


@pytest.mark.asyncio
async def test_resume_match_caps_recommendation_list_confidence_at_medium(workspace):
    stub = _StubLLM(
        content=json.dumps(
            {
                "matches": [
                    {
                        "id": "j1",
                        "score": 84,
                        "score_confidence": "high",
                        "recommendation": "推荐",
                        "evidence": [
                            {
                                "resume_evidence": "具备 6 年 Java 开发经验",
                                "job_requirement": "Java、5-10 年",
                                "assessment": "技能与年限匹配",
                            }
                        ],
                    }
                ]
            },
            ensure_ascii=False,
        )
    )
    tool = ResumeMatchTool(llm_client=stub)

    result = await tool.safe_run(
        ToolCall(
            id="recommendation-list-confidence-cap",
            name="resume_match",
            arguments={
                "evaluation_mode": "recommendation_list",
                "resume": {"summary": "6 年 Java 开发经验", "skills": ["Java"]},
                "jobs": [
                    {
                        "securityId": "j1",
                        "jobName": "Java 工程师",
                        "skills": ["Java"],
                        "jobExperience": "5-10年",
                    }
                ],
            },
        ),
        _context(workspace),
    )

    assert result.success is True
    assert result.output["matches"][0]["score_confidence"] == "medium"


@pytest.mark.asyncio
async def test_resume_match_keeps_title_only_job_fail_closed(workspace):
    stub = _StubLLM(
        content=json.dumps(
            {
                "matches": [
                    {
                        "id": "j1",
                        "score": 75,
                        "score_confidence": "high",
                        "recommendation": "推荐",
                        "evidence": [
                            {
                                "resume_evidence": "具备大模型应用经验",
                                "job_requirement": "岗位名称为大模型应用工程师",
                                "assessment": "方向相似",
                            }
                        ],
                    }
                ]
            },
            ensure_ascii=False,
        )
    )
    tool = ResumeMatchTool(llm_client=stub)

    result = await tool.safe_run(
        ToolCall(
            id="title-only-fail-closed",
            name="resume_match",
            arguments={
                "evaluation_mode": "recommendation_list",
                "resume": {"summary": "具备大模型应用经验"},
                "jobs": [{"securityId": "j1", "jobName": "大模型应用工程师"}],
            },
        ),
        _context(workspace),
    )

    assert result.success is True
    assert result.output["matches"][0]["score_confidence"] == "low"
    assert result.output["matches"][0]["recommendation"] == "证据不足"


@pytest.mark.asyncio
async def test_resume_match_recalibrates_confidence_from_grounded_evidence(workspace):
    evidence = [
        {
            "resume_evidence": f"简历证据 {index}",
            "job_requirement": f"岗位要求 {index}",
            "assessment": "证据可核验",
        }
        for index in range(1, 4)
    ]
    stub = _StubLLM(
        content=json.dumps(
            {
                "matches": [
                    {
                        "id": "j1",
                        "score": 78,
                        "score_confidence": "low",
                        "recommendation": "可尝试",
                        "evidence": evidence,
                    }
                ]
            },
            ensure_ascii=False,
        )
    )
    tool = ResumeMatchTool(llm_client=stub)

    result = await tool.safe_run(
        ToolCall(
            id="confidence-calibration",
            name="resume_match",
            arguments={
                "resume": {"summary": "6 年 Agent 与 RAG 工程经验", "skills": ["Python", "LangGraph"]},
                "jobs": [
                    {
                        "securityId": "j1",
                        "jobName": "Agent 工程师",
                        "jobDescription": "负责 Agent 平台架构、RAG 检索链路、工具治理和工程交付，要求具备完整项目落地经验。",
                    }
                ],
            },
        ),
        _context(workspace),
    )

    assert result.success is True
    assert result.output["matches"][0]["score_confidence"] == "high"


@pytest.mark.asyncio
async def test_resume_match_upgrades_low_confidence_to_medium_with_partial_grounding(workspace):
    stub = _StubLLM(
        content=json.dumps(
            {
                "matches": [
                    {
                        "id": "j1",
                        "score": 65,
                        "score_confidence": "low",
                        "recommendation": "谨慎",
                        "evidence": [
                            {
                                "resume_evidence": "具备 Python 项目经验",
                                "job_requirement": "熟悉 Python",
                                "assessment": "匹配",
                            }
                        ],
                    }
                ]
            },
            ensure_ascii=False,
        )
    )
    tool = ResumeMatchTool(llm_client=stub)

    result = await tool.safe_run(
        ToolCall(
            id="partial-confidence-calibration",
            name="resume_match",
            arguments={
                "evaluation_mode": "full_jd_analysis",
                "resume": {"skills": ["Python"]},
                "jobs": [{"securityId": "j1", "jobName": "Python 工程师", "skills": ["Python"]}],
            },
        ),
        _context(workspace),
    )

    assert result.success is True
    assert result.output["matches"][0]["score_confidence"] == "medium"
    assert result.output["matches"][0]["recommendation"] == "谨慎"


@pytest.mark.asyncio
async def test_resume_match_accepts_single_match_object(workspace):
    stub = _StubLLM(
        content=json.dumps(
            {
                "evaluation_schema": "evidence_based_resume_job_match_v4",
                "matches": {
                    "id": "j1",
                    "score": 82,
                    "score_confidence": "low",
                    "recommendation": "推荐",
                    "reasoning": "岗位要求与简历证据充分对应。",
                    "evidence": [
                        {
                            "resume_evidence": "负责 Agent 平台落地",
                            "job_requirement": "具备 Agent 工程经验",
                            "assessment": "匹配",
                        },
                        {
                            "resume_evidence": "负责 RAG 检索链路",
                            "job_requirement": "具备 RAG 项目经验",
                            "assessment": "匹配",
                        },
                        {
                            "resume_evidence": "使用 Python 开发服务",
                            "job_requirement": "熟悉 Python",
                            "assessment": "匹配",
                        },
                    ],
                    "hits": ["Agent、RAG 与 Python 均有项目证据"],
                    "gaps": [],
                },
                "limitations": [],
            },
            ensure_ascii=False,
        )
    )
    tool = ResumeMatchTool(llm_client=stub)

    result = await tool.safe_run(
        ToolCall(
            id="single-match-object",
            name="resume_match",
            arguments={
                "resume": {"summary": "6 年研发经验"},
                "jobs": [
                    {
                        "securityId": "j1",
                        "jobName": "大模型应用研发",
                        "jobDescription": "负责 Agent 与 RAG 平台开发，要求熟悉 Python 并具备完整工程落地经验。",
                    }
                ],
                "sections": [
                    "score",
                    "score_confidence",
                    "recommendation",
                    "reasoning",
                    "evidence",
                    "hits",
                    "gaps",
                ],
            },
        ),
        _context(workspace),
    )

    assert result.success is True
    assert result.output["matches"][0]["score_confidence"] == "high"
    assert result.output["scored_count"] == 1


@pytest.mark.asyncio
async def test_resume_match_returns_only_requested_partial_fields(workspace):
    stub = _StubLLM(
        content=json.dumps(
            {
                "matches": [
                    {
                        "id": "j1",
                        "dimensions": {"technical_skill": {"score": 80}},
                        "risks": ["量化证据不足"],
                        "score": 90,
                    }
                ]
            },
            ensure_ascii=False,
        )
    )
    tool = ResumeMatchTool(llm_client=stub)

    result = await tool.safe_run(
        ToolCall(
            id="partial-match",
            name="resume_match",
            arguments={
                "resume": {"skills": ["Java"]},
                "jobs": [{"securityId": "j1", "jobName": "Java 工程师", "jobDescription": "负责 Java 平台"}],
                "sections": ["dimensions", "risks"],
            },
        ),
        _context(workspace),
    )

    row = result.output["matches"][0]
    assert set(row) == {"id", "dimensions", "risks"}
    assert set(row["dimensions"]) == {
        "technical_skill",
        "seniority",
        "education_fit",
        "project_relevance",
        "domain_fit",
        "constraints",
    }
    assert row["dimensions"]["education_fit"]["score"] == 50
    assert row["dimensions"]["education_fit"]["gap"] == "学历、专业或资质证据不足，当前按保守中性分评估"
    assert "score" not in row
    assert "本次每个 match 只生成" in stub.calls[0]["messages"][0].content


def test_resume_match_compacts_detailed_work_and_project_evidence():
    compacted = ResumeMatchTool._compact_resume(
        {
            "personal_advantage": "具备 Agent 平台交付经验",
            "work_experiences": [
                {
                    "companyName": "示例科技",
                    "positionName": "高级研发工程师",
                    "workContent": "负责 RAG 与 Agent 平台架构设计",
                    "achievement": "接口延迟降低 35%",
                }
            ],
            "project_experiences": [
                {
                    "projectName": "智能问答平台",
                    "role": "技术负责人",
                    "techStack": ["Python", "LangGraph", "Milvus"],
                    "responsibility": "负责检索链路与评测体系",
                    "achievement": "答案命中率提升 20%",
                }
            ],
            "education": [
                {
                    "schoolName": "示例大学",
                    "degreeName": "本科",
                    "majorName": "计算机科学与技术",
                    "dateRange": "2013-2017",
                }
            ],
        }
    )

    assert compacted["summary"] == "具备 Agent 平台交付经验"
    assert compacted["work_experiences"][0]["title"] == "高级研发工程师"
    assert compacted["work_experiences"][0]["description"] == "负责 RAG 与 Agent 平台架构设计"
    assert compacted["project_experiences"][0]["skills"] == ["Python", "LangGraph", "Milvus"]
    assert compacted["project_experiences"][0]["achievement"] == "答案命中率提升 20%"
    assert compacted["education"][0] == {
        "school": "示例大学",
        "degree": "本科",
        "major": "计算机科学与技术",
        "period": "2013-2017",
    }


@pytest.mark.asyncio
async def test_resume_match_rejects_empty_model_matches(workspace):
    tool = ResumeMatchTool(llm_client=_StubLLM(content=json.dumps({"matches": []})))
    call = ToolCall(
        id="empty-matches",
        name="resume_match",
        arguments={"resume": {"skills": ["Java"]}, "jobs": [{"jobName": "Java 工程师"}]},
    )

    result = await tool.safe_run(call, _context(workspace))

    assert result.success is False
    assert "岗位匹配结果不完整" in result.error
    assert "未返回有效" in result.error


@pytest.mark.asyncio
async def test_resume_match_rejects_partial_model_results_with_missing_ids(workspace):
    tool = ResumeMatchTool(
        llm_client=_StubLLM(
            content=json.dumps(
                {
                    "matches": [
                        {
                            "id": "j1",
                            "score": 80,
                            "score_confidence": "medium",
                            "recommendation": "可尝试",
                        }
                    ]
                },
                ensure_ascii=False,
            )
        )
    )
    call = ToolCall(
        id="partial-model-results",
        name="resume_match",
        arguments={
            "evaluation_mode": "recommendation_list",
            "resume": {"skills": ["Java"]},
            "jobs": [
                {"securityId": "j1", "jobName": "Java 工程师", "skills": ["Java"]},
                {"securityId": "j2", "jobName": "后端工程师", "skills": ["Java"]},
            ],
        },
    )

    result = await tool.safe_run(call, _context(workspace))

    assert result.success is False
    assert "岗位匹配结果不完整" in result.error
    assert "missing_ids=['j2']" in result.error
    assert "expected_count=2" in result.error
    assert "returned_count=1" in result.error


@pytest.mark.parametrize(
    ("rows", "diagnostic"),
    [
        ([{"id": "j1"}, {"id": "unknown"}], "unknown_ids=['unknown']"),
        ([{"id": "j1"}, {"id": "j1"}], "duplicate_ids=['j1']"),
    ],
)
def test_resume_match_alignment_reports_invalid_model_ids(rows, diagnostic):
    with pytest.raises(ValueError, match="岗位匹配结果不完整") as exc_info:
        ResumeMatchTool._align_match_rows(rows, [{"id": "j1"}, {"id": "j2"}])

    assert diagnostic in str(exc_info.value)


@pytest.mark.asyncio
async def test_resume_match_rejects_missing_id_in_full_jd_analysis(workspace):
    resume = {"skills": ["Python"]}
    jobs = [{"jobName": "Python 工程师", "skills": ["Python"]}]
    llm_response = {"matches": [{"score": 70}]}
    tool = ResumeMatchTool(llm_client=_StubLLM(content=json.dumps(llm_response)))

    call = ToolCall(id="c6", name="resume_match", arguments={"resume": resume, "jobs": jobs})
    result = await tool.safe_run(call, _context(workspace))

    assert result.success is False
    assert "岗位匹配结果不完整" in result.error
    assert "missing_ids=['job_0']" in result.error
    assert "missing_id_indexes=[0]" in result.error


@pytest.mark.parametrize(
    ("rows", "missing_id"),
    [
        ([{"id": "j1", "score": 70}, {"score": 65}], "j2"),
        ([{"id": "j2", "score": 70}, {"score": 65}], "j1"),
    ],
)
def test_resume_match_alignment_rejects_missing_id_in_multi_row_results(rows, missing_id):
    with pytest.raises(ValueError, match="岗位匹配结果不完整") as exc_info:
        ResumeMatchTool._align_match_rows(rows, [{"id": "j1"}, {"id": "j2"}])

    error = str(exc_info.value)
    assert f"missing_ids=['{missing_id}']" in error
    assert "missing_id_indexes=[1]" in error


@pytest.mark.asyncio
async def test_resume_match_rejects_empty_jobs(workspace):
    tool = ResumeMatchTool(llm_client=_StubLLM(content="[]"))
    call = ToolCall(id="c7", name="resume_match", arguments={"resume": {}, "jobs": []})
    result = await tool.safe_run(call, _context(workspace))
    assert result.success is False
    assert "非空" in result.error
