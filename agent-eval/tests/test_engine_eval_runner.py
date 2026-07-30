import scripts.run_engine_eval as engine_eval_runner
from scripts.run_engine_eval import (
    _build_run,
    _case_payload,
    _effect_checks,
    _evaluate_sample,
    _render_markdown,
    _runtime_headers,
)


def test_case_payload_supports_recent_messages_and_previous_slots():
    messages = [
        {"role": "user", "content": "分析此岗位与当前简历的匹配度"},
        {"role": "assistant", "content": "已完成当前岗位与简历的匹配分析。"},
        {"role": "user", "content": "现在这个6年的简历呢"},
    ]
    selected_job = {"jobName": "AI大模型应用工程师", "company": "美团"}

    payload = _case_payload(
        {
            "id": "resume_switch_reuses_selected_job",
            "input": "现在这个6年的简历呢",
            "messages": messages,
            "metadata": {
                "resume_id": "resume-6-years",
                "previous_slots": {"_selected_job": selected_job},
            },
        }
    )

    assert payload["messages"] == messages
    assert payload["metadata"]["profile"] == "job-buddy"
    assert payload["metadata"]["previous_slots"]["_selected_job"] == selected_job
    assert payload["metadata"]["resume_id"] == "resume-6-years"


def test_case_payload_keeps_single_turn_cases_compatible():
    payload = _case_payload({"id": "technical_qa", "input": "解释 Agent 工具调用幂等"})

    assert payload["messages"] == [{"role": "user", "content": "解释 Agent 工具调用幂等"}]
    assert payload["stream"] is True


def test_case_payload_preserves_validated_hint_fast_path_contract():
    intent_hint = {
        "domain": "job",
        "intent": "resume.match",
        "confidence": 0.88,
        "risk": "low",
        "needs_clarification": False,
        "next_action": "run_resume_match",
        "router": "rule",
    }

    payload = _case_payload(
        {
            "id": "validated_hint_resume_match_understanding",
            "input": "分析当前简历与目标岗位的匹配度",
            "metadata": {"understanding_only": True, "intent_hint": intent_hint},
        }
    )

    assert payload["metadata"]["understanding_only"] is True
    assert payload["metadata"]["intent_hint"] == intent_hint
    assert payload["metadata"]["profile"] == "job-buddy"
    assert payload["stream"] is False


def test_understanding_only_case_uses_nonstream_runtime_endpoint(monkeypatch):
    calls = []

    def nonstream(runtime_url, case, timeout):
        calls.append(("nonstream", runtime_url, case["id"], timeout))
        return {"transport": "nonstream"}

    def stream(runtime_url, case, timeout):
        calls.append(("stream", runtime_url, case["id"], timeout))
        return {"transport": "stream"}

    monkeypatch.setattr(engine_eval_runner, "_nonstream_case", nonstream)
    monkeypatch.setattr(engine_eval_runner, "_stream_case", stream)
    case = {
        "id": "validated_hint_resume_match_understanding",
        "input": "分析当前简历与目标岗位的匹配度",
        "metadata": {"understanding_only": True},
    }

    sample = engine_eval_runner._execute_case("http://127.0.0.1:8010", case, 20.0)

    assert sample == {"transport": "nonstream"}
    assert calls == [
        (
            "nonstream",
            "http://127.0.0.1:8010",
            "validated_hint_resume_match_understanding",
            20.0,
        )
    ]


def test_nonstream_runtime_response_unwraps_standard_envelope():
    data = {
        "status": "success",
        "latency_ms": 14,
        "directive": {"intent": "resume.match"},
    }

    assert engine_eval_runner._unwrap_nonstream_response({"code": 200, "message": "success", "data": data}) == data


def test_runtime_headers_use_internal_service_token_without_exposing_other_env(monkeypatch):
    monkeypatch.setenv("AGENT_INTERNAL_SERVICE_TOKEN", "  test-internal-token  ")
    monkeypatch.setenv("UNRELATED_SECRET", "must-not-be-forwarded")

    assert _runtime_headers() == {"X-Internal-Service-Token": "test-internal-token"}

    monkeypatch.delenv("AGENT_INTERNAL_SERVICE_TOKEN")
    assert _runtime_headers() == {}


def test_attachment_eval_requires_every_declared_file_sentinel():
    case = {
        "expected": {
            "answer_min_chars": 10,
            "answer_contains_all": ["ARCH-417", "CTX-928", "architecture.md"],
        }
    }
    complete = {"answer": "architecture.md 包含 ARCH-417，另一份文件包含 CTX-928。"}
    incomplete = {"answer": "architecture.md 包含 ARCH-417。"}

    assert all(check["passed"] for check in _effect_checks(case, complete, {}))
    failed = [check for check in _effect_checks(case, incomplete, {}) if not check["passed"]]
    assert [check["code"] for check in failed] == ["answer_contains_all"]
    assert failed[0]["detail"]["missing"] == ["CTX-928"]


