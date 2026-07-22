from scripts.run_engine_eval import _case_payload


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
    payload = _case_payload({"id": "technical_qa", "input": "解释 Java volatile"})

    assert payload["messages"] == [{"role": "user", "content": "解释 Java volatile"}]
    assert payload["stream"] is True
