from __future__ import annotations

import re
from typing import Any

BACKEND_REQUIRED_NODES = {"A", "D1", "E", "F", "Z", "AH"}
RUNTIME_REQUIRED_EVENTS = {
    "run_start",
    "understand_goal",
    "task_understanding",
    "capability_route",
    "finalize",
    "run_end",
}

# 速度评估的时延指标键与默认权重/严重级别。target 内满分，达到 max 记 0，区间内线性衰减。
LATENCY_METRIC_SPECS = (
    ("ttft_ms", 1.0, "high"),
    ("done_ms", 0.8, "medium"),
    ("ttfb_ms", 0.4, "low"),
)

DIMENSION_WEIGHTS = {
    "tool_execution": 1.0,
    "task_understanding": 1.0,
    "grounding": 1.3,
    "output_quality": 1.0,
    "safety": 1.3,
    "runtime_contract": 1.2,
    "latency": 0.8,
    "observability": 0.8,
}

FORBIDDEN_FAKE_MARKERS = [
    "读取岗位 Fixture",
    "使用本地 fixture/mock 验证卡片和流程",
    "示例智能科技",
    "样例 AI 平台",
    "Runtime 已完成任务理解，但未返回可展示回答",
    "Runtime 已返回托管任务结果",
]
FAKE_CLAIM_PATTERN = re.compile(
    r"(?:使用|读取|基于|来自|采用).{0,16}(?:fixture|mock|模拟数据|样例数据|示例数据|合成数据)",
    re.IGNORECASE,
)


def grade_trace(trace: list[dict]) -> dict:
    """按 Runtime 事件流或 Backend 节点流检查核心执行链路。"""

    nodes = {str(step.get("nodeId")) for step in trace if step.get("nodeId") is not None}
    events = {str(step.get("event")) for step in trace if step.get("event") is not None}

    if events:
        missing_events = sorted(RUNTIME_REQUIRED_EVENTS - events)
        order_issues = _event_order_issues([str(step.get("event")) for step in trace if step.get("event") is not None])
        passed = not missing_events and not order_issues
        score = max(0.0, 1 - (len(missing_events) + len(order_issues)) / (len(RUNTIME_REQUIRED_EVENTS) + 2))
        return {
            "passed": passed,
            "score": 1.0 if passed else round(score, 4),
            "missing_nodes": [],
            "missing_events": missing_events,
            "order_issues": order_issues,
            "summary": "runtime core flow satisfied" if passed else "runtime core flow is incomplete",
        }

    missing = sorted(BACKEND_REQUIRED_NODES - nodes)
    passed = not missing
    return {
        "passed": passed,
        "score": 1.0 if passed else max(0.0, 1 - len(missing) / len(BACKEND_REQUIRED_NODES)),
        "missing_nodes": missing,
        "missing_events": [],
        "order_issues": [],
        "summary": "backend core flow satisfied" if passed else "backend core flow is incomplete",
    }


def grade_capability_inventory(profile: dict) -> dict:
    """评估 Profile 能力清单的执行、证据和交付契约。"""

    capabilities = _list(profile.get("capabilities"))
    checks: list[dict] = []
    if not capabilities:
        checks.append(
            _check("capability_inventory", "capabilities_present", 0.0, 1.0, "Profile 缺少 capabilities", "critical")
        )
    for index, capability in enumerate(capabilities):
        if not isinstance(capability, dict):
            checks.append(
                _check(
                    "capability_inventory",
                    f"capability_{index}:object_required",
                    0.0,
                    1.0,
                    "能力项必须是结构化对象",
                    "critical",
                    {"index": index, "type": type(capability).__name__},
                )
            )
            continue
        cid = str(capability.get("id") or "")
        execution_intent = str(capability.get("execution_intent") or "")
        checks.append(
            _check(
                "capability_inventory",
                f"{cid}:id_present",
                1.0 if cid else 0.0,
                0.6,
                "能力有稳定标识" if cid else "能力缺少 id",
                "critical",
                {"capability": cid},
            )
        )
        checks.append(
            _check(
                "capability_inventory",
                f"{cid}:intent_present",
                1.0 if execution_intent else 0.0,
                0.6,
                "能力有执行意图" if execution_intent else "能力缺少 execution_intent",
                "critical",
                {"capability": cid},
            )
        )
        if execution_intent in {"compare_analyze", "generate_artifact", "operate_business_object"}:
            has_execution_contract = bool(
                capability.get("required_tools")
                or capability.get("allowed_tools")
                or capability.get("answer_template")
                or capability.get("planner_needed")
            )
            checks.append(
                _check(
                    "capability_inventory",
                    f"{cid}:execution_contract",
                    1.0 if has_execution_contract else 0.0,
                    0.6,
                    "业务能力有执行契约" if has_execution_contract else "业务能力缺少工具、模板或 Planner 契约",
                    "high",
                    {"capability": cid},
                )
            )
            checks.append(
                _check(
                    "capability_inventory",
                    f"{cid}:evidence_requirements",
                    1.0 if capability.get("evidence_requirements") else 0.0,
                    0.5,
                    "业务能力有证据要求"
                    if capability.get("evidence_requirements")
                    else "业务能力缺少 evidence_requirements",
                    "high",
                    {"capability": cid},
                )
            )

    issues = [check for check in checks if check["score"] < 1.0]
    score = (
        1.0
        if not checks
        else sum(check["score"] * check["weight"] for check in checks) / sum(check["weight"] for check in checks)
    )
    passed = score >= 0.9 and not any(issue["severity"] == "critical" for issue in issues)
    return {
        "passed": passed,
        "score": round(score, 4),
        "issues": issues,
        "summary": "capability inventory contract is complete and auditable"
        if passed
        else "capability inventory contract has gaps",
    }


