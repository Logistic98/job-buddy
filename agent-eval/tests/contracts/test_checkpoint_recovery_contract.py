from app.grader import grade_run


def test_checkpoint_resume_requires_lineage_and_resume_trace():
    run = {
        "run_id": "run-resumed",
        "trace_id": "trace-resumed",
        "status": "success",
        "stop_reason": "task_complete",
        "answer": "恢复完成",
        "resumed_from_run_id": "run-source",
        "resumed_from_stage": "execute_tool",
        "directive": {"next_action": "run_runtime_planner"},
        "trace_events": [
            {"event": "run_start"},
            {"event": "run_resumed", "payload": {"source_run_id": "run-source", "source_stage": "execute_tool"}},
            {"event": "finalize"},
            {"event": "run_end", "status": "success"},
        ],
        "tool_events": [{"id": "runtime_managed", "status": "success"}],
    }

    result = grade_run(run, {"checkpoint_resume": True, "answer_min_chars": 2, "min_score": 0.7})

    issue_codes = {issue["code"] for issue in result["issues"]}
    assert "checkpoint_resume_trace_flow" not in issue_codes
    assert "checkpoint_resume_lineage" not in issue_codes


def test_checkpoint_resume_rejects_reused_run_id_and_missing_stage():
    run = {
        "run_id": "run-source",
        "status": "success",
        "stop_reason": "task_complete",
        "answer": "恢复完成",
        "resumed_from_run_id": "run-source",
        "directive": {"next_action": "run_runtime_planner"},
        "trace_events": [
            {"event": "run_start"},
            {"event": "run_resumed"},
            {"event": "finalize"},
            {"event": "run_end", "status": "success"},
        ],
        "tool_events": [{"id": "runtime_managed", "status": "success"}],
    }

    result = grade_run(run, {"checkpoint_resume": True, "answer_min_chars": 2})

    assert any(issue["code"] == "checkpoint_resume_lineage" for issue in result["issues"])


def test_invalid_plan_replan_requires_safe_cursor_and_planner_trace():
    run = {
        "run_id": "run-replanned",
        "status": "success",
        "stop_reason": "task_complete",
        "answer": "重新规划完成",
        "resumed_from_run_id": "run-invalid-plan",
        "resumed_from_stage": "tool_search",
        "directive": {"next_action": "run_runtime_planner"},
        "trace_events": [
            {"event": "run_start"},
            {"event": "run_resumed"},
            {"event": "plan_created"},
            {"event": "finalize"},
            {"event": "run_end", "status": "success"},
        ],
        "tool_events": [{"id": "runtime_managed", "status": "success"}],
    }

    result = grade_run(
        run,
        {
            "checkpoint_resume": True,
            "checkpoint_replan": True,
            "answer_min_chars": 2,
            "min_score": 0.7,
        },
    )

    issue_codes = {issue["code"] for issue in result["issues"]}
    assert "checkpoint_resume_trace_flow" not in issue_codes
    assert "checkpoint_replan_stage" not in issue_codes
