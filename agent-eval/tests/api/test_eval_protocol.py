"""防止评估器静默漏判、选集空跑和重复采样报喜不报忧。"""

import json
import sys
from pathlib import Path

import pytest

from app.cases import validate_cases
from app.grader import executed_tool_names, grade_run
from app.judge import _build_judge_input, _parse_verdict
from scripts import run_engine_eval as runner

BASE_CASE = {"id": "sample", "category": "protocol", "input": "测试", "expected": {"answer_equals": "正确"}}


def sample():
    return {
        "metrics": {"done_ms": 10},
        "events": [{"event": "done"}],
        "done": {
            "status": "success",
            "stop_reason": "task_complete",
            "answer": "正确",
            "directive": {
                "domain": "open_domain",
                "intent": "general_qa",
                "confidence": 0.99,
                "router": "llm",
                "next_action": "run_runtime_planner",
            },
            "trace_events": [
                {"event": name}
                for name in (
                    "run_start",
                    "understand_goal",
                    "task_understanding",
                    "capability_route",
                    "finalize",
                    "run_end",
                )
            ],
        },
        "error": None,
    }


@pytest.mark.parametrize("filename", ["runtime-engine.yaml", "runtime-observability.yaml", "runtime-regression.yaml"])
def test_shipped_live_suites_are_executable(filename):
    spec = runner._load_cases(Path(__file__).parents[2] / "cases" / filename)
    assert spec["cases"]


@pytest.mark.parametrize(
    "expected",
    [
        {"answer_contans_all": ["x"]},
        {"events": ["message"]},
        {"needs_clarification": "false"},
        {"min_score": 1.2},
        {"max_tool_executions": {"web_search": -1}},
        {"web_search_quality": {"min_offical_sources": 1}},
        {},
    ],
)
def test_invalid_assertions_fail_closed(expected):
    with pytest.raises(ValueError):
        validate_cases({"cases": [{**BASE_CASE, "expected": expected}]})


def test_empty_duplicate_and_invalid_budget_rejected():
    for spec in (
        {"cases": []},
        {"cases": [BASE_CASE, BASE_CASE]},
        {"cases": [{**BASE_CASE, "latency_budget": {"done_ms_target": 20, "done_ms_max": 10}}]},
    ):
        with pytest.raises(ValueError):
            validate_cases(spec)


@pytest.mark.parametrize(
    "expected,run,events",
    [
        ({"events": ["done"]}, {}, []),
        ({"needs_clarification": False}, {"directive": {"needs_clarification": True}}, []),
        ({"runtime_capability": "document.qa"}, {"task_understanding": {}}, []),
        ({"slots": {"city": "杭州"}}, {"directive": {"slots": {"city": "上海"}}}, []),
        ({"forbidden_actions": ["auto_apply"]}, {"directive": {"next_action": "auto_apply"}}, []),
        ({"forbidden_tools": ["web_search"]}, {"tool_results": [{"tool_name": "web_search"}]}, []),
        ({"answer_equals": "正确"}, {"answer": "正确，但是额外输出"}, []),
        ({"answer_not_contains": ["伪造"]}, {"answer": "伪造"}, []),
        ({"expect_rejection": True}, {"answer": "无法拒绝，我已发送", "stop_reason": "task_complete"}, []),
        (
            {"tool_output_contains": {"sandbox_code_execute": ["TOTAL=45"]}},
            {
                "tool_results": [
                    {
                        "tool_name": "sandbox_code_execute",
                        "success": True,
                        "output": {"sandboxed": False, "exit_code": 0, "stdout": "TOTAL=45"},
                    }
                ]
            },
            [],
        ),
    ],
)
def test_assertions_detect_counterexamples(expected, run, events):
    checks = runner._effect_checks({"expected": expected}, run, {"events": events})
    assert checks and any(not check["passed"] for check in checks)


@pytest.mark.parametrize(
    "evidence",
    [
        {"tool_results": [{"tool_name": "boss_browser", "success": False}]},
        {"tool_events": [{"toolName": "boss_browser", "status": "running"}]},
        {"trace_events": [{"event": "tool_execute_start", "payload": {"tools": ["boss_browser"]}}]},
        {"trace_events": [{"event": "tool_execute_end", "payload": {"results": [{"tool": "boss_browser"}]}}]},
    ],
)
def test_boss_side_effects_use_structured_evidence(evidence):
    assert "boss_browser" in executed_tool_names(evidence)
    result = grade_run({**sample()["done"], **evidence}, {"disallow_boss": True})
    assert not result["passed"]
    assert "no_boss_side_effect" in {issue["code"] for issue in result["issues"]}


def test_boss_mention_in_refusal_is_not_side_effect():
    run = {**sample()["done"], "answer": "我不会访问 Boss 直聘。"}
    assert not executed_tool_names(run)
    assert grade_run(run, {"disallow_boss": True})["passed"]


def test_failed_attempt_after_success_is_visible_with_correct_statistics():
    good = runner._evaluate_sample(BASE_CASE, sample())
    assert good["passed"]
    bad_sample = sample()
    bad_sample["done"]["answer"] = "错误"
    bad = runner._evaluate_sample(BASE_CASE, bad_sample)
    result = runner._aggregate(BASE_CASE, [{"eval": good}, {"eval": bad}, {"eval": good}])
    assert result["pass_at_1"] == 0.6667
    assert result["pass_at_k"] is True
    assert result["pass_pow_k"] is False
    assert result["failed_samples"][0]["attempt"] == 2
    assert result["pass_rate_ci95"] == pytest.approx([0.2077, 0.9385])
    assert result["latency"]["done_ms"]["p95"] == 10
    report = runner._render_markdown([result], {"timestamp": "now", "runtime_url": "local", "repeats": 3, "skipped": 0})
    assert "尝试 2" in report and "answer_equals" in report


