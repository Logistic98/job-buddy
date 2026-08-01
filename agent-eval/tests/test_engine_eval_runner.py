from datetime import date
from pathlib import Path

import scripts.run_engine_eval as engine_eval_runner
from scripts.run_engine_eval import (
    _build_run,
    _case_payload,
    _effect_checks,
    _evaluate_sample,
    _load_cases,
    _render_markdown,
    _runtime_headers,
)


def test_case_payload_supports_recent_messages_and_previous_slots():
    messages = [
        {"role": "user", "content": "分析此岗位与当前简历的匹配度"},
        {"role": "assistant", "content": "已完成当前岗位与简历的匹配分析。"},
        {"role": "user", "content": "现在这个5年经验的简历呢"},
    ]
    selected_job = {"jobName": "云原生后端工程师", "company": "示例科技"}

    payload = _case_payload(
        {
            "id": "resume_switch_reuses_selected_job",
            "input": "现在这个5年经验的简历呢",
            "messages": messages,
            "metadata": {
                "resume_id": "resume-5-years",
                "previous_slots": {"_selected_job": selected_job},
            },
        }
    )

    assert payload["messages"] == messages
    assert payload["metadata"]["profile"] == "job-buddy"
    assert payload["metadata"]["previous_slots"]["_selected_job"] == selected_job
    assert payload["metadata"]["resume_id"] == "resume-5-years"


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
                        "output": {
                            "sandboxed": True,
                            "exit_code": 0,
                            "stdout": "45\n",
                            "dependencies": ["numpy"],
                        },
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


def test_tool_free_eval_explicitly_rejects_real_tool_results():
    case = {"expected": {"expect_no_tool_results": True}}
    empty = _build_run({"done": {"tool_results": []}})
    synthetic = _build_run(
        {
            "done": {
                "tool_results": [
                    {
                        "tool_name": "job-buddy.task_understanding",
                        "success": True,
                        "metadata": {"synthetic": True},
                    }
                ]
            }
        }
    )
    grep = _build_run(
        {
            "done": {
                "tool_results": [
                    {
                        "tool_name": "grep",
                        "success": True,
                        "output": {"matches": [], "count": 0},
                    }
                ]
            }
        }
    )

    empty_check = next(check for check in _effect_checks(case, empty, {}) if check["code"] == "expect_no_tool_results")
    synthetic_check = next(
        check for check in _effect_checks(case, synthetic, {}) if check["code"] == "expect_no_tool_results"
    )
    grep_check = next(check for check in _effect_checks(case, grep, {}) if check["code"] == "expect_no_tool_results")

    assert empty_check["passed"] is True
    assert synthetic_check["passed"] is True
    assert grep_check["passed"] is False
    assert grep_check["detail"]["actual_tools"] == ["grep"]


def test_eval_rejects_duplicate_tool_execution_and_replanning_events():
    case = {
        "expected": {
            "max_tool_executions": {"web_search": 1},
            "max_trace_event_counts": {"tool_search": 1, "plan_created": 1},
        }
    }
    run = _build_run(
        {
            "done": {
                "tool_results": [
                    {"tool_name": "web_search", "success": True},
                    {"tool_name": "web_search", "success": True},
                ],
                "trace_events": [
                    {"event": "tool_search"},
                    {"event": "plan_created"},
                    {"event": "tool_search"},
                    {"event": "plan_created"},
                ],
            }
        }
    )

    checks = {check["code"]: check for check in _effect_checks(case, run, {})}

    assert checks["max_tool_executions"]["passed"] is False
    assert checks["max_tool_executions"]["detail"]["actual"] == {"web_search": 2}
    assert checks["max_trace_event_counts"]["passed"] is False
    assert checks["max_trace_event_counts"]["detail"]["actual"] == {
        "tool_search": 2,
        "plan_created": 2,
    }


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


