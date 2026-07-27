from scripts.run_engine_eval import _case_payload, _effect_checks, _runtime_headers


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