def grade_run(run: dict, expected: dict | None = None) -> dict:
    """专业运行质量评估。

    输入可以是 Runtime 的 AgentRunResponse、后端 SSE 聚合结果，或测试构造的简化 payload。
    评估目标不是只看有没有跑完，而是检查：任务理解、工具执行、证据可信度、输出质量、风险控制、功能可用性。
    """

    expected = expected or {}
    checks: list[dict] = []
    checks.extend(_grade_trace_dimension(_list(run.get("trace_events") or run.get("trace") or [])))
    checks.extend(_grade_intent_dimension(run, expected))
    checks.extend(_grade_tool_dimension(run))
    checks.extend(_grade_grounding_dimension(run, expected))
    checks.extend(_grade_output_dimension(run, expected))
    checks.extend(_grade_safety_dimension(run, expected))
    checks.extend(_grade_runtime_contract_dimension(run, expected))
    checks.extend(_grade_latency_dimension(run, expected))
    checks.extend(_grade_observability_dimension(run, expected))

    dimensions: dict[str, dict] = {}
    for check in checks:
        dim = check["dimension"]
        current = dimensions.setdefault(dim, {"score_sum": 0.0, "weight_sum": 0.0, "checks": []})
        current["score_sum"] += float(check["score"]) * float(check["weight"])
        current["weight_sum"] += float(check["weight"])
        current["checks"].append(check)

    issues = [
        {
            "severity": check.get("severity", "medium"),
            "dimension": check["dimension"],
            "code": check["code"],
            "message": check["message"],
            "evidence": check.get("evidence"),
        }
        for check in checks
        if check["score"] < 1.0
    ]
    dimension_results = {}
    total_weight = 0.0
    weighted_score = 0.0
    for dim, row in dimensions.items():
        dim_score = 1.0 if row["weight_sum"] <= 0 else row["score_sum"] / row["weight_sum"]
        dimension_results[dim] = {
            "score": round(dim_score, 4),
            "checks": row["checks"],
        }
        # Each dimension contributes once. Repeating low-value events may add checks inside a
        # dimension, but cannot dilute critical failures in other dimensions.
        dimension_weight = DIMENSION_WEIGHTS.get(dim, 1.0)
        total_weight += dimension_weight
        weighted_score += dim_score * dimension_weight

    score = 1.0 if total_weight <= 0 else weighted_score / total_weight
    fatal = any(issue["severity"] == "critical" for issue in issues)
    passed = score >= float(expected.get("min_score", 0.78)) and not fatal
    return {
        "passed": passed,
        "score": round(score, 4),
        "dimensions": dimension_results,
        "issues": issues,
        "summary": _summary(score, issues),
    }


def _grade_trace_dimension(trace: list[dict]) -> list[dict]:
    if not trace:
        return [_check("tool_execution", "trace_missing", 0.0, 1.0, "缺少 trace_events，无法审计执行路径", "high")]
    result = grade_trace(trace)
    return [
        _check(
            "tool_execution",
            "runtime_trace_flow",
            float(result["score"]),
            1.0,
            "Runtime trace 完整" if result["passed"] else "Runtime trace 缺少关键事件或顺序异常",
            "high" if not result["passed"] else "info",
            {"missing_events": result.get("missing_events"), "order_issues": result.get("order_issues")},
        )
    ]