def test_web_search_quality_eval_checks_query_expansion_deduplication_and_preferred_domain():
    case = {
        "expected": {
            "web_search_quality": {
                "query_max_chars": 80,
                "forbidden_query_fragments": ["包括模型名称"],
                "require_expansion": True,
                "max_queries": 2,
                "require_unique_urls": True,
                "preferred_source_domains_any": ["openai.com"],
                "require_preferred_source_flag": True,
                "require_preferred_query_scope": True,
                "forbid_site_operator": True,
                "min_official_sources": 1,
                "require_official_tier": True,
                "allowed_official_verifications": ["bocha_result", "configured_direct_fetch"],
            }
        }
    }
    valid = _build_run(
        {
            "done": {
                "tool_results": [
                    {
                        "tool_name": "web_search",
                        "success": True,
                        "output": {
                            "query": "OpenAI 最新模型 发布 2026",
                            "queries": [
                                "OpenAI 最新模型 发布 2026",
                                "OpenAI 最新模型 发布 2026 official",
                            ],
                            "query_scopes": [
                                {"query": "OpenAI 最新模型 发布 2026", "include_domains": []},
                                {
                                    "query": "OpenAI 最新模型 发布 2026 official",
                                    "include_domains": ["openai.com"],
                                },
                            ],
                            "preferred_source_domains": ["openai.com"],
                            "preferred_source_found": True,
                            "official_source_count": 1,
                            "third_party_source_count": 1,
                            "official_verification": "bocha_result",
                            "results": [
                                {
                                    "title": "Introducing GPT-5",
                                    "url": "https://openai.com/index/introducing-gpt-5/",
                                    "source_tier": "official",
                                },
                                {
                                    "title": "OpenAI model news",
                                    "url": "https://news.example.com/openai",
                                    "source_tier": "third_party",
                                },
                            ],
                        },
                    }
                ]
            }
        }
    )
    invalid = _build_run(
        {
            "done": {
                "tool_results": [
                    {
                        "tool_name": "web_search",
                        "success": True,
                        "output": {
                            "query": "联网搜索并整理 OpenAI 最新模型，包括模型名称、主要能力和适用场景",
                            "queries": ["联网搜索并整理 OpenAI 最新模型，包括模型名称、主要能力和适用场景"],
                            "results": [
                                {
                                    "title": "重复媒体稿",
                                    "url": "https://news.example.com/openai?from=desktop",
                                },
                                {
                                    "title": "重复媒体稿移动版",
                                    "url": "https://news.example.com/openai?from=mobile",
                                },
                            ],
                        },
                    }
                ]
            }
        }
    )

    valid_check = next(check for check in _effect_checks(case, valid, {}) if check["code"] == "web_search_quality")
    invalid_check = next(check for check in _effect_checks(case, invalid, {}) if check["code"] == "web_search_quality")

    assert valid_check["passed"] is True
    assert invalid_check["passed"] is False
    assert set(invalid_check["detail"]["issues"]) == {
        "forbidden_query_fragment",
        "missing_query_expansion",
        "duplicate_result_urls",
        "preferred_source_missing",
        "preferred_source_unverified",
        "preferred_query_scope_missing",
        "official_source_count_too_low",
        "official_source_tier_missing",
        "official_verification_invalid",
    }


def test_web_search_quality_allows_third_party_fallback_after_official_first_query():
    case = {
        "expected": {
            "web_search_quality": {
                "preferred_source_domains_any": ["openai.com"],
                "require_preferred_source_flag": True,
                "require_preferred_query_scope": True,
                "min_official_sources": 1,
                "require_official_tier": True,
                "allowed_official_verifications": ["bocha_result"],
                "allow_third_party_fallback": True,
            }
        }
    }
    run = _build_run(
        {
            "done": {
                "tool_results": [
                    {
                        "tool_name": "web_search",
                        "success": True,
                        "output": {
                            "query": "OpenAI 最新模型 2026",
                            "queries": ["OpenAI 最新模型 2026", "OpenAI 最新模型 2026 official"],
                            "query_scopes": [
                                {"query": "OpenAI 最新模型 2026", "include_domains": []},
                                {
                                    "query": "OpenAI 最新模型 2026 official",
                                    "include_domains": ["openai.com"],
                                },
                            ],
                            "preferred_source_domains": ["openai.com"],
                            "preferred_source_found": False,
                            "official_source_count": 0,
                            "third_party_source_count": 1,
                            "official_verification": "not_found",
                            "results": [
                                {
                                    "title": "Independent model report",
                                    "url": "https://news.example.com/model-report",
                                    "source_tier": "third_party",
                                }
                            ],
                        },
                    }
                ]
            }
        }
    )

    check = next(check for check in _effect_checks(case, run, {}) if check["code"] == "web_search_quality")

    assert check["passed"] is True
    assert check["detail"]["issues"] == []


LATEST_ENGINEERING_URL = "https://www.anthropic.com/research/discovering-cryptographic-weaknesses"
LATEST_ENGINEERING_TITLE = "Discovering cryptographic weaknesses with Claude"


