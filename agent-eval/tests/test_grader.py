from app.grader import grade_capability_inventory, grade_run, grade_trace


def test_grade_trace_passes_when_required_nodes_exist():
    result = grade_trace([{"nodeId": node} for node in ["A", "D1", "E", "F", "Z", "AH"]])
    assert result["passed"] is True
    assert result["score"] == 1.0


def test_grade_trace_passes_when_runtime_events_exist():
    result = grade_trace(
        [
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
    )
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
        "directive": {
            "domain": "job",
            "intent": "job.recommend",
            "router": "llm",
            "confidence": 0.95,
            "next_action": "call_get_recommend_jobs",
        },
        "trace_events": [
            {"event": event}
            for event in [
                "run_start",
                "understand_goal",
                "task_understanding",
                "capability_route",
                "finalize",
                "run_end",
            ]
        ],
        "tool_events": [{"id": "job_search", "status": "success", "summary": "读取岗位 Fixture"}],
    }

    result = grade_run(run, {"intent": "job.recommend", "domain": "job"})

    assert result["passed"] is False
    assert any(issue["code"] == "no_fixture_or_mock_claims" for issue in result["issues"])


def test_grade_run_rejects_job_cards_that_bypass_strict_recommendation_gate():
    run = {
        "status": "success",
        "answer": "已找到岗位。",
        "directive": {"domain": "job", "intent": "job.recommend", "router": "llm", "confidence": 0.99},
        "trace_events": [
            {"event": event}
            for event in [
                "run_start",
                "understand_goal",
                "task_understanding",
                "capability_route",
                "finalize",
                "run_end",
            ]
        ],
        "job_cards": [
            {
                "securityId": "bad-1",
                "matchScore": 45,
                "matchConfidence": "medium",
                "matchRecommendation": "不建议",
            }
        ],
    }

    result = grade_run(run, {"intent": "job.recommend", "domain": "job"})

    assert result["passed"] is False
    assert any(issue["code"] == "job_recommendations_pass_quality_gate" for issue in result["issues"])


def test_grade_run_accepts_job_cards_meeting_default_sixty_point_gate():
    run = {
        "status": "success",
        "answer": "已找到岗位。",
        "directive": {"domain": "job", "intent": "job.recommend", "router": "llm", "confidence": 0.99},
        "trace_events": [
            {"event": event}
            for event in [
                "run_start",
                "understand_goal",
                "task_understanding",
                "capability_route",
                "finalize",
                "run_end",
            ]
        ],
        "job_cards": [
            {
                "securityId": "qualified-1",
                "matchScore": 65,
                "matchConfidence": "medium",
                "matchRecommendation": "可尝试",
            }
        ],
    }

    result = grade_run(run, {"intent": "job.recommend", "domain": "job"})

    assert not any(issue["code"] == "job_recommendations_pass_quality_gate" for issue in result["issues"])


def test_grade_run_rejects_incomplete_job_scoring_even_when_no_cards_are_returned():
    run = {
        "status": "success",
        "answer": "当前没有合适岗位。",
        "directive": {"domain": "job", "intent": "job.recommend", "router": "llm", "confidence": 0.99},
        "trace_events": [
            {"event": event}
            for event in [
                "run_start",
                "understand_goal",
                "task_understanding",
                "capability_route",
                "finalize",
                "run_end",
            ]
        ],
        "tool_events": [
            {
                "id": "recommendation_quality_gate",
                "status": "success",
                "detail": {
                    "requestedMatchCount": 23,
                    "returnedMatchCount": 1,
                    "missingMatchCount": 22,
                    "qualifiedCount": 0,
                    "rejectionReasons": {"未达到最低匹配分": 1},
                },
            }
        ],
        "job_cards": [],
    }

    result = grade_run(
        run,
        {
            "intent": "job.recommend",
            "domain": "job",
            "require_complete_recommendation_scoring": True,
        },
    )

    assert result["passed"] is False
    assert any(issue["code"] == "job_recommendation_scoring_is_complete" for issue in result["issues"])
    assert any(issue["code"] == "job_recommendation_funnel_is_conserved" for issue in result["issues"])