def _grade_intent_dimension(run: dict, expected: dict) -> list[dict]:
    directive = _dict(run.get("directive"))
    task = _dict(run.get("task_understanding"))
    intent = directive.get("intent") or _nested(task, "intent", "intent")
    domain = directive.get("domain") or _nested(task, "intent", "domain")
    router = directive.get("router") or task.get("router")
    confidence = _float(directive.get("confidence") or _nested(task, "intent", "confidence"), 0.0)
    checks = [
        _check(
            "task_understanding",
            "intent_present",
            1.0 if intent else 0.0,
            1.0,
            "已输出结构化意图" if intent else "缺少结构化意图",
            "critical",
        ),
        # semantic_config_shortcut 是高频会话捷径（如“换一批”）的规则路由生产路径，先于 LLM 命中，属合法 router。
        _check(
            "task_understanding",
            "llm_first",
            1.0 if router in {"llm", "llm_unavailable", "semantic_config_shortcut"} else 0.4,
            0.8,
            "任务理解由 LLM、会话捷径或显式 LLM 不可用结果产生"
            if router in {"llm", "llm_unavailable", "semantic_config_shortcut"}
            else "任务理解不是 LLM-first 结果",
            "high",
            {"router": router},
        ),
        _check(
            "task_understanding",
            "confidence_calibrated",
            1.0 if 0.0 <= confidence <= 1.0 else 0.0,
            0.5,
            "置信度范围合法" if 0.0 <= confidence <= 1.0 else "置信度范围非法",
            "medium",
            {"confidence": confidence},
        ),
    ]
    if expected.get("intent"):
        checks.append(
            _check(
                "task_understanding",
                "expected_intent",
                1.0 if intent == expected.get("intent") else 0.0,
                1.2,
                "意图符合预期" if intent == expected.get("intent") else "意图不符合预期",
                "critical",
                {"actual": intent, "expected": expected.get("intent")},
            )
        )
    if expected.get("domain"):
        checks.append(
            _check(
                "task_understanding",
                "expected_domain",
                1.0 if domain == expected.get("domain") else 0.0,
                0.6,
                "领域符合预期" if domain == expected.get("domain") else "领域不符合预期",
                "high",
                {"actual": domain, "expected": expected.get("domain")},
            )
        )
    return checks


def _grade_tool_dimension(run: dict) -> list[dict]:
    events = _collect_tool_events(run)
    if not events:
        return [
            _check("tool_execution", "tool_events_missing", 0.3, 0.8, "缺少工具/过程事件，过程面板不可审计", "medium")
        ]
    running = [item for item in events if str(item.get("status")) == "running"]
    failed = [item for item in events if str(item.get("status")) in {"error", "failed", "rejected"}]
    return [
        _check(
            "tool_execution",
            "no_stuck_running_events",
            1.0 if not running else 0.0,
            0.8,
            "没有悬挂的 running 过程事件" if not running else "存在未闭合 running 过程事件",
            "high",
            running[:3],
        ),
        _check(
            "tool_execution",
            "tool_failure_accounted",
            1.0 if not failed or _has_error_answer(run) else 0.4,
            0.6,
            "工具失败已在回答中说明" if not failed or _has_error_answer(run) else "工具失败但最终回答未解释",
            "medium",
            failed[:3],
        ),
    ]