def _latest_search_quality_case():
    return {
        "expected": {
            "web_search_quality": {
                "require_latest_verified": True,
                "expected_content_scope": "engineering_blog",
                "latest_result_path_prefixes": ["/engineering/", "/research/"],
                "trusted_hosts_any": ["anthropic.com", "www.anthropic.com"],
                "allowed_selection_bases": ["official_catalog_published_at"],
                "allowed_latest_verification_methods": ["configured_official_index"],
                "allowed_published_date_sources": ["official_catalog", "official_detail"],
                "expected_current_date": "2026-08-01",
                "require_selected_url_in_answer": True,
                "require_selected_published_date_in_answer": True,
                "require_selected_title_in_answer": True,
            }
        }
    }


def _latest_search_run(
    *,
    selected_url=LATEST_ENGINEERING_URL,
    latest_result_url=None,
    selected_published_date="2026-07-28",
    as_of_date="2026-08-01",
    time_range_start="2026-01-01",
    row_overrides=None,
    output_overrides=None,
    answer=None,
):
    row = {
        "title": LATEST_ENGINEERING_TITLE,
        "url": selected_url,
        "source_tier": "official",
        "is_latest": True,
        "verification_method": "configured_official_index",
        "published_date": selected_published_date,
        "published_date_source": "official_detail",
        **(row_overrides or {}),
    }
    output = {
        "content_scope": "engineering_blog",
        "latest_evidence_verified": True,
        "selection_basis": "official_catalog_published_at",
        "latest_result_url": latest_result_url or selected_url,
        "selected_url": selected_url,
        "selected_published_date": selected_published_date,
        "as_of_date": as_of_date,
        "time_range_start": time_range_start,
        "results": [row],
        **(output_overrides or {}),
    }
    resolved_answer = answer
    if resolved_answer is None:
        resolved_answer = (
            f"截至 {as_of_date}，最新工程博客 {LATEST_ENGINEERING_TITLE} "
            f"发布于 {selected_published_date}：{selected_url}"
        )
    return _build_run(
        {
            "done": {
                "answer": resolved_answer,
                "tool_results": [{"tool_name": "web_search", "success": True, "output": output}],
            }
        }
    )


def _web_search_quality_check(run):
    return next(
        check
        for check in _effect_checks(_latest_search_quality_case(), run, {})
        if check["code"] == "web_search_quality"
    )


def test_web_search_quality_eval_requires_verified_latest_result_in_expected_official_section():
    valid_check = _web_search_quality_check(_latest_search_run())
    invalid_check = _web_search_quality_check(
        _latest_search_run(
            selected_url="https://www.anthropic.com/claude/fable",
            selected_published_date="2026-06-09",
            output_overrides={"content_scope": "company_news", "latest_evidence_verified": False},
        )
    )

    assert valid_check["passed"] is True
    assert invalid_check["passed"] is False
    assert set(invalid_check["detail"]["issues"]) == {
        "latest_evidence_unverified",
        "content_scope_mismatch",
        "latest_result_path_mismatch",
    }


def test_web_search_quality_eval_accepts_engineering_or_research_catalog_paths():
    research_check = _web_search_quality_check(_latest_search_run())
    engineering_check = _web_search_quality_check(
        _latest_search_run(
            selected_url="https://www.anthropic.com/engineering/future-engineering-post",
            selected_published_date="2026-07-29",
            row_overrides={"title": "Future engineering post"},
            answer=(
                "截至 2026-08-01，最新工程博客 Future engineering post 发布于 2026-07-29："
                "https://www.anthropic.com/engineering/future-engineering-post"
            ),
        )
    )

    assert research_check["passed"] is True
    assert engineering_check["passed"] is True


def test_web_search_quality_eval_rejects_untrusted_exact_hosts_even_with_official_path():
    malicious_subdomain = _web_search_quality_check(
        _latest_search_run(selected_url="https://evil.anthropic.com/research/fake-latest")
    )
    unrelated_host = _web_search_quality_check(
        _latest_search_run(selected_url="https://evil.example/research/fake-latest")
    )

    expected = {"official_result_host_untrusted", "latest_result_host_untrusted"}
    assert set(malicious_subdomain["detail"]["issues"]) == expected
    assert set(unrelated_host["detail"]["issues"]) == expected


def test_web_search_quality_eval_requires_latest_and_selected_urls_to_match():
    check = _web_search_quality_check(
        _latest_search_run(
            latest_result_url="https://www.anthropic.com/research/different-article",
        )
    )

    assert check["passed"] is False
    assert check["detail"]["issues"] == ["latest_result_url_mismatch"]