def test_grade_run_rejects_self_reported_complete_scoring_when_funnel_loses_a_candidate():
    run = {
        "status": "success",
        "answer": "累计评估 23 个候选。",
        "directive": {"domain": "job", "intent": "job.recommend", "router": "llm", "confidence": 0.99},
        "trace_events": [
            {"event": event}
            for event in [
                "run_start",
                "understand_goal",
                "task_understanding",
                "capability_route",
                "finalize",
                "run_end",
            ]
        ],
        "tool_events": [
            {
                "id": "recommendation_quality_gate",
                "status": "success",
                "detail": {
                    "requestedMatchCount": 23,
                    "returnedMatchCount": 23,
                    "missingMatchCount": 0,
                    "qualifiedCount": 1,
                    "rejectionReasons": {"未达到最低匹配分": 21},
                },
            }
        ],
        "job_cards": [
            {
                "securityId": "qualified-1",
                "matchScore": 75,
                "matchConfidence": "medium",
                "matchRecommendation": "可尝试",
            }
        ],
    }

    result = grade_run(
        run,
        {
            "intent": "job.recommend",
            "domain": "job",
            "require_complete_recommendation_scoring": True,
        },
    )

    assert not any(issue["code"] == "job_recommendation_scoring_is_complete" for issue in result["issues"])
    assert any(issue["code"] == "job_recommendation_funnel_is_conserved" for issue in result["issues"])


def test_grade_run_accepts_conserved_twenty_three_candidate_recommendation_funnel():
    run = {
        "status": "success",
        "answer": "累计评估 23 个候选，其中 2 个通过推荐门槛。",
        "directive": {"domain": "job", "intent": "job.recommend", "router": "llm", "confidence": 0.99},
        "trace_events": [
            {"event": event}
            for event in [
                "run_start",
                "understand_goal",
                "task_understanding",
                "capability_route",
                "finalize",
                "run_end",
            ]
        ],
        "tool_events": [
            {
                "id": "recommendation_quality_gate",
                "status": "success",
                "detail": {
                    "requestedMatchCount": 23,
                    "returnedMatchCount": 23,
                    "missingMatchCount": 0,
                    "qualifiedCount": 2,
                    "rejectionReasons": {
                        "未达到最低匹配分": 18,
                        "匹配置信度低": 2,
                        "投递建议为不建议": 1,
                    },
                },
            }
        ],
        "job_cards": [
            {
                "securityId": "qualified-1",
                "matchScore": 75,
                "matchConfidence": "medium",
                "matchRecommendation": "可尝试",
            },
            {
                "securityId": "qualified-2",
                "matchScore": 82,
                "matchConfidence": "medium",
                "matchRecommendation": "推荐",
            },
        ],
    }

    result = grade_run(
        run,
        {
            "intent": "job.recommend",
            "domain": "job",
            "minimum_recommended_match_score": 60,
            "require_complete_recommendation_scoring": True,
            "minimum_qualified_jobs": 1,
        },
    )

    assert not any(issue["code"] == "job_recommendation_scoring_is_complete" for issue in result["issues"])
    assert not any(issue["code"] == "job_recommendation_funnel_is_conserved" for issue in result["issues"])
    assert not any(issue["code"] == "job_recommendations_pass_quality_gate" for issue in result["issues"])


def test_grade_run_requires_qualified_card_for_deterministic_recommendation_case():
    run = {
        "status": "success",
        "answer": "预筛完成。",
        "directive": {"domain": "job", "intent": "job.recommend", "router": "llm", "confidence": 0.99},
        "trace_events": [
            {"event": event}
            for event in [
                "run_start",
                "understand_goal",
                "task_understanding",
                "capability_route",
                "finalize",
                "run_end",
            ]
        ],
        "tool_events": [
            {
                "id": "recommendation_quality_gate",
                "status": "success",
                "detail": {
                    "requestedMatchCount": 3,
                    "returnedMatchCount": 3,
                    "missingMatchCount": 0,
                    "qualifiedCount": 0,
                    "rejectionReasons": {"未达到最低匹配分": 3},
                },
            }
        ],
        "job_cards": [],
    }

    result = grade_run(
        run,
        {
            "intent": "job.recommend",
            "domain": "job",
            "require_complete_recommendation_scoring": True,
            "minimum_qualified_jobs": 1,
        },
    )

    assert not any(issue["code"] == "job_recommendation_scoring_is_complete" for issue in result["issues"])
    assert any(issue["code"] == "job_recommendation_has_minimum_qualified_results" for issue in result["issues"])