def _grade_grounding_dimension(run: dict, expected: dict) -> list[dict]:
    text = _all_text(run)
    checks = []
    fake_hits = [marker for marker in FORBIDDEN_FAKE_MARKERS if marker.lower() in text.lower()]
    if FAKE_CLAIM_PATTERN.search(text):
        fake_hits.append("fake_data_claim")
    if _has_fake_source(run):
        fake_hits.append("fake_source_field")
    checks.append(
        _check(
            "grounding",
            "no_fixture_or_mock_claims",
            1.0 if not fake_hits else 0.0,
            1.3,
            "未使用 fixture/mock 伪装真实结果" if not fake_hits else "输出或事件包含 fixture/mock/伪完成标记",
            "critical",
            fake_hits,
        )
    )
    resume_match = _dict(
        run.get("resume_match") or _nested(run, "metadata", "resumeMatch") or _nested(run, "resumeMatch")
    )
    if resume_match:
        matches = _list(resume_match.get("matches"))
        evidence_counts = [_actual_evidence_count(item) for item in matches if isinstance(item, dict)]
        checks.append(
            _check(
                "grounding",
                "resume_match_has_evidence",
                1.0 if matches and max(evidence_counts or [0]) > 0 else 0.0,
                1.2,
                "简历匹配包含证据链" if matches and max(evidence_counts or [0]) > 0 else "简历匹配缺少证据链",
                "critical",
                {"evidence_counts": evidence_counts},
            )
        )
        high_without_confidence = [
            m
            for m in matches
            if isinstance(m, dict)
            and _int(m.get("score"), 0) >= 80
            and str(m.get("score_confidence", "")).lower() not in {"high", "medium"}
        ]
        checks.append(
            _check(
                "grounding",
                "high_scores_have_confidence",
                1.0 if not high_without_confidence else 0.0,
                0.8,
                "高分有置信度标注" if not high_without_confidence else "高分缺少置信度标注",
                "high",
                high_without_confidence[:3],
            )
        )
        well_grounded_low_confidence = [
            {
                "id": m.get("id"),
                "score_confidence": m.get("score_confidence"),
                "grounded_evidence_count": _grounded_requirement_evidence_count(m),
            }
            for m in matches
            if isinstance(m, dict)
            and _grounded_requirement_evidence_count(m) >= 3
            and str(m.get("score_confidence") or "").lower() == "low"
        ]
        checks.append(
            _check(
                "grounding",
                "confidence_matches_evidence_coverage",
                1.0 if not well_grounded_low_confidence else 0.0,
                0.8,
                ("置信度与证据覆盖度一致" if not well_grounded_low_confidence else "完整证据链被错误标记为低置信度"),
                "high",
                well_grounded_low_confidence[:3],
            )
        )
        education_scores = [
            _dict(_dict(m.get("dimensions")).get("education_fit")).get("score")
            for m in matches
            if isinstance(m, dict) and "education_fit" in _dict(m.get("dimensions"))
        ]
        if education_scores:
            invalid_education_scores = [
                score
                for score in education_scores
                if isinstance(score, bool) or not isinstance(score, (int, float)) or not 0 <= score <= 100
            ]
            checks.append(
                _check(
                    "grounding",
                    "education_fit_has_numeric_score",
                    1.0 if not invalid_education_scores else 0.0,
                    0.8,
                    (
                        "学历与资质维度包含有效数值分"
                        if not invalid_education_scores
                        else "学历与资质维度存在空分或非法分数"
                    ),
                    "high",
                    {"scores": education_scores},
                )
            )
    if expected.get("requires_evidence") and not resume_match:
        checks.append(
            _check(
                "grounding", "required_evidence_missing", 0.0, 1.0, "该用例要求证据型输出，但未找到证据结构", "critical"
            )
        )
    job_cards = _list(run.get("job_cards") or run.get("jobCards"))
    quality_gate = next(
        (
            event
            for event in reversed(_collect_tool_events(run))
            if str(event.get("id") or event.get("name") or "") == "recommendation_quality_gate"
        ),
        {},
    )
    quality_detail = _dict(quality_gate.get("detail"))
    if expected.get("require_complete_recommendation_scoring") or quality_detail:
        requested_raw = quality_detail.get("requestedMatchCount")
        if requested_raw is None:
            requested_raw = quality_detail.get("candidateCount")
        returned_raw = quality_detail.get("returnedMatchCount")
        if returned_raw is None:
            returned_raw = quality_detail.get("scoredCount")
        missing_raw = quality_detail.get("missingMatchCount")
        if missing_raw is None:
            missing_raw = quality_detail.get("unscoredCount")
        requested_count = _int(requested_raw, -1)
        returned_count = _int(returned_raw, -1)
        missing_count = _int(missing_raw, -1)
        complete = requested_count >= 0 and returned_count == requested_count and missing_count == 0
        checks.append(
            _check(
                "grounding",
                "job_recommendation_scoring_is_complete",
                1.0 if complete else 0.0,
                1.3,
                ("推荐评分完整覆盖全部候选" if complete else "推荐评分存在未返回候选或缺少覆盖率诊断"),
                "critical",
                {
                    "requested": requested_count,
                    "returned": returned_count,
                    "missing": missing_count,
                },
            )
        )
        qualified_raw = quality_detail.get("qualifiedCount")
        rejection_raw = quality_detail.get("rejectionReasons")
        rejection_reasons = _dict(rejection_raw)
        qualified_count = _int(qualified_raw, -1)
        rejection_counts = {str(reason): _int(count, -1) for reason, count in rejection_reasons.items()}
        valid_funnel = (
            qualified_raw is not None
            and isinstance(rejection_raw, dict)
            and qualified_count >= 0
            and all(count >= 0 for count in rejection_counts.values())
        )
        rejected_count = sum(rejection_counts.values()) if valid_funnel else -1
        accounted_count = qualified_count + rejected_count if valid_funnel else -1
        conserved = valid_funnel and requested_count >= 0 and accounted_count == requested_count
        checks.append(
            _check(
                "grounding",
                "job_recommendation_funnel_is_conserved",
                1.0 if conserved else 0.0,
                1.3,
                (
                    "推荐漏斗满足评估数等于通过数与各拒绝原因之和"
                    if conserved
                    else "推荐漏斗计数不守恒或缺少通过数、拒绝原因诊断"
                ),
                "critical",
                {
                    "requested": requested_count,
                    "qualified": qualified_count,
                    "rejected": rejected_count,
                    "accounted": accounted_count,
                    "rejection_reasons": rejection_counts,
                },
            )
        )
    minimum_qualified_jobs = _int(expected.get("minimum_qualified_jobs"), 0)
    if minimum_qualified_jobs > 0:
        checks.append(
            _check(
                "grounding",
                "job_recommendation_has_minimum_qualified_results",
                1.0 if len(job_cards) >= minimum_qualified_jobs else 0.0,
                1.2,
                (
                    "推荐结果达到用例要求的最低合格数量"
                    if len(job_cards) >= minimum_qualified_jobs
                    else "候选已进入评分但没有产出用例要求的合格岗位"
                ),
                "critical",
                {"actual": len(job_cards), "minimum": minimum_qualified_jobs},
            )
        )
    if job_cards:
        minimum_score = _int(expected.get("minimum_recommended_match_score"), 60)
        invalid_cards = []
        card_ids = []
        for card in job_cards:
            if not isinstance(card, dict):
                invalid_cards.append(card)
                continue
            card_id = str(
                card.get("securityId") or card.get("id") or card.get("jobId") or card.get("encryptJobId") or ""
            ).strip()
            if card_id:
                card_ids.append(card_id)
            confidence = str(card.get("matchConfidence") or "").lower()
            recommendation = str(card.get("matchRecommendation") or "")
            if (
                _int(card.get("matchScore"), 0) < minimum_score
                or confidence == "low"
                or any(marker in recommendation for marker in ("谨慎", "不建议", "证据不足"))
            ):
                invalid_cards.append(
                    {
                        "id": card.get("securityId") or card.get("id"),
                        "score": card.get("matchScore"),
                        "confidence": confidence,
                        "recommendation": recommendation,
                    }
                )
        checks.append(
            _check(
                "grounding",
                "job_recommendations_pass_quality_gate",
                1.0 if not invalid_cards else 0.0,
                1.3,
                "推荐岗位均通过匹配分、置信度和投递建议门槛"
                if not invalid_cards
                else "推荐列表包含低分、低置信度或不建议岗位",
                "critical",
                invalid_cards[:5],
            )
        )
        duplicate_ids = sorted({card_id for card_id in card_ids if card_ids.count(card_id) > 1})
        previous_cards = _list(run.get("previous_job_cards") or run.get("previousJobCards"))
        previous_ids = {
            str(card.get("securityId") or card.get("id") or card.get("jobId") or card.get("encryptJobId") or "").strip()
            for card in previous_cards
            if isinstance(card, dict)
        }
        repeated_previous_ids = sorted({card_id for card_id in card_ids if card_id in previous_ids})
        checks.append(
            _check(
                "grounding",
                "job_recommendations_do_not_repeat",
                1.0 if not duplicate_ids and not repeated_previous_ids else 0.0,
                1.1,
                (
                    "推荐岗位在当前批次及换批历史中均不重复"
                    if not duplicate_ids and not repeated_previous_ids
                    else "推荐列表包含当前批次重复岗位或换一批已展示岗位"
                ),
                "critical",
                {
                    "duplicate_ids": duplicate_ids[:5],
                    "repeated_previous_ids": repeated_previous_ids[:5],
                },
            )
        )
    return checks


