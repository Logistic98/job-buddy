from app.grader import _event_order_issues, grade_latency, grade_run, grade_trace

FULL_EVENTS = ["run_start", "understand_goal", "task_understanding", "capability_route", "finalize", "run_end"]


def _run(**overrides):
    run = {
        "status": "success",
        "answer": "已完成，结果如上。",
        "directive": {
            "domain": "open_domain",
            "intent": "general.chat",
            "router": "llm",
            "confidence": 0.9,
            "next_action": "run_runtime_planner",
        },
        "trace_events": [{"event": event} for event in FULL_EVENTS],
        "tool_events": [{"id": "t1", "status": "success"}],
    }
    run.update(overrides)
    return run


# ---- 速度评分边界 ----


def test_latency_full_score_within_target():
    result = grade_latency(
        {"ttft_ms": 1500, "done_ms": 5000},
        {"ttft_ms_target": 2000, "ttft_ms_max": 4000, "done_ms_target": 6000, "done_ms_max": 10000},
    )
    assert result["passed"] is True
    assert result["score"] == 1.0


def test_latency_zero_score_at_or_beyond_max():
    result = grade_latency({"ttft_ms": 4000}, {"ttft_ms_target": 2000, "ttft_ms_max": 4000})
    assert result["score"] == 0.0
    assert result["passed"] is False


def test_latency_linear_decay_between_target_and_max():
    result = grade_latency({"ttft_ms": 3000}, {"ttft_ms_target": 2000, "ttft_ms_max": 4000})
    assert abs(result["score"] - 0.5) < 1e-6


def test_latency_missing_metric_scores_zero_with_budget():
    result = grade_latency({}, {"ttft_ms_target": 2000, "ttft_ms_max": 4000})
    assert result["score"] == 0.0
    assert any(issue["code"] == "ttft_ms_measured" for issue in result["issues"])


def test_latency_illegal_metric_value_scores_zero():
    result = grade_latency({"ttft_ms": "abc"}, {"ttft_ms_target": 2000})
    assert result["score"] == 0.0


def test_latency_no_budget_means_pass():
    result = grade_latency({"ttft_ms": 99999}, {})
    assert result["passed"] is True
    assert result["score"] == 1.0


def test_latency_target_only_penalizes_to_double_target():
    at_double = grade_latency({"ttft_ms": 4000}, {"ttft_ms_target": 2000})
    within = grade_latency({"ttft_ms": 1999}, {"ttft_ms_target": 2000})
    assert at_double["score"] == 0.0
    assert within["score"] == 1.0


# ---- 事件顺序与 trace 边界 ----


def test_event_order_issue_detected_when_finalize_before_capability_route():
    issues = _event_order_issues(
        ["run_start", "understand_goal", "task_understanding", "finalize", "capability_route", "run_end"]
    )
    assert "capability_route_after_finalize" in issues


def test_grade_trace_penalizes_out_of_order_events():
    trace = [
        {"event": e}
        for e in ["run_start", "understand_goal", "task_understanding", "finalize", "capability_route", "run_end"]
    ]
    result = grade_trace(trace)
    assert result["passed"] is False
    assert result["order_issues"]


def test_grade_trace_empty_trace_uses_backend_node_contract():
    result = grade_trace([])
    assert result["passed"] is False
    assert result["missing_nodes"]


# ---- 工具执行维度 ----


def test_stuck_running_tool_event_is_flagged():
    result = grade_run(_run(tool_events=[{"id": "t1", "status": "running"}]), {})
    assert any(issue["code"] == "no_stuck_running_events" for issue in result["issues"])


def test_tool_failure_without_explanation_is_flagged():
    result = grade_run(_run(tool_events=[{"id": "t1", "status": "failed"}], answer="全部完成。"), {})
    assert any(issue["code"] == "tool_failure_accounted" for issue in result["issues"])


def test_tool_failure_with_explanation_passes():
    result = grade_run(_run(tool_events=[{"id": "t1", "status": "failed"}], answer="岗位搜索失败，请检查登录态。"), {})
    assert not any(issue["code"] == "tool_failure_accounted" for issue in result["issues"])


def test_missing_tool_events_downgrades_but_not_fatal():
    result = grade_run(_run(tool_events=[]), {})
    assert any(issue["code"] == "tool_events_missing" for issue in result["issues"])
    assert not any(issue["severity"] == "critical" for issue in result["issues"])


# ---- 安全与运行状态一致性 ----


def test_disallow_boss_flags_boss_side_effect():
    result = grade_run(_run(tool_results=[{"tool_name": "boss_browser", "success": True}]), {"disallow_boss": True})
    assert any(issue["code"] == "no_boss_side_effect" for issue in result["issues"])
    assert result["passed"] is False


def test_disallow_boss_passes_without_boss_traces():
    result = grade_run(_run(), {"disallow_boss": True})
    assert not any(issue["code"] == "no_boss_side_effect" for issue in result["issues"])


def test_failure_answer_marked_success_is_fatal():
    run = _run(answer="下游调用失败，已完成本次请求。", status="success")
    run["stop_reason"] = "tool_execution_failed"
    result = grade_run(run, {})
    assert any(issue["code"] == "failure_not_marked_success" for issue in result["issues"])
    assert result["passed"] is False


def test_failed_run_end_trace_cannot_be_marked_success():
    run = _run(status="success")
    run["trace_events"][-1]["payload"] = {
        "status": "fail",
        "stop_reason": "runtime_error",
    }

    result = grade_run(run, {})

    assert any(issue["code"] == "failure_not_marked_success" for issue in result["issues"])
    assert result["passed"] is False


def test_min_score_threshold_is_respected():
    run = _run(tool_events=[])
    lenient = grade_run(run, {"min_score": 0.5})
    strict = grade_run(run, {"min_score": 0.99})
    assert lenient["passed"] is True
    assert strict["passed"] is False


def test_missing_capability_inventory_is_critical():
    from app.grader import grade_capability_inventory

    result = grade_capability_inventory({})
    assert result["passed"] is False
    assert any(issue["code"] == "capabilities_present" for issue in result["issues"])


def test_nested_fake_source_is_not_accepted_as_grounding():
    result = grade_run(_run(tool_results=[{"metadata": {"source": "synthetic"}}]), {})
    issue = next(issue for issue in result["issues"] if issue["code"] == "no_fixture_or_mock_claims")
    assert "fake_source_field" in issue["evidence"]
    assert result["passed"] is False


def test_legacy_quality_counts_in_message_tool_events_remain_supported():
    run = _run(
        messages=[
            {
                "toolEvents": [
                    {
                        "id": "recommendation_quality_gate",
                        "detail": {
                            "candidateCount": 3,
                            "scoredCount": 2,
                            "unscoredCount": 1,
                        },
                    }
                ]
            }
        ],
        job_cards=["malformed"],
    )
    result = grade_run(run, {"require_complete_recommendation_scoring": True})
    issue = next(issue for issue in result["issues"] if issue["code"] == "job_recommendation_scoring_is_complete")
    assert issue["severity"] == "critical"
    assert result["passed"] is False


def test_maximum_only_latency_budget_passes_below_limit():
    assert grade_latency({"ttft_ms": 5}, {"ttft_ms_max": 10})["score"] == 1


def test_actual_evidence_counts_populated_objects_and_nonblank_text():
    from app.grader import _actual_evidence_count

    assert _actual_evidence_count({"evidence": [{"fact": "verified"}, {"fact": ""}, "verified", " ", None]}) == 2