def test_web_search_quality_eval_binds_selected_url_to_one_official_latest_row():
    missing_row = _web_search_quality_check(
        _latest_search_run(
            output_overrides={
                "results": [
                    {
                        "url": "https://www.anthropic.com/research/another-article",
                        "source_tier": "official",
                        "is_latest": True,
                    }
                ]
            }
        )
    )
    invalid_row = _web_search_quality_check(
        _latest_search_run(row_overrides={"source_tier": "third_party", "is_latest": False})
    )

    assert missing_row["detail"]["issues"] == ["selected_result_missing"]
    assert set(invalid_row["detail"]["issues"]) == {
        "selected_result_not_official",
        "selected_result_not_marked_latest",
    }


def test_web_search_quality_eval_rejects_string_latest_marker():
    check = _web_search_quality_check(_latest_search_run(row_overrides={"is_latest": "True"}))

    assert check["detail"]["issues"] == ["selected_result_not_marked_latest"]


def test_web_search_quality_eval_restricts_latest_verification_and_date_provenance():
    check = _web_search_quality_check(
        _latest_search_run(
            row_overrides={
                "verification_method": "bocha_result",
                "published_date_source": "provider_snippet",
            }
        )
    )

    assert set(check["detail"]["issues"]) == {
        "latest_verification_method_invalid",
        "published_date_source_invalid",
    }


def test_web_search_quality_eval_rejects_mismatched_invalid_and_future_dates():
    mismatched = _web_search_quality_check(_latest_search_run(row_overrides={"published_date": "2026-05-24"}))
    invalid = _web_search_quality_check(_latest_search_run(selected_published_date="May 25, 2026"))
    invalid_as_of = _web_search_quality_check(_latest_search_run(as_of_date="August 1, 2026"))
    invalid_range = _web_search_quality_check(_latest_search_run(time_range_start="2026-99-99"))
    future = _web_search_quality_check(_latest_search_run(selected_published_date="2026-08-02"))
    before_range = _web_search_quality_check(_latest_search_run(selected_published_date="2025-12-31"))

    assert mismatched["detail"]["issues"] == ["selected_published_date_mismatch"]
    assert invalid["detail"]["issues"] == ["selected_published_date_invalid"]
    assert invalid_as_of["detail"]["issues"] == ["as_of_date_invalid"]
    assert invalid_range["detail"]["issues"] == ["time_range_start_invalid"]
    assert future["detail"]["issues"] == ["selected_published_date_after_as_of"]
    assert before_range["detail"]["issues"] == ["selected_published_date_before_time_range"]


def test_web_search_quality_eval_rejects_catalog_as_of_date_after_expected_current_date():
    check = _web_search_quality_check(_latest_search_run(as_of_date="2026-08-02"))

    assert check["passed"] is False
    assert check["detail"]["issues"] == ["as_of_date_after_current"]


def test_web_search_quality_eval_requires_selected_evidence_in_answer():
    check = _web_search_quality_check(_latest_search_run(answer="已找到官方最新工程博客。"))

    assert set(check["detail"]["issues"]) == {
        "selected_url_missing_from_answer",
        "selected_published_date_missing_from_answer",
        "selected_title_missing_from_answer",
    }


def test_web_search_quality_eval_requires_selected_title_in_answer():
    answer = f"最新工程博客发布于 2026-07-28：{LATEST_ENGINEERING_URL}"
    check = _web_search_quality_check(_latest_search_run(answer=answer))

    assert check["passed"] is False
    assert check["detail"]["issues"] == ["selected_title_missing_from_answer"]


def test_web_search_quality_eval_requires_unique_selected_row_for_title_binding():
    duplicated_row = {
        "title": LATEST_ENGINEERING_TITLE,
        "url": LATEST_ENGINEERING_URL,
        "source_tier": "official",
        "is_latest": True,
        "verification_method": "configured_official_index",
        "published_date": "2026-07-28",
        "published_date_source": "official_detail",
    }
    check = _web_search_quality_check(
        _latest_search_run(output_overrides={"results": [duplicated_row, {**duplicated_row}]})
    )

    assert check["passed"] is False
    assert check["detail"]["issues"] == ["selected_result_not_unique"]


OPENAI_MODELS_URL = "https://developers.openai.com/api/docs/models"