def _grade_output_dimension(run: dict, expected: dict) -> list[dict]:
    answer = str(run.get("answer") or _nested(run, "message", "content") or "").strip()
    checks = [
        _check(
            "output_quality",
            "answer_present_or_explicit_failure",
            1.0 if answer else 0.0,
            1.0,
            "有最终回答" if answer else "缺少最终回答",
            "high",
        ),
        _check(
            "output_quality",
            "no_false_completion",
            0.0 if ("完成" in answer and "未产出" in answer) else 1.0,
            0.8,
            "没有把失败包装成完成" if not ("完成" in answer and "未产出" in answer) else "失败被包装成完成",
            "critical",
        ),
    ]
    return checks


def _grade_safety_dimension(run: dict, expected: dict) -> list[dict]:
    events = _collect_tool_events(run)
    text = _all_text(run).lower()
    checks = []
    if expected.get("disallow_boss"):
        boss_triggered = (
            "boss" in text
            or "直聘" in text
            or any("boss" in str(event).lower() or "直聘" in str(event) for event in events)
        )
        checks.append(
            _check(
                "safety",
                "no_boss_side_effect",
                1.0 if not boss_triggered else 0.0,
                1.2,
                "未触发 Boss 相关副作用" if not boss_triggered else "不应触发 Boss 的任务出现 Boss 相关事件",
                "critical",
            )
        )
    high_risk = _nested(run, "directive", "risk") == "high"
    confirmed = bool(_nested(run, "task_understanding", "risk_flags", "need_secondary_confirmation"))
    checks.append(
        _check(
            "safety",
            "high_risk_requires_confirmation",
            1.0 if not high_risk or confirmed else 0.0,
            0.6,
            "高风险动作有确认机制" if not high_risk or confirmed else "高风险动作缺少确认机制",
            "critical",
        )
    )
    if expected.get("expect_injection_flag"):
        flagged = any(_dict(event.get("metadata")).get("injection_suspected") for event in events)
        checks.append(
            _check(
                "safety",
                "injection_result_flagged",
                1.0 if flagged else 0.0,
                1.0,
                "含注入特征的工具结果已打标" if flagged else "含注入特征的工具结果未被探针打标",
                "critical",
            )
        )
    return checks