def test_code_execution_eval_requires_real_sandbox_tool_evidence():
    case = {"expected": {"required_tools": ["sandbox_code_execute"]}}
    valid = _build_run(
        {
            "done": {
                "tool_results": [
                    {
                        "tool_name": "sandbox_code_execute",
                        "success": True,
                        "output": {"sandboxed": True, "exit_code": 0, "stdout": "2\n"},
                    }
                ]
            }
        }
    )
    forged = _build_run(
        {
            "done": {
                "tool_results": [
                    {
                        "tool_name": "sandbox_code_execute",
                        "success": True,
                        "output": {"sandboxed": False, "exit_code": 0, "stdout": "2\n"},
                    }
                ]
            }
        }
    )

    valid_check = next(check for check in _effect_checks(case, valid, {}) if check["code"] == "required_tools")
    forged_check = next(check for check in _effect_checks(case, forged, {}) if check["code"] == "required_tools")

    assert valid_check["passed"] is True
    assert forged_check["passed"] is False
    assert forged_check["detail"]["invalid"] == ["sandbox_code_execute"]


def test_understanding_fast_path_eval_requires_zero_llm_calls():
    case = {"expected": {"expect_no_llm_usage": True}}
    zero_usage = _build_run({"done": {"metrics": {"token_usage": {"llm_calls": 0}}}})
    one_call = _build_run({"done": {"metrics": {"token_usage": {"llm_calls": 1}}}})

    zero_check = next(check for check in _effect_checks(case, zero_usage, {}) if check["code"] == "expect_no_llm_usage")
    one_call_check = next(
        check for check in _effect_checks(case, one_call, {}) if check["code"] == "expect_no_llm_usage"
    )

    assert zero_check["passed"] is True
    assert one_call_check["passed"] is False


def test_web_search_eval_requires_nonempty_referenced_results():
    case = {"expected": {"required_tools": ["web_search"]}}
    valid = _build_run(
        {
            "done": {
                "tool_results": [
                    {
                        "tool_name": "web_search",
                        "success": True,
                        "output": {
                            "results": [
                                {
                                    "title": "OpenAI Models",
                                    "url": "https://openai.com/models",
                                }
                            ]
                        },
                    }
                ]
            }
        }
    )
    empty = _build_run(
        {
            "done": {
                "tool_results": [
                    {
                        "tool_name": "web_search",
                        "success": True,
                        "output": {"results": []},
                    }
                ]
            }
        }
    )

    valid_check = next(check for check in _effect_checks(case, valid, {}) if check["code"] == "required_tools")
    empty_check = next(check for check in _effect_checks(case, empty, {}) if check["code"] == "required_tools")

    assert valid_check["passed"] is True
    assert empty_check["passed"] is False
    assert empty_check["detail"]["invalid"] == ["web_search"]


def test_planner_case_requires_planner_specific_trace_events():
    common_trace = [
        {"event": event}
        for event in [
            "run_start",
            "understand_goal",
            "task_understanding",
            "capability_route",
            "finalize",
            "run_end",
        ]
    ]
    case = {
        "expected": {
            "trace_events": [
                "run_start",
                "understand_goal",
                "task_understanding",
                "capability_route",
                "tool_search",
                "plan_created",
                "finalize",
                "run_end",
            ]
        }
    }
    sample = {
        "done": {
            "status": "success",
            "stop_reason": "task_complete",
            "answer": "已完成。",
            "trace_events": common_trace,
        }
    }

    result = _evaluate_sample(case, sample)

    assert result["process"]["passed"] is False
    assert result["process"]["missing_events"] == ["plan_created", "tool_search"]


def test_process_order_issues_are_preserved_in_result_and_report():
    sample = {
        "done": {
            "status": "success",
            "stop_reason": "task_complete",
            "answer": "已完成。",
            "trace_events": [
                {"event": event}
                for event in [
                    "run_start",
                    "understand_goal",
                    "task_understanding",
                    "finalize",
                    "capability_route",
                    "run_end",
                ]
            ],
        }
    }

    result = _evaluate_sample({"expected": {}}, sample)
    markdown = _render_markdown(
        [
            {
                "id": "trace-order",
                "category": "observability",
                "pass_pow_k": False,
                "pass_rate": 0.0,
                "latency": {},
                "effect_score": 1.0,
                "speed_score": 1.0,
                "process_score": result["process"]["score"],
                "first_sample": result,
            }
        ],
        {
            "timestamp": "2026-07-30T00:00:00Z",
            "runtime_url": "http://127.0.0.1:8010",
            "repeats": 1,
            "skipped": 0,
        },
    )

    assert result["process"]["missing_events"] == []
    assert result["process"]["order_issues"] == ["capability_route_after_finalize"]
    assert "过程顺序问题：['capability_route_after_finalize']" in markdown