def test_grade_run_rejects_job_cards_repeated_from_previous_batch():
    run = {
        "status": "success",
        "answer": "已为你换一批岗位。",
        "directive": {
            "domain": "job",
            "intent": "job.recommend",
            "router": "semantic_config_shortcut",
            "confidence": 0.99,
            "next_action": "call_get_recommend_jobs",
        },
        "trace_events": [
            {"event": event}
            for event in [
                "run_start",
                "understand_goal",
                "task_understanding",
                "capability_route",
                "finalize",
                "run_end",
            ]
        ],
        "previous_job_cards": [{"securityId": "shown-1"}],
        "job_cards": [
            {
                "securityId": "shown-1",
                "matchScore": 82,
                "matchConfidence": "medium",
                "matchRecommendation": "推荐",
            }
        ],
    }

    result = grade_run(run, {"intent": "job.recommend", "domain": "job"})

    assert result["passed"] is False
    assert any(issue["code"] == "job_recommendations_do_not_repeat" for issue in result["issues"])


def test_grade_run_accepts_conversation_shortcut_router():
    run = {
        "status": "success",
        "answer": "已为你换一批岗位。",
        "directive": {
            "domain": "job",
            "intent": "job.recommend",
            "router": "semantic_config_shortcut",
            "confidence": 0.92,
            "next_action": "call_get_recommend_jobs",
        },
        "trace_events": [
            {"event": event}
            for event in [
                "run_start",
                "understand_goal",
                "task_understanding",
                "capability_route",
                "finalize",
                "run_end",
            ]
        ],
        "tool_events": [{"id": "job_search", "status": "success", "summary": "Boss 实时搜索完成"}],
    }

    result = grade_run(run, {"intent": "job.recommend", "domain": "job"})

    assert not any(issue["code"] == "llm_first" for issue in result["issues"])


def test_grade_capability_inventory_accepts_complete_contracts():
    profile = {
        "capabilities": [
            {
                "id": "resume.match",
                "execution_intent": "compare_analyze",
                "required_tools": ["resume_match"],
                "evidence_requirements": ["简历", "JD", "证据链"],
            },
            {
                "id": "interview.prepare",
                "execution_intent": "generate_artifact",
                "planner_needed": True,
                "evidence_requirements": ["简历", "JD"],
            },
        ]
    }

    result = grade_capability_inventory(profile)

    assert result["passed"] is True


def test_grade_capability_inventory_rejects_missing_execution_contract():
    result = grade_capability_inventory(
        {
            "capabilities": [
                {
                    "id": "interview.prepare",
                    "execution_intent": "generate_artifact",
                    "evidence_requirements": ["简历", "JD"],
                }
            ]
        }
    )

    assert result["passed"] is False
    assert any(issue["code"] == "interview.prepare:execution_contract" for issue in result["issues"])


def test_grade_run_requires_injection_flag_when_expected():
    base_run = {
        "status": "success",
        "answer": "已完成岗位搜索。",
        "directive": {
            "domain": "job",
            "intent": "job.recommend",
            "router": "llm",
            "confidence": 0.95,
            "next_action": "call_get_recommend_jobs",
        },
        "trace_events": [
            {"event": event}
            for event in [
                "run_start",
                "understand_goal",
                "task_understanding",
                "capability_route",
                "finalize",
                "run_end",
            ]
        ],
    }

    unflagged = dict(
        base_run, tool_events=[{"id": "job_search", "status": "success", "summary": "完成", "metadata": {}}]
    )
    result = grade_run(unflagged, {"intent": "job.recommend", "domain": "job", "expect_injection_flag": True})
    assert any(issue["code"] == "injection_result_flagged" for issue in result["issues"])

    flagged = dict(
        base_run,
        tool_events=[
            {
                "id": "job_search",
                "status": "success",
                "summary": "完成",
                "metadata": {"injection_suspected": True, "injection_patterns": ["override_instructions_en"]},
            }
        ],
    )
    result = grade_run(flagged, {"intent": "job.recommend", "domain": "job", "expect_injection_flag": True})
    assert not any(issue["code"] == "injection_result_flagged" for issue in result["issues"])


def test_grade_run_accepts_evidence_based_resume_match():
    run = {
        "status": "success",
        "answer": "已完成基于岗位证据的简历匹配评估。",
        "directive": {
            "domain": "job",
            "intent": "resume.match",
            "router": "llm",
            "confidence": 0.91,
            "next_action": "run_resume_match",
        },
        "trace_events": [
            {"event": event}
            for event in [
                "run_start",
                "understand_goal",
                "task_understanding",
                "capability_route",
                "finalize",
                "run_end",
            ]
        ],
        "tool_events": [{"id": "resume_match", "status": "success", "summary": "完成"}],
        "resume_match": {
            "matches": [
                {
                    "id": "j1",
                    "score": 82,
                    "score_confidence": "medium",
                    "evidence_count": 3,
                    "evidence": [
                        {"resume_evidence": "Agent 项目", "job_requirement": "Agent 应用开发", "assessment": "相关"}
                    ],
                    "dimensions": {
                        "education_fit": {
                            "score": 78,
                            "evidence": "相关专业本科",
                            "gap": "",
                        }
                    },
                }
            ]
        },
    }

    result = grade_run(run, {"intent": "resume.match", "domain": "job", "requires_evidence": True, "min_score": 0.75})

    assert result["passed"] is True
    assert result["dimensions"]["grounding"]["score"] == 1.0