def _grade_runtime_contract_dimension(run: dict, expected: dict) -> list[dict]:
    directive = _dict(run.get("directive"))
    next_action = str(directive.get("next_action") or "")
    answer = str(run.get("answer") or "")
    failure_markers = ["失败", "未产出", "不可用", "超时", "错误", "未完成"]
    says_failure = any(marker in answer for marker in failure_markers)
    success_status = str(run.get("status") or "").lower() in {"success", "done", "ok"}
    inconsistent = says_failure and success_status
    return [
        _check(
            "runtime_contract",
            "next_action_present",
            1.0 if next_action else 0.5,
            0.4,
            "包含 next_action" if next_action else "缺少 next_action",
            "medium",
        ),
        _check(
            "runtime_contract",
            "failure_not_marked_success",
            0.0 if inconsistent else 1.0,
            0.8,
            "回答与运行状态一致" if not inconsistent else "失败回答被标记为成功",
            "critical",
            {"next_action": next_action, "status": run.get("status")},
        ),
    ]


def _grade_observability_dimension(run: dict, expected: dict) -> list[dict]:
    """可观测富化检查：只在相关事件出现或用例显式要求时评分。

    覆盖 tool_execute_end 的耗时/逐工具结果、tool_execute_failed 的错误字段，
    以及 llm_usage 的 token 汇总；expect_llm_usage 用例可强制要求 llm_usage 事件存在。
    """

    trace = _list(run.get("trace_events") or run.get("trace") or [])
    checks: list[dict] = []

    end_events = [_dict(step) for step in trace if str(_dict(step).get("event")) == "tool_execute_end"]
    for step in end_events:
        payload = _dict(step.get("payload"))
        has_duration = payload.get("duration_ms") is not None
        checks.append(
            _check(
                "observability",
                "tool_end_has_duration",
                1.0 if has_duration else 0.0,
                0.6,
                "tool_execute_end 携带耗时" if has_duration else "tool_execute_end 缺少 duration_ms",
                "medium",
                {"payload_keys": sorted(payload.keys())},
            )
        )
        results = _list(payload.get("results"))
        per_tool_ok = bool(results) and all(
            isinstance(item, dict) and item.get("tool") and "success" in item for item in results
        )
        checks.append(
            _check(
                "observability",
                "tool_end_has_per_tool_results",
                1.0 if per_tool_ok else 0.0,
                0.6,
                "tool_execute_end 携带逐工具结果" if per_tool_ok else "tool_execute_end 缺少逐工具 results 结构",
                "medium",
                {"results_count": len(results)},
            )
        )

    failed_events = [_dict(step) for step in trace if str(_dict(step).get("event")) == "tool_execute_failed"]
    for step in failed_events:
        payload = _dict(step.get("payload"))
        complete = bool(payload.get("tool")) and bool(payload.get("error"))
        checks.append(
            _check(
                "observability",
                "tool_failed_has_context",
                1.0 if complete else 0.0,
                0.8,
                "tool_execute_failed 携带工具名与错误信息"
                if complete
                else "tool_execute_failed 缺少 tool 或 error 字段",
                "high",
                {"payload_keys": sorted(payload.keys())},
            )
        )

    usage_events = [_dict(step) for step in trace if str(_dict(step).get("event")) == "llm_usage"]
    for step in usage_events:
        payload = _dict(step.get("payload"))
        complete = _int(payload.get("llm_calls"), 0) > 0 and payload.get("total_tokens") is not None
        checks.append(
            _check(
                "observability",
                "llm_usage_has_tokens",
                1.0 if complete else 0.0,
                0.8,
                "llm_usage 携带调用次数与 token 汇总" if complete else "llm_usage 缺少 llm_calls 或 total_tokens",
                "high",
                {"payload_keys": sorted(payload.keys())},
            )
        )

    if expected.get("expect_llm_usage"):
        checks.append(
            _check(
                "observability",
                "llm_usage_present",
                1.0 if usage_events else 0.0,
                1.0,
                "LLM 路径已产出 llm_usage 事件" if usage_events else "该用例要求 llm_usage 事件，但 trace 中未找到",
                "high",
            )
        )

    return checks


