"""TaskUnderstandingResult 组装器。

把“路由结果 + 槽位”组装为稳定协议 TaskUnderstandingResult 是任务理解中独立于
路由策略（LLM 或离线语义）的职责。Router 只决定选中哪个能力卡与槽位，
由本组装器统一推导澄清、风险、planner 约束、next_action 与元数据，保证两种路由
路径产出一致的结构。
"""

from __future__ import annotations

from typing import Any, Dict, Iterable, List

from app.core.capability.models import CapabilityCard, ProfileDefinition
from app.core.capability.registry import CapabilityRegistry
from app.core.intent.text_match import score_to_confidence  # noqa: F401  (保留供潜在复用)
from app.models.schemas import (
    CapabilityCandidate,
    ClarificationDecision,
    ContextUnderstanding,
    IntentUnderstanding,
    PlannerConstraints,
    QueryRewrite,
    RiskFlags,
    RoutingUnderstanding,
    SlotPrecheck,
    SlotValue,
    TaskUnderstandingResult,
)


class TaskResultBuilder:
    def __init__(self, capability_registry: CapabilityRegistry):
        self.capability_registry = capability_registry

    def build(
        self,
        profile: ProfileDefinition,
        message: str,
        trace_id: str,
        capability: CapabilityCard,
        candidates: List[CapabilityCandidate],
        confidence: float,
        slots: Dict[str, Any],
        router: str,
        reason: str,
    ) -> TaskUnderstandingResult:
        missing_required = self.missing_required(capability, slots)
        missing_optional = [slot for slot in capability.optional_slots if slot not in slots]
        clarification_needed = bool(missing_required)
        clarification_question = capability.clarification_question or self._default_clarification_question(
            missing_required
        )
        risk_level = capability.risk or "low"
        planner_needed = bool(capability.planner_needed or capability.next_action == "run_runtime_planner")
        next_action = "clarify" if missing_required else capability.next_action
        answer = capability.answer_template if capability.answer_template and not missing_required else None
        selected = candidates[0] if candidates else self.candidate(capability, confidence, reason, missing_required)

        slot_source = "llm" if router == "llm" else "semantic_fallback"
        slot_status: Dict[str, SlotValue] = {}
        for key, value in slots.items():
            slot_status[key] = SlotValue(status="filled", value=value, source=slot_source)
        for key in missing_required:
            slot_status[key] = SlotValue(status="missing_required", value=None, source="capability_card")
        for key in missing_optional:
            slot_status.setdefault(key, SlotValue(status="missing_optional", value=None, source="capability_card"))

        return TaskUnderstandingResult(
            trace_id=trace_id,
            profile=profile.id,
            router=router,
            original_query=message,
            rewritten_query=QueryRewrite(
                resolved_query=message,
                retrieval_query=self._build_retrieval_query(message, capability),
                planner_query=message,
            ),
            context=ContextUnderstanding(
                dependency="required" if capability.context_dependencies else "optional",
                context_type=list(capability.context_dependencies),
                memory_policy={
                    "need_session_memory": bool(capability.context_dependencies),
                    "need_user_preference": "profile" in capability.context_dependencies,
                },
            ),
            intent=IntentUnderstanding(
                execution_intent=capability.execution_intent,
                domain=capability.domain,
                intent=capability.intent,
                business_intents=capability.business_tags,
                capability_intents=capability.capability_tags or [capability.id],
                confidence=confidence,
                secondary=capability.secondary,
            ),
            routing=RoutingUnderstanding(
                selected_capability=selected,
                candidate_capabilities=candidates or [selected],
                preferred_domains=[capability.domain],
                blocked_domains=[],
            ),
            slots=SlotPrecheck(
                filled=slots,
                status=slot_status,
                missing_required=missing_required,
                missing_optional=missing_optional,
                need_confirm=[] if risk_level != "high" else list(slots.keys()),
            ),
            clarification=ClarificationDecision(
                needed=clarification_needed,
                blocking=clarification_needed,
                reason="missing_required_slots" if clarification_needed else None,
                question=clarification_question if clarification_needed else None,
                suggested_options=[],
            ),
            risk_flags=RiskFlags(
                risk_level=risk_level,
                high_risk_operation=risk_level == "high",
                need_secondary_confirmation=risk_level == "high",
                safety_blocked=capability.next_action == "reject_with_reason",
            ),
            planner_constraints=PlannerConstraints(
                planner_needed=planner_needed,
                max_candidate_skills=5,
                risk_level=risk_level,
                execution_mode=capability.execution_mode,
            ),
            next_action=next_action,
            answer=answer,
            metadata={
                "capability_reason": reason,
                "capability_contract": {
                    "required_tools": capability.required_tools,
                    "allowed_tools": capability.allowed_tools,
                    "evidence_requirements": capability.evidence_requirements,
                    "eval_rubric": capability.eval_rubric,
                },
            },
        )

    def build_llm_unavailable(self, profile: ProfileDefinition, message: str, trace_id: str) -> TaskUnderstandingResult:
        capability = self.default_capability(profile)
        result = self.build(
            profile=profile,
            message=message,
            trace_id=trace_id,
            capability=capability,
            candidates=[self.candidate(capability, 0.0, "llm_unavailable", [])],
            confidence=0.0,
            slots={},
            router="llm_unavailable",
            reason="llm_unavailable",
        )
        result.clarification.needed = True
        result.clarification.blocking = True
        result.clarification.reason = "llm_unavailable"
        result.clarification.question = "当前大模型未配置或不可访问，请配置 JOB_BUDDY_LLM_API_KEY 或检查模型服务。"
        result.answer = result.clarification.question
        result.next_action = "clarify"
        result.metadata["llm_required"] = True
        return result

    def candidate(
        self, capability: CapabilityCard, score: float, reason: str, missing_slots: Iterable[str]
    ) -> CapabilityCandidate:
        return CapabilityCandidate(
            capability_id=capability.id,
            domain=capability.domain,
            intent=capability.intent,
            score=round(float(score), 4),
            reason=reason,
            next_action=capability.next_action,
            execution_mode=capability.execution_mode,
            risk=capability.risk,
            missing_slots=list(missing_slots),
        )

    def missing_required(self, capability: CapabilityCard, slots: Dict[str, Any]) -> List[str]:
        return [slot for slot in capability.required_slots if slots.get(slot) in (None, "", [])]

    def default_capability(self, profile: ProfileDefinition) -> CapabilityCard:
        if profile.default_capability_id:
            found = profile.capability_by_id(profile.default_capability_id)
            if found:
                return found
        if profile.capabilities:
            return sorted(profile.capabilities, key=lambda item: item.id)[0]
        return self.capability_registry.get_profile("default").capabilities[0]

    def _build_retrieval_query(self, message: str, capability: CapabilityCard) -> str:
        return message.strip()

    def _default_clarification_question(self, missing_required: List[str]) -> str | None:
        if not missing_required:
            return None
        return "请补充必要信息：" + "、".join(missing_required)