def test_resume_match_rejects_empty_education_fit_score():
    run = _valid_run()
    run["resume_match"] = {
        "matches": [
            {
                "id": "j1",
                "score": 72,
                "score_confidence": "medium",
                "evidence": [
                    {"resume_evidence": "信息与计算科学本科", "job_requirement": "计算机相关专业", "assessment": "相近"}
                ],
                "dimensions": {
                    "education_fit": {
                        "score": None,
                        "evidence": "专业方向相近",
                        "gap": "学历要求需确认",
                    }
                },
            }
        ]
    }

    result = grade_run(run, {"intent": "resume.match", "domain": "job", "requires_evidence": True})

    assert any(issue["code"] == "education_fit_has_numeric_score" for issue in result["issues"])


def test_resume_match_rejects_low_confidence_with_complete_evidence_coverage():
    run = _valid_run()
    run["resume_match"] = {
        "matches": [
            {
                "id": "j1",
                "score": 78,
                "score_confidence": "low",
                "evidence": [
                    {
                        "resume_evidence": f"简历证据 {index}",
                        "job_requirement": f"岗位要求 {index}",
                        "assessment": "可核验",
                    }
                    for index in range(1, 4)
                ],
            }
        ]
    }

    result = grade_run(run, {"intent": "resume.match", "domain": "job", "requires_evidence": True})

    assert any(issue["code"] == "confidence_matches_evidence_coverage" for issue in result["issues"])


def test_grade_capability_inventory_rejects_non_object_items():
    result = grade_capability_inventory({"capabilities": ["resume.match"]})

    assert result["passed"] is False
    assert any(issue["code"] == "capability_0:object_required" for issue in result["issues"])


def test_resume_match_does_not_trust_reported_evidence_count():
    run = _valid_run()
    run["resume_match"] = {
        "matches": [
            {
                "id": "j1",
                "score": 88,
                "score_confidence": "high",
                "evidence_count": 99,
                "evidence": [],
                "hits": [],
            }
        ]
    }

    result = grade_run(run, {"intent": "resume.match", "domain": "job", "requires_evidence": True})

    assert result["passed"] is False
    assert any(issue["code"] == "resume_match_has_evidence" for issue in result["issues"])


def test_repeated_observability_events_cannot_dilute_other_dimensions():
    run = _valid_run()
    run["answer"] = "基于模拟数据生成了岗位结果"
    one_event = dict(run, trace_events=[*run["trace_events"], _tool_end_event()])
    many_events = dict(run, trace_events=[*run["trace_events"], *[_tool_end_event() for _ in range(50)]])

    one_result = grade_run(one_event, {"intent": "resume.match", "domain": "job"})
    many_result = grade_run(many_events, {"intent": "resume.match", "domain": "job"})

    assert one_result["passed"] is False
    assert many_result["passed"] is False
    assert many_result["score"] == one_result["score"]


def test_missing_next_action_does_not_skip_false_success_check():
    run = _valid_run()
    run["directive"].pop("next_action")
    run["answer"] = "任务未完成，服务超时"
    run["status"] = "success"

    result = grade_run(run, {"intent": "resume.match", "domain": "job"})

    assert any(issue["code"] == "next_action_present" for issue in result["issues"])
    assert any(issue["code"] == "failure_not_marked_success" for issue in result["issues"])


def _valid_run():
    return {
        "status": "success",
        "answer": "已完成基于真实岗位证据的匹配分析。",
        "directive": {
            "domain": "job",
            "intent": "resume.match",
            "router": "llm",
            "confidence": 0.9,
            "next_action": "run_resume_match",
        },
        "trace_events": [
            {"event": event}
            for event in [
                "run_start",
                "understand_goal",
                "task_understanding",
                "capability_route",
                "finalize",
                "run_end",
            ]
        ],
        "tool_events": [{"id": "resume_match", "status": "success", "summary": "完成"}],
    }


def _tool_end_event():
    return {
        "event": "tool_execute_end",
        "payload": {"duration_ms": 10, "results": [{"tool": "resume_match", "success": True}]},
    }