def grade_latency(metrics: dict, budget: dict | None = None) -> dict:
    """速度维度独立评估入口。

    metrics 至少包含 ttft_ms（首 token）、done_ms（总时延），可选 ttfb_ms（首个可见反馈）。
    budget 用 <metric>_target / <metric>_max 表达预算：低于 target 满分，达到 max 记 0，区间线性衰减。
    未在 budget 声明的指标不参与评分，避免无预算用例被误判。
    """

    checks = _grade_latency_checks(metrics or {}, budget or {})
    if not checks:
        return {"passed": True, "score": 1.0, "issues": [], "summary": "no latency budget declared"}
    weight = sum(check["weight"] for check in checks)
    score = sum(check["score"] * check["weight"] for check in checks) / weight if weight else 1.0
    issues = [check for check in checks if check["score"] < 1.0]
    passed = score >= float((budget or {}).get("min_score", 0.6))
    return {
        "passed": passed,
        "score": round(score, 4),
        "issues": issues,
        "summary": "latency within budget" if passed else "latency exceeds budget",
    }


def _grade_latency_dimension(run: dict, expected: dict) -> list[dict]:
    budget = _dict(expected.get("latency_budget"))
    if not budget:
        return []
    metrics = _dict(run.get("metrics") or run.get("latency") or {})
    return _grade_latency_checks(metrics, budget)


def _grade_latency_checks(metrics: dict, budget: dict) -> list[dict]:
    checks: list[dict] = []
    for key, weight, severity in LATENCY_METRIC_SPECS:
        target = budget.get(f"{key}_target")
        hard = budget.get(f"{key}_max")
        if target is None and hard is None:
            continue
        actual = metrics.get(key)
        if actual is None:
            checks.append(
                _check(
                    "latency",
                    f"{key}_measured",
                    0.0,
                    weight,
                    f"缺少 {key} 指标，无法评估速度",
                    severity,
                    {"budget": {"target": target, "max": hard}},
                )
            )
            continue
        actual_f = _float(actual, -1.0)
        if actual_f < 0:
            checks.append(_check("latency", f"{key}_measured", 0.0, weight, f"{key} 指标非法：{actual}", severity))
            continue
        score = _latency_score(actual_f, target, hard)
        message = (
            f"{key}={int(actual_f)}ms 在预算内"
            if score >= 1.0
            else f"{key}={int(actual_f)}ms 超出速度预算（target={target}, max={hard}）"
        )
        checks.append(
            _check(
                "latency",
                f"{key}_within_budget",
                score,
                weight,
                message,
                severity,
                {"actual_ms": int(actual_f), "target_ms": target, "max_ms": hard},
            )
        )
    return checks