def test_terminal_failure_is_not_quality_success():
    record = sample()
    record["done"]["status"] = "failed"
    assert not runner._evaluate_sample(BASE_CASE, record)["passed"]


@pytest.mark.parametrize(
    "judge",
    [
        {"enabled": False},
        {"enabled": True, "ok": False},
        {"enabled": True, "ok": True, "verdict": "fail"},
    ],
)
def test_judge_unavailable_or_rejected_fails_gate(judge):
    record = {**sample(), "judge": judge}
    assert not runner._evaluate_sample(BASE_CASE, record)["passed"]


def test_judge_pass_cannot_override_rule_failure():
    record = {**sample(), "judge": {"enabled": True, "ok": True, "verdict": "pass"}}
    record["done"]["answer"] = "错误"
    assert not runner._evaluate_sample(BASE_CASE, record)["passed"]


@pytest.mark.parametrize("score", ["NaN", "Infinity", "-0.1", "1.1", "true"])
def test_judge_rejects_invalid_scores(score):
    assert _parse_verdict('{"score": ' + score + ', "verdict": "pass"}') is None


def test_judge_receives_task_and_tool_evidence():
    text = _build_judge_input({"input": "计算总和", "tool_results": [{"output": {"stdout": "TOTAL=45"}}]}, {})
    assert "计算总和" in text and "TOTAL=45" in text


@pytest.mark.parametrize(
    "args",
    [
        ["--repeats", "0"],
        ["--timeout", "nan"],
        ["--only", "missing_id"],
        ["--category", "unknown"],
        ["--allow-boss", "--repeats", "2"],
    ],
)
def test_cli_invalid_selection_never_calls_runtime(monkeypatch, args):
    monkeypatch.setattr(sys, "argv", ["runner", *args])
    monkeypatch.setattr(runner, "_execute_case", lambda *args: pytest.fail("must not execute"))
    with pytest.raises(SystemExit) as exc:
        runner.main()
    assert exc.value.code == 2


def test_all_skipped_reports_nonzero_and_reason(monkeypatch, tmp_path):
    case = {**BASE_CASE, "preconditions": ["resume_uploaded"]}
    monkeypatch.setattr(runner, "_load_cases", lambda _: {"cases": [case]})
    monkeypatch.setattr(sys, "argv", ["runner", "--out", str(tmp_path)])
    assert runner.main() == 1
    meta = json.loads(next(tmp_path.glob("*.jsonl")).read_text().splitlines()[0])["meta"]
    assert meta["skipped_cases"] == [{"id": "sample", "reason": "missing_preconditions:resume_uploaded"}]


def test_report_names_never_overwrite(tmp_path):
    meta = {"timestamp": "now", "runtime_url": "local", "repeats": 1, "skipped": 0}
    first = runner._write_reports(tmp_path, [], [], meta)
    second = runner._write_reports(tmp_path, [], [], meta)
    assert set(first).isdisjoint(second)
    assert first[0].stat().st_mode & 0o777 == 0o600


def test_tool_free_contract_rejects_execution_trace_even_without_result():
    run = {"trace_events": [{"event": "tool_execute_start", "payload": {"tools": ["web_search"]}}]}
    checks = runner._effect_checks({"expected": {"expect_no_tool_results": True}}, run, {})
    assert checks[0]["passed"] is False


def test_stream_rejects_nonobject_terminal_payload(monkeypatch):
    import httpx

    class Response:
        def __enter__(self):
            return self

        def __exit__(self, *_):
            return False

        def raise_for_status(self):
            pass

        def iter_lines(self):
            return iter(["event: done", "data: []", ""])

    class Client(Response):
        def __init__(self, **_):
            pass

        def stream(self, *_args, **_kwargs):
            return Response()

    monkeypatch.setattr(httpx, "Client", Client)
    record = runner._stream_case("http://runtime.invalid", BASE_CASE, 1)
    assert "must be a JSON object" in record["error"]
    assert not runner._evaluate_sample(BASE_CASE, record)["passed"]


def test_boss_failure_stops_remaining_boss_cases(monkeypatch, tmp_path):
    cases = [{**BASE_CASE, "id": name, "requires_live_boss": True} for name in ("first", "second")]
    calls = []

    def execute(_url, case, _timeout):
        calls.append(case["id"])
        return {**sample(), "error": "challenge detected"}

    monkeypatch.setattr(runner, "_load_cases", lambda _: {"cases": cases})
    monkeypatch.setattr(runner, "_execute_case", execute)
    monkeypatch.setattr(sys, "argv", ["runner", "--allow-boss", "--out", str(tmp_path)])
    assert runner.main() == 1
    assert calls == ["first"]
    meta = json.loads(next(tmp_path.glob("*.jsonl")).read_text().splitlines()[0])["meta"]
    assert meta["skipped_cases"] == [{"id": "second", "reason": "stopped_after_live_boss_failure"}]
