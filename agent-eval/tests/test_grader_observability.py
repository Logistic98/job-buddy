from app.grader import _grade_observability_dimension, grade_run

FULL_EVENTS = ["run_start", "understand_goal", "task_understanding", "capability_route", "finalize", "run_end"]


def _base_run(extra_trace=None):
    trace = [{"event": event, "payload": {}} for event in FULL_EVENTS]
    if extra_trace:
        trace[-1:-1] = extra_trace
    return {
        "status": "success",
        "answer": "已完成，输出结果如上。",
        "directive": {"intent": "technical_qa", "domain": "open_domain", "router": "llm", "confidence": 0.9, "next_action": "run_runtime_planner"},
        "trace_events": trace,
        "tool_events": [{"status": "success", "tool": "demo_tool"}],
    }


def test_observability_ignores_trace_without_new_events():
    checks = _grade_observability_dimension(_base_run(), {})
    assert checks == []


def test_tool_execute_end_requires_duration_and_results():
    run = _base_run([
        {"event": "tool_execute_end", "payload": {"success": True, "duration_ms": 12, "results": [{"tool": "demo_tool", "success": True, "duration_ms": 12}]}},
    ])
    checks = _grade_observability_dimension(run, {})
    assert {c["code"] for c in checks} == {"tool_end_has_duration", "tool_end_has_per_tool_results"}
    assert all(c["score"] == 1.0 for c in checks)


def test_tool_execute_end_missing_duration_is_flagged():
    run = _base_run([
        {"event": "tool_execute_end", "payload": {"success": True}},
    ])
    checks = {c["code"]: c for c in _grade_observability_dimension(run, {})}
    assert checks["tool_end_has_duration"]["score"] == 0.0
    assert checks["tool_end_has_per_tool_results"]["score"] == 0.0


def test_tool_execute_failed_requires_tool_and_error():
    ok_run = _base_run([
        {"event": "tool_execute_failed", "payload": {"tool": "broken_tool", "error": "上游超时", "duration_ms": 30, "permission_denied": False}},
    ])
    bad_run = _base_run([
        {"event": "tool_execute_failed", "payload": {"tool": "broken_tool"}},
    ])
    ok = {c["code"]: c for c in _grade_observability_dimension(ok_run, {})}
    bad = {c["code"]: c for c in _grade_observability_dimension(bad_run, {})}
    assert ok["tool_failed_has_context"]["score"] == 1.0
    assert bad["tool_failed_has_context"]["score"] == 0.0


def test_llm_usage_payload_requires_calls_and_tokens():
    ok_run = _base_run([
        {"event": "llm_usage", "payload": {"llm_calls": 2, "prompt_tokens": 100, "completion_tokens": 40, "total_tokens": 140}},
    ])
    bad_run = _base_run([
        {"event": "llm_usage", "payload": {"prompt_tokens": 100}},
    ])
    ok = {c["code"]: c for c in _grade_observability_dimension(ok_run, {})}
    bad = {c["code"]: c for c in _grade_observability_dimension(bad_run, {})}
    assert ok["llm_usage_has_tokens"]["score"] == 1.0
    assert bad["llm_usage_has_tokens"]["score"] == 0.0


def test_expect_llm_usage_requires_event_presence():
    missing = {c["code"]: c for c in _grade_observability_dimension(_base_run(), {"expect_llm_usage": True})}
    assert missing["llm_usage_present"]["score"] == 0.0

    present_run = _base_run([
        {"event": "llm_usage", "payload": {"llm_calls": 1, "total_tokens": 80}},
    ])
    present = {c["code"]: c for c in _grade_observability_dimension(present_run, {"expect_llm_usage": True})}
    assert present["llm_usage_present"]["score"] == 1.0


def test_grade_run_includes_observability_dimension_when_events_present():
    run = _base_run([
        {"event": "llm_usage", "payload": {"llm_calls": 1, "total_tokens": 80}},
    ])
    result = grade_run(run, {"expect_llm_usage": True})
    assert "observability" in result["dimensions"]
    assert result["dimensions"]["observability"]["score"] == 1.0


def test_grade_run_backward_compatible_without_new_events():
    result = grade_run(_base_run(), {})
    assert "observability" not in result["dimensions"]
