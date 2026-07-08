from app.grader import grade_capability_inventory, grade_run, grade_trace


def test_grade_trace_passes_when_required_nodes_exist():
    result = grade_trace([{"nodeId": node} for node in ["A", "D1", "E", "F", "Z", "AH"]])
    assert result["passed"] is True
    assert result["score"] == 1.0


def test_grade_trace_passes_when_runtime_events_exist():
    result = grade_trace([
        {"event": event}
        for event in ["run_start", "understand_goal", "task_understanding", "capability_route", "finalize", "run_end"]
    ])
    assert result["passed"] is True
    assert result["score"] == 1.0
    assert result["missing_events"] == []


def test_grade_trace_reports_missing_runtime_events():
    result = grade_trace([{"event": "run_start"}, {"event": "task_understanding"}])
    assert result["passed"] is False
    assert "capability_route" in result["missing_events"]


def test_grade_run_rejects_fixture_and_false_completion():
    run = {
        "status": "success",
        "answer": "Runtime 已完成任务理解，但未返回可展示回答。",
        "directive": {"domain": "job", "intent": "job.recommend", "router": "llm", "confidence": 0.95, "next_action": "call_get_recommend_jobs"},
        "trace_events": [{"event": event} for event in ["run_start", "understand_goal", "task_understanding", "capability_route", "finalize", "run_end"]],
        "tool_events": [{"id": "job_search", "status": "success", "summary": "读取岗位 Fixture"}],
    }

    result = grade_run(run, {"intent": "job.recommend", "domain": "job"})

    assert result["passed"] is False
    assert any(issue["code"] == "no_fixture_or_mock_claims" for issue in result["issues"])


def test_grade_run_accepts_conversation_shortcut_router():
    run = {
        "status": "success",
        "answer": "已为你换一批岗位。",
        "directive": {"domain": "job", "intent": "job.recommend", "router": "semantic_config_shortcut", "confidence": 0.92, "next_action": "call_get_recommend_jobs"},
        "trace_events": [{"event": event} for event in ["run_start", "understand_goal", "task_understanding", "capability_route", "finalize", "run_end"]],
        "tool_events": [{"id": "job_search", "status": "success", "summary": "Boss 实时搜索完成"}],
    }

    result = grade_run(run, {"intent": "job.recommend", "domain": "job"})

    assert not any(issue["code"] == "llm_first" for issue in result["issues"])


def test_grade_capability_inventory_requires_readiness_metadata():
    profile = {
        "capabilities": [
            {
                "id": "resume.match",
                "implementation_status": "partial",
                "execution_intent": "compare_analyze",
                "required_tools": ["resume_match"],
                "evidence_requirements": ["简历", "JD", "证据链"],
            },
            {
                "id": "interview.prepare",
                "implementation_status": "planned",
                "execution_intent": "generate_artifact",
                "implementation_notes": "尚未实现",
                "evidence_requirements": ["简历", "JD"],
            },
        ]
    }

    result = grade_capability_inventory(profile)

    assert result["passed"] is True


def test_grade_capability_inventory_rejects_missing_status():
    result = grade_capability_inventory({"capabilities": [{"id": "interview.prepare", "execution_intent": "generate_artifact"}]})

    assert result["passed"] is False
    assert any(issue["code"] == "interview.prepare:status_present" for issue in result["issues"])


def test_grade_run_requires_injection_flag_when_expected():
    base_run = {
        "status": "success",
        "answer": "已完成岗位搜索。",
        "directive": {"domain": "job", "intent": "job.recommend", "router": "llm", "confidence": 0.95, "next_action": "call_get_recommend_jobs"},
        "trace_events": [{"event": event} for event in ["run_start", "understand_goal", "task_understanding", "capability_route", "finalize", "run_end"]],
    }

    unflagged = dict(base_run, tool_events=[{"id": "job_search", "status": "success", "summary": "完成", "metadata": {}}])
    result = grade_run(unflagged, {"intent": "job.recommend", "domain": "job", "expect_injection_flag": True})
    assert any(issue["code"] == "injection_result_flagged" for issue in result["issues"])

    flagged = dict(base_run, tool_events=[{"id": "job_search", "status": "success", "summary": "完成", "metadata": {"injection_suspected": True, "injection_patterns": ["override_instructions_en"]}}])
    result = grade_run(flagged, {"intent": "job.recommend", "domain": "job", "expect_injection_flag": True})
    assert not any(issue["code"] == "injection_result_flagged" for issue in result["issues"])


def test_grade_run_accepts_evidence_based_resume_match():
    run = {
        "status": "success",
        "answer": "已完成基于岗位证据的简历匹配评估。",
        "directive": {"domain": "job", "intent": "resume.match", "router": "llm", "confidence": 0.91, "next_action": "run_resume_match"},
        "trace_events": [{"event": event} for event in ["run_start", "understand_goal", "task_understanding", "capability_route", "finalize", "run_end"]],
        "tool_events": [{"id": "resume_match", "status": "success", "summary": "完成"}],
        "resume_match": {
            "matches": [
                {
                    "id": "j1",
                    "score": 82,
                    "score_confidence": "medium",
                    "evidence_count": 3,
                    "evidence": [{"resume_evidence": "Agent 项目", "job_requirement": "Agent 应用开发", "assessment": "相关"}],
                }
            ]
        },
    }

    result = grade_run(run, {"intent": "resume.match", "domain": "job", "requires_evidence": True, "min_score": 0.75})

    assert result["passed"] is True
    assert result["dimensions"]["grounding"]["score"] == 1.0