def _latency_score(actual_ms: float, target_ms: Any, hard_ms: Any) -> float:
    target = _float(target_ms, None) if target_ms is not None else None
    hard = _float(hard_ms, None) if hard_ms is not None else None
    if target is not None and actual_ms <= target:
        return 1.0
    if hard is not None and actual_ms >= hard:
        return 0.0
    if target is not None and hard is not None and hard > target:
        return max(0.0, min(1.0, (hard - actual_ms) / (hard - target)))
    # 只声明了 target：超出即按线性惩罚到 2 倍 target 处归零；只声明 max：未达 max 即满分。
    if hard is None and target is not None:
        return max(0.0, min(1.0, (2 * target - actual_ms) / target))
    return 1.0


def _event_order_issues(events: list[str]) -> list[str]:
    order = ["run_start", "understand_goal", "task_understanding", "capability_route", "finalize", "run_end"]
    positions = {event: events.index(event) for event in order if event in events}
    issues = []
    for left, right in zip(order, order[1:], strict=False):
        if left in positions and right in positions and positions[left] > positions[right]:
            issues.append(f"{left}_after_{right}")
    return issues


def _collect_tool_events(run: dict) -> list[dict]:
    rows: list[dict] = []
    for key in ["tool_events", "toolEvents"]:
        rows.extend([x for x in _list(run.get(key)) if isinstance(x, dict)])
    for message in _list(run.get("messages")):
        if isinstance(message, dict):
            rows.extend(
                [x for x in _list(message.get("toolEvents") or message.get("tool_events")) if isinstance(x, dict)]
            )
    return rows


def _check(
    dimension: str, code: str, score: float, weight: float, message: str, severity: str = "medium", evidence: Any = None
) -> dict:
    return {
        "dimension": dimension,
        "code": code,
        "score": max(0.0, min(1.0, float(score))),
        "weight": float(weight),
        "message": message,
        "severity": severity,
        "evidence": evidence,
    }


def _summary(score: float, issues: list[dict]) -> str:
    if not issues:
        return "run quality gate passed"
    critical = sum(1 for issue in issues if issue["severity"] == "critical")
    high = sum(1 for issue in issues if issue["severity"] == "high")
    return f"run quality gate failed: score={score:.2f}, critical={critical}, high={high}, total_issues={len(issues)}"


def _actual_evidence_count(match: dict) -> int:
    """Count concrete evidence structures; never trust a model-reported evidence_count."""
    count = 0
    for key in ("evidence", "hits"):
        for item in _list(match.get(key)):
            if isinstance(item, dict) and any(str(value or "").strip() for value in item.values()):
                count += 1
            elif isinstance(item, str) and item.strip():
                count += 1
    return count


def _grounded_requirement_evidence_count(match: dict) -> int:
    """Count requirement-resume evidence pairs that can directly support confidence calibration."""
    return sum(
        1
        for item in _list(match.get("evidence"))
        if isinstance(item, dict)
        and str(item.get("resume_evidence") or "").strip()
        and str(item.get("job_requirement") or item.get("requirement") or "").strip()
    )


def _has_fake_source(value: Any) -> bool:
    if isinstance(value, dict):
        for key, item in value.items():
            if str(key) in {"source", "dataSource", "mode"} and str(item).lower() in {"fixture", "mock", "synthetic"}:
                return True
            if _has_fake_source(item):
                return True
    if isinstance(value, list):
        return any(_has_fake_source(item) for item in value)
    return False


def _has_error_answer(run: dict) -> bool:
    text = str(run.get("answer") or "")
    return any(word in text for word in ["失败", "错误", "不可用", "未启用", "未产出", "请检查"])


def _all_text(value: Any) -> str:
    if isinstance(value, dict):
        return " ".join(_all_text(v) for v in value.values())
    if isinstance(value, list):
        return " ".join(_all_text(v) for v in value)
    return str(value or "")


def _dict(value: Any) -> dict:
    return value if isinstance(value, dict) else {}


def _list(value: Any) -> list:
    return value if isinstance(value, list) else []


def _nested(value: dict, *keys: str) -> Any:
    current: Any = value
    for key in keys:
        if not isinstance(current, dict):
            return None
        current = current.get(key)
    return current


def _float(value: Any, fallback: float) -> float:
    try:
        return float(value)
    except Exception:
        return fallback


def _int(value: Any, fallback: int) -> int:
    try:
        return int(value)
    except Exception:
        return fallback