def _canonical_snapshot_quality_check(
    *,
    selection_basis="official_canonical_snapshot",
    selected_url=OPENAI_MODELS_URL,
    latest_result_url=None,
    catalog_url=None,
    selected_published_date="",
    as_of_date=None,
    time_range_start="",
):
    case = {
        "expected": {
            "web_search_quality": {
                "require_latest_verified": True,
                "latest_result_path_prefix": "/api/docs/models",
                "trusted_hosts_any": ["openai.com", "developers.openai.com"],
                "allowed_selection_bases": ["official_canonical_snapshot"],
                "allowed_latest_verification_methods": ["configured_direct_fetch"],
                "allowed_published_date_sources": ["official_snapshot"],
                "require_selected_url_in_answer": True,
            }
        }
    }
    output = {
        "latest_evidence_verified": True,
        "selection_basis": selection_basis,
        "latest_result_url": latest_result_url or selected_url,
        "catalog_url": catalog_url or selected_url,
        "selected_url": selected_url,
        "selected_published_date": selected_published_date,
        "as_of_date": as_of_date or date.today().isoformat(),
        "time_range_start": time_range_start,
        "results": [
            {
                "title": "Models | OpenAI API",
                "url": selected_url,
                "source_tier": "official",
                "is_latest": True,
                "verification_method": "configured_direct_fetch",
                "published_date": selected_published_date,
                "published_date_source": "official_snapshot",
            }
        ],
    }
    run = _build_run(
        {
            "done": {
                "answer": f"当前官方模型目录：{selected_url}",
                "tool_results": [{"tool_name": "web_search", "success": True, "output": output}],
            }
        }
    )
    return next(check for check in _effect_checks(case, run, {}) if check["code"] == "web_search_quality")


def test_web_search_quality_eval_accepts_verified_official_canonical_snapshot_without_publication_date():
    check = _canonical_snapshot_quality_check()

    assert check["passed"] is True
    assert check["detail"]["issues"] == []


def test_web_search_quality_eval_rejects_unapproved_latest_selection_basis():
    check = _canonical_snapshot_quality_check(selection_basis="provider_rank")

    assert check["passed"] is False
    assert check["detail"]["issues"] == ["selection_basis_invalid"]


def test_web_search_quality_eval_rejects_historical_window_for_canonical_snapshot():
    check = _canonical_snapshot_quality_check(
        as_of_date="2024-12-31",
        time_range_start="2024-01-01",
    )

    assert check["passed"] is False
    assert set(check["detail"]["issues"]) == {
        "canonical_snapshot_time_range_unsupported",
        "canonical_snapshot_as_of_not_current",
    }


def test_web_search_quality_eval_binds_canonical_catalog_selected_and_latest_urls():
    check = _canonical_snapshot_quality_check(
        catalog_url="https://developers.openai.com/api/docs/models/archive",
    )

    assert check["passed"] is False
    assert check["detail"]["issues"] == ["canonical_snapshot_catalog_url_mismatch"]


def test_web_search_quality_eval_rejects_publication_date_on_canonical_snapshot():
    check = _canonical_snapshot_quality_check(selected_published_date="2026-05-25")

    assert check["passed"] is False
    assert check["detail"]["issues"] == ["canonical_snapshot_published_date_present"]


def test_web_search_quality_eval_enforces_latest_path_segment_boundary():
    check = _canonical_snapshot_quality_check(
        selected_url="https://developers.openai.com/api/docs/models-evil",
    )

    assert check["passed"] is False
    assert check["detail"]["issues"] == ["latest_result_path_mismatch"]


def test_latest_eval_cases_require_official_latest_certification():
    payload = _load_cases(Path(__file__).resolve().parents[1] / "cases" / "engine-eval-v1.yaml")
    openai_cases = (
        "explicit_web_search_latest_model",
        "autonomous_web_search_latest_model",
    )
    for case_id in openai_cases:
        case = next(item for item in payload["cases"] if item["id"] == case_id)
        quality = case["expected"]["web_search_quality"]
        assert quality["allowed_official_verifications"] == ["configured_direct_fetch"]
        assert quality["require_latest_verified"] is True
        assert quality["allowed_selection_bases"] == ["official_canonical_snapshot"]
        assert quality["require_selected_url_in_answer"] is True

    anthropic = next(
        item for item in payload["cases"] if item["id"] == "autonomous_web_search_latest_anthropic_engineering_blog"
    )["expected"]["web_search_quality"]
    assert anthropic["allowed_official_verifications"] == ["configured_official_index"]
    assert anthropic["allowed_selection_bases"] == ["official_catalog_published_at"]
    assert anthropic["require_selected_published_date_in_answer"] is True
    assert anthropic["require_selected_title_in_answer"] is True


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
