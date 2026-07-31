"""任务理解与能力路由编排器。

本服务只负责"选哪个能力卡 + 抽哪些槽位 + 用哪条路由路径"的编排：
- LLM 是默认路由器；会话捷径与受 Profile 约束的双重校验 Hint 是显式快路径。
- 生产链路的关键词/配置打分不单独授予能力、风险或工具权限。
- 只有测试或显式离线模式传入 allow_semantic_fallback=True 时，才使用配置驱动语义兜底。

具体职责被拆分到独立模块以保持单一职责：
- 纯文本匹配/归一化 -> app.core.intent.text_match
- 配置驱动槽位抽取   -> app.core.intent.slot_extractor.SlotExtractor
- 协议结果组装       -> app.core.intent.task_result_builder.TaskResultBuilder
"""

from __future__ import annotations

import json
import re
import time
from typing import Any, Dict, List, Optional, Tuple

from loguru import logger

from app.core.capability.models import CapabilityCard, ConversationShortcut, ProfileDefinition
from app.core.capability.registry import CapabilityRegistry
from app.core.common.settings import settings
from app.core.intent.slot_extractor import SlotExtractor
from app.core.intent.task_result_builder import TaskResultBuilder
from app.core.intent.text_match import (
    normalize_text,
    overlap_score,
    phrase_match,
    score_to_confidence,
    tokens,
)
from app.core.prompt.loader import PromptTemplateLoader
from app.core.utils.time_utils import TimeUtils
from app.core.workflow.registry import WorkflowRegistry
from app.models.schemas import (
    AgentRunRequest,
    ChatMessage,
    QueryRewrite,
    ResolvedReference,
    TaskUnderstandingResult,
)

DEFAULT_TASK_UNDERSTANDING_PROMPT = """
你是企业级 Agent Runtime 的任务理解与能力路由器。你只负责 Planner 前置理解，不执行工具。

你必须基于 Profile 能力卡，把用户输入转成结构化 TaskUnderstandingResult。请遵守：
1. 先结合 recent_messages、previous_slots 与当前消息解析指代，再选择候选能力。
2. resolved_query、retrieval_query 和 planner_query 必须是不依赖原对话也能理解的独立表达。
3. 输出必须是严格 JSON，省略 Markdown。
4. 只能选择能力卡中存在的 capability_id；无法可靠解析时返回 needs_clarification。
5. 高风险或缺少必填槽位的请求必须显式 needs_clarification 或 need_confirm。
6. 技术问答、代码生成、复杂工程任务应进入 runtime/open_domain 能力。
7. 用户明确要求联网、上网、搜索网页或 search/browse the web 时，只能选择允许 web_search 的能力；不要把明确联网请求当作无需工具的普通问答。
8. runtime_context.current_date 是运行时当前日期；“最新、近期、当前、今年”等相对时间不得被改写为其他年份。

输出 JSON schema:
{
  "resolved_query": "完整独立表达",
  "retrieval_query": "用于能力召回的语义表达",
  "planner_query": "用于 Planner 的可执行表达",
  "context_dependency": "none|optional|required",
  "context_type": [],
  "resolved_references": [],
  "reuse_previous_slots": false,
  "selected_capability_id": "capability id",
  "confidence": 0.0,
  "secondary": [],
  "slots": {},
  "missing_required": [],
  "needs_clarification": false,
  "clarification_question": null,
  "risk_level": "low|medium|high",
  "answer": null,
  "reason": "简短说明"
}
""".strip()


class TaskUnderstandingService:
    """任务理解与能力路由服务。

    LLM 是默认路由器；关键词/配置打分仅作显式降级。
    只有测试或显式离线模式传入 allow_semantic_fallback=True 时，才使用配置驱动语义兜底。
    """

    def __init__(
        self,
        capability_registry: CapabilityRegistry | None = None,
        llm_client=None,
        prompt_loader: PromptTemplateLoader | None = None,
        allow_semantic_fallback: bool = False,
        workflow_registry: WorkflowRegistry | None = None,
    ):
        self.capability_registry = capability_registry or CapabilityRegistry()
        self.llm_client = llm_client
        self.prompt_loader = prompt_loader or PromptTemplateLoader()
        self.allow_semantic_fallback = allow_semantic_fallback
        self.workflow_registry = workflow_registry or WorkflowRegistry(capability_registry=self.capability_registry)
        self.slot_extractor = SlotExtractor()
        self.result_builder = TaskResultBuilder(self.capability_registry)

    async def understand(
        self, request: AgentRunRequest, session_id: str, run_id: str, trace_id: str
    ) -> TaskUnderstandingResult:
        started_at = time.perf_counter()
        model_called = False
        metadata = request.metadata or {}
        profile_id = str(metadata.get("profile") or metadata.get("agent_profile") or "default")
        profile = self.capability_registry.get_profile(profile_id)
        message = self._last_user_message(request)
        previous_slots = self._dict(metadata.get("previous_slots"))

        # 高频会话捷径（如“换一批”）先于 LLM 命中：规则可判定的稳定意图直接短路，
        # 把一次任务理解 LLM 往返从首字延迟链路上移除。
        result: Optional[TaskUnderstandingResult] = self._understand_with_shortcut(
            profile, message, previous_slots, trace_id
        )
        if result is None:
            result = self._understand_with_validated_intent_hint(profile, request, message, trace_id)
        if result is None and self.llm_client:
            model_called = True
            result = await self._understand_with_model(profile, request, message, previous_slots, trace_id)
        if result is None and (self.allow_semantic_fallback or self.llm_client):
            # LLM 可访问但 JSON 不稳定时，回退到配置语义路由。
            result = self._understand_with_semantic_config(profile, request, message, previous_slots, trace_id)
        if result is None:
            result = self.result_builder.build_llm_unavailable(profile, message, trace_id)

        self._apply_explicit_tool_requirements(message, result)
        result.trace_id = trace_id
        result.profile = profile.id
        result.metadata.update({"session_id": session_id, "run_id": run_id})
        duration_ms = min(3_600_000, max(0, int((time.perf_counter() - started_at) * 1000)))
        result.metadata["understanding_metrics"] = {
            "duration_ms": duration_ms,
            "strategy": result.router,
            "model_called": model_called,
        }
        selected = result.routing.selected_capability
        workflow = self.workflow_registry.match(selected.capability_id if selected else None, profile.id)
        if workflow is not None:
            result.metadata["workflow"] = workflow.metadata()
        return result

    def _apply_explicit_tool_requirements(
        self,
        message: str,
        result: TaskUnderstandingResult,
    ) -> None:
        """把用户明确指定的低风险只读工具提升为本轮必需工具。

        Capability 的 allowlist 仍是权限上界；这里不能给能力卡未授权的工具扩权。
        该约束用于阻止 Backend 的 runtime_execute 快路径跳过用户明确要求的联网搜索。
        """
        if not self._explicit_web_search_requested(message):
            return
        metadata = result.metadata if isinstance(result.metadata, dict) else {}
        contract = metadata.get("capability_contract")
        if not isinstance(contract, dict):
            return
        allowed = {
            str(item)
            for item in [
                *(contract.get("required_tools") or []),
                *(contract.get("allowed_tools") or []),
            ]
        }
        if "web_search" not in allowed:
            return
        required = [str(item) for item in (contract.get("required_tools") or [])]
        if "web_search" not in required:
            required.append("web_search")
        contract["required_tools"] = required
        metadata["explicit_tool_requirements"] = ["web_search"]
        result.metadata = metadata

    def _explicit_web_search_requested(self, message: str) -> bool:
        text = str(message or "").strip().lower()
        if not text:
            return False
        if re.search(r"(?:不要|不用|无需|禁止|别)(?:再)?(?:联网|上网|网络|网页|web|online)", text):
            return False
        chinese_patterns = (
            r"(?:联网|上网)(?:查找|查询|搜索|检索|搜|查|浏览)",
            r"(?:搜索|查找|查询|检索|搜一下|查一下)(?:网页|网络|互联网)",
            r"(?:网页|网络|互联网)(?:搜索|查找|查询|检索)",
        )
        if any(re.search(pattern, text) for pattern in chinese_patterns):
            return True
        return bool(
            re.search(
                r"\b(?:web\s+search|search\s+the\s+web|browse\s+(?:the\s+)?(?:web|internet)|"
                r"look\s+up\s+online|online\s+search)\b",
                text,
            )
        )

    def _understand_with_validated_intent_hint(
        self,
        profile: ProfileDefinition,
        request: AgentRunRequest,
        message: str,
        trace_id: str,
    ) -> Optional[TaskUnderstandingResult]:
        policy = profile.intent_hint_fast_path
        metadata = request.metadata if isinstance(request.metadata, dict) else {}
        hint = metadata.get("intent_hint")
        if not policy.enabled or not isinstance(hint, dict):
            return None
        if len(request.messages or []) != 1 or request.messages[0].role != "user":
            return None
        if metadata.get("previous_slots") not in (None, {}):
            return None
        if metadata.get("attachments") not in (None, []):
            return None
        if hint.get("router") != "rule" or hint.get("needs_clarification") is not False:
            return None
        try:
            hint_confidence = float(hint.get("confidence"))
        except (TypeError, ValueError):
            return None
        if not 0.0 <= hint_confidence <= 1.0 or hint_confidence < policy.min_hint_confidence:
            return None

        capability = profile.capability_by_intent(str(hint.get("intent") or ""))
        if capability is None or capability.id not in policy.allowed_capability_ids:
            return None
        if capability.risk != "low" or capability.execution_mode != "STABLE_WORKFLOW":
            return None
        if (
            hint.get("domain") != capability.domain
            or hint.get("intent") != capability.intent
            or hint.get("next_action") != capability.next_action
            or hint.get("risk") != capability.risk
        ):
            return None

        slots = self.slot_extractor.extract(profile, message)
        scored = self._score_capabilities(profile, message, slots)
        semantic_confidence, reason, semantic_capability = scored[0]
        if semantic_capability.id != capability.id or semantic_confidence < policy.min_semantic_confidence:
            return None
        candidates = [
            self.result_builder.candidate(
                card,
                score,
                score_reason,
                self.result_builder.missing_required(card, slots),
            )
            for score, score_reason, card in scored[:5]
        ]
        result = self.result_builder.build(
            profile=profile,
            message=message,
            trace_id=trace_id,
            capability=capability,
            candidates=candidates,
            confidence=semantic_confidence,
            slots=slots,
            router="validated_intent_hint",
            reason=reason,
        )
        if result.clarification.needed:
            return None
        return result

    def build_directive(self, profile: ProfileDefinition, result: TaskUnderstandingResult) -> Optional[Dict[str, Any]]:
        if not profile.directive_type:
            return None
        slots = dict(result.slots.filled)
        if result.slots.missing_required:
            slots["missing_slots"] = result.slots.missing_required
        if result.clarification.question:
            slots["clarification_question"] = result.clarification.question

        selected = result.routing.selected_capability
        task_payload = result.model_dump()
        # 构造 Java Backend 消费结构，并保留完整 TaskUnderstandingResult。
        task_payload.setdefault("routing", {})
        capability_contract = (
            result.metadata.get("capability_contract", {})
            if isinstance(result.metadata.get("capability_contract"), dict)
            else {}
        )
        task_payload["routing"].update(
            {
                "domain": result.intent.domain,
                "intent": result.intent.intent,
                "execution_mode": selected.execution_mode if selected else result.planner_constraints.execution_mode,
                "next_action": result.next_action,
                "capability_contract": capability_contract,
            }
        )
        task_payload["context_dependencies"] = (
            selected
            and self._safe_list(selected.model_dump().get("context_dependencies"))
            or result.context.context_type
        )
        task_payload["planner_constraints"] = result.planner_constraints.model_dump()

        directive = {
            "type": profile.directive_type,
            "domain": result.intent.domain,
            "intent": result.intent.intent,
            "confidence": result.intent.confidence,
            "secondary": result.intent.secondary,
            "risk": result.risk_flags.risk_level,
            "needs_clarification": result.clarification.needed,
            "next_action": result.next_action,
            "slots": slots,
            "task": task_payload,
            "router": result.router,
            "capability_contract": capability_contract,
        }
        workflow = result.metadata.get("workflow") if isinstance(result.metadata, dict) else None
        if isinstance(workflow, dict):
            directive["workflow"] = dict(workflow)
        if result.answer:
            directive["answer"] = result.answer
        return directive

    def get_profile(self, profile_id: str | None) -> ProfileDefinition:
        return self.capability_registry.get_profile(profile_id)

    async def _understand_with_model(
        self,
        profile: ProfileDefinition,
        request: AgentRunRequest,
        message: str,
        previous_slots: Dict[str, Any],
        trace_id: str,
    ) -> Optional[TaskUnderstandingResult]:
        capabilities = self.capability_registry.list_capabilities(profile.id)
        prompt_path = profile.prompts.get("intent", "intent/task_understanding.md")
        system_prompt = self.prompt_loader.load(prompt_path, DEFAULT_TASK_UNDERSTANDING_PROMPT)
        capability_payload = [self._capability_for_prompt(item) for item in capabilities]
        user_payload = {
            "profile": {"id": profile.id, "name": profile.name, "domain": profile.domain},
            "message": message,
            "runtime_context": {"current_date": TimeUtils.get_current_date()},
            # 当前问题已由 message 单独传入，历史区移除同一条末尾 user 消息并清理空字段，
            # 避免重复 prefill；仍保留之前七条意图线索，信息窗口不缩小。
            "recent_messages": self._recent_messages_for_prompt(request, message),
            "metadata": self._safe_metadata(request.metadata or {}),
            "previous_slots": previous_slots,
        }
        try:
            response = await self.llm_client.chat(
                [
                    ChatMessage(role="system", content=system_prompt),
                    ChatMessage(
                        role="system",
                        content="能力卡目录按 id 稳定排序：\n"
                        + json.dumps(capability_payload, ensure_ascii=False, sort_keys=True),
                    ),
                    ChatMessage(role="user", content=json.dumps(user_payload, ensure_ascii=False, sort_keys=True)),
                ],
                temperature=0,
                max_tokens=1200,
                disable_thinking=True,
            )
            data = self._extract_json(response.get("content") or "")
            return self._normalize_model_result(profile, data, message, previous_slots, trace_id)
        except Exception as exc:
            logger.warning(f"TaskUnderstanding LLM 路由失败，使用配置语义兜底：profile={profile.id}, error={exc}")
            return None

    def _understand_with_semantic_config(
        self,
        profile: ProfileDefinition,
        request: AgentRunRequest,
        message: str,
        previous_slots: Dict[str, Any],
        trace_id: str,
    ) -> TaskUnderstandingResult:
        shortcut_result = self._understand_with_shortcut(profile, message, previous_slots, trace_id)
        if shortcut_result is not None:
            return shortcut_result

        slots = self.slot_extractor.extract(profile, message)
        scored = self._score_capabilities(profile, message, slots)
        capability = scored[0][2] if scored else self.result_builder.default_capability(profile)
        candidates = [
            self.result_builder.candidate(card, score, reason, self.result_builder.missing_required(card, slots))
            for score, reason, card in scored[:5]
        ]
        confidence = scored[0][0] if scored else 0.35
        reason = scored[0][1] if scored else "default capability"
        return self.result_builder.build(
            profile=profile,
            message=message,
            trace_id=trace_id,
            capability=capability,
            candidates=candidates,
            confidence=confidence,
            slots=slots,
            router="semantic_config",
            reason=reason,
        )

    def _normalize_model_result(
        self,
        profile: ProfileDefinition,
        data: Dict[str, Any],
        message: str,
        previous_slots: Dict[str, Any],
        trace_id: str,
    ) -> TaskUnderstandingResult:
        # 能力标识不可信，必须回落到画像中已注册的能力。
        capability_id = str(data.get("selected_capability_id") or data.get("capability_id") or "")
        intent = str(data.get("intent") or "")
        capability = (
            profile.capability_by_id(capability_id)
            or profile.capability_by_intent(intent)
            or self.result_builder.default_capability(profile)
        )
        model_slots = data.get("slots") if isinstance(data.get("slots"), dict) else {}
        reuse_previous_slots = bool(data.get("reuse_previous_slots"))
        # 只有模型显式声明复用时才合并历史槽位，避免旧上下文污染新任务。
        reusable_slots = previous_slots if reuse_previous_slots else {}
        slots = {**reusable_slots, **model_slots}
        confidence = self._clamp_float(data.get("confidence"), 0.8)
        candidates = [
            self.result_builder.candidate(
                capability, confidence, str(data.get("reason") or "llm selected"), data.get("missing_required") or []
            )
        ]
        result = self.result_builder.build(
            profile=profile,
            message=message,
            trace_id=trace_id,
            capability=capability,
            candidates=candidates,
            confidence=confidence,
            slots=slots,
            router="llm",
            reason=str(data.get("reason") or "llm selected"),
        )
        # 查询改写、上下文依赖和澄清信息按各自协议边界逐项收敛。
        result.rewritten_query = QueryRewrite(
            resolved_query=str(data.get("resolved_query") or result.rewritten_query.resolved_query),
            retrieval_query=self._normalize_relative_time_query(
                message,
                str(data.get("retrieval_query") or result.rewritten_query.retrieval_query),
            ),
            planner_query=str(data.get("planner_query") or result.rewritten_query.planner_query),
        )
        dependency = str(data.get("context_dependency") or "").strip().lower()
        if dependency in {"none", "optional", "required"}:
            result.context.dependency = dependency
        if isinstance(data.get("context_type"), list):
            result.context.context_type = [str(item) for item in data["context_type"] if str(item).strip()]
        result.context.resolved_references = self._resolved_references(data.get("resolved_references"))
        if isinstance(data.get("secondary"), list):
            result.intent.secondary = [str(item) for item in data["secondary"] if str(item).strip()]
        result.metadata["reuse_previous_slots"] = reuse_previous_slots
        if data.get("needs_clarification") is not None:
            result.clarification.needed = bool(data.get("needs_clarification"))
            result.clarification.blocking = result.clarification.needed
            result.clarification.question = data.get("clarification_question") or result.clarification.question
        if data.get("answer"):
            result.answer = str(data.get("answer"))
        if data.get("risk_level"):
            result.risk_flags.risk_level = str(data.get("risk_level"))
        return result

    def _normalize_relative_time_query(self, original_query: str, retrieval_query: str) -> str:
        """修正模型为相对时间请求擅自补入的过期年份。

        用户明确写出的年份必须原样保留；只有原问题没有年份、同时表达相对时间时，
        才把模型生成检索词中的年份收敛为 Runtime 当前年份。
        """

        original = str(original_query or "")
        rewritten = str(retrieval_query or "").strip()
        if not rewritten or re.search(r"(?<!\d)(?:19|20)\d{2}(?!\d)", original):
            return rewritten
        if not re.search(r"最新|近期|最近|当前|今年|today|latest|recent|current", original, re.IGNORECASE):
            return rewritten
        current_year = TimeUtils.get_current_date()[:4]
        return re.sub(r"(?<!\d)(?:19|20)\d{2}(?!\d)", current_year, rewritten)

    def _score_capabilities(
        self, profile: ProfileDefinition, message: str, slots: Dict[str, Any]
    ) -> List[Tuple[float, str, CapabilityCard]]:
        rows: List[Tuple[float, str, CapabilityCard]] = []
        normalized_message = normalize_text(message)
        message_tokens = set(tokens(message))
        for capability in profile.capabilities:
            raw_score = 0.0
            reasons: List[str] = []
            # 正向关键词与示例累积分数，负向信号用于抑制误路由。
            for keyword in capability.keywords:
                if phrase_match(keyword, normalized_message):
                    raw_score += 3.0
                    reasons.append(f"keyword:{keyword}")
            for example in capability.examples:
                overlap = overlap_score(message_tokens, set(tokens(example)))
                if overlap > 0:
                    raw_score += min(2.0, overlap)
                if phrase_match(example, normalized_message):
                    raw_score += 2.5
            for negative in capability.negative_keywords + capability.negative_examples:
                if phrase_match(negative, normalized_message):
                    raw_score -= 4.0
                    reasons.append(f"negative:{negative}")
            for slot in capability.required_slots:
                raw_score += 0.35 if slot in slots else -0.25
            if profile.default_capability_id == capability.id:
                raw_score += 0.1
            if raw_score > 0:
                confidence = score_to_confidence(raw_score)
                rows.append((confidence, ",".join(reasons) or "semantic capability match", capability))
        rows.sort(key=lambda item: (-item[0], item[2].id))
        if not rows:
            default_capability = self.result_builder.default_capability(profile)
            rows.append((0.35, "default capability", default_capability))
        return rows

    def _understand_with_shortcut(
        self,
        profile: ProfileDefinition,
        message: str,
        previous_slots: Dict[str, Any],
        trace_id: str,
    ) -> Optional[TaskUnderstandingResult]:
        shortcut = self._match_shortcut(profile, message, previous_slots)
        if not shortcut:
            return None
        capability = profile.capability_by_id(shortcut.capability_id) or self.result_builder.default_capability(profile)
        slots = self._apply_shortcut_slots(shortcut, previous_slots)
        candidates = [self.result_builder.candidate(capability, shortcut.confidence, shortcut.reason, [])]
        result = self.result_builder.build(
            profile=profile,
            message=message,
            trace_id=trace_id,
            capability=capability,
            candidates=candidates,
            confidence=shortcut.confidence,
            slots=slots,
            router="semantic_config_shortcut",
            reason=shortcut.reason,
        )
        result.metadata["reuse_previous_slots"] = shortcut.reuse_previous_slots
        if shortcut.resolved_query:
            result.rewritten_query.resolved_query = shortcut.resolved_query
        if shortcut.retrieval_query:
            result.rewritten_query.retrieval_query = shortcut.retrieval_query
        if shortcut.planner_query:
            result.rewritten_query.planner_query = shortcut.planner_query
        if shortcut.context_type:
            result.context.dependency = "required"
            result.context.context_type = list(shortcut.context_type)
        if shortcut.secondary:
            result.intent.secondary = list(dict.fromkeys([*result.intent.secondary, *shortcut.secondary]))
        if shortcut.reuse_previous_slots:
            result.context.resolved_references = [
                ResolvedReference(
                    text=message,
                    resolved_to=result.rewritten_query.resolved_query,
                    source="previous_slots",
                    confidence=shortcut.confidence,
                )
            ]
        return result

    def _match_shortcut(
        self, profile: ProfileDefinition, message: str, previous_slots: Dict[str, Any]
    ) -> Optional[ConversationShortcut]:
        if not previous_slots:
            return None
        text = normalize_text(message)
        raw_message = str(message or "").strip()
        for shortcut in profile.conversation_shortcuts:
            if any(not self._has_slot_path(previous_slots, path) for path in shortcut.required_previous_slots):
                continue
            phrase_matched = any(phrase_match(phrase, text) for phrase in shortcut.phrases)
            pattern_matched = False
            for pattern in shortcut.patterns:
                try:
                    if re.search(pattern, raw_message, re.IGNORECASE):
                        pattern_matched = True
                        break
                except re.error as exc:
                    logger.warning(f"忽略无效会话捷径正则：shortcut={shortcut.id}, pattern={pattern}, error={exc}")
            if phrase_matched or pattern_matched:
                return shortcut
        return None

    def _apply_shortcut_slots(self, shortcut: ConversationShortcut, previous_slots: Dict[str, Any]) -> Dict[str, Any]:
        slots = dict(previous_slots)
        for key, spec in shortcut.slot_updates.items():
            if isinstance(spec, dict) and spec.get("op") == "increment":
                default = int(spec.get("default", 0))
                step = int(spec.get("step", 1))
                slots[key] = int(slots.get(key) or default) + step
            else:
                slots[key] = spec
        return slots

    def _has_slot_path(self, slots: Dict[str, Any], path: str) -> bool:
        current: Any = slots
        for segment in str(path or "").split("."):
            if not segment or not isinstance(current, dict) or segment not in current:
                return False
            current = current[segment]
        return current not in (None, "", [], {})

    def _capability_for_prompt(self, capability: CapabilityCard) -> Dict[str, Any]:
        return {
            "id": capability.id,
            "name": capability.name,
            "domain": capability.domain,
            "intent": capability.intent,
            "execution_intent": capability.execution_intent,
            "execution_mode": capability.execution_mode,
            "description": capability.description,
            "examples": capability.examples,
            "negative_examples": capability.negative_examples,
            "required_slots": capability.required_slots,
            "optional_slots": capability.optional_slots,
            "risk": capability.risk,
            "next_action": capability.next_action,
            "context_dependencies": capability.context_dependencies,
        }

    def _compact_message(self, message: ChatMessage, limit: int = 400) -> Dict[str, Any]:
        content = str(message.content or "")
        data: Dict[str, Any] = {"role": message.role, "content": content}
        if message.name:
            data["name"] = message.name
        if message.tool_call_id:
            data["tool_call_id"] = message.tool_call_id
        if len(content) > limit:
            data["content"] = content[:limit] + "...(truncated)"
        return data

    def _recent_messages_for_prompt(self, request: AgentRunRequest, current_message: str) -> List[Dict[str, Any]]:
        messages = list(request.messages or [])
        limit = 8
        if (
            messages
            and messages[-1].role == "user"
            and str(messages[-1].content or "").strip() == str(current_message or "").strip()
        ):
            messages = messages[:-1]
            limit = 7
        return [self._compact_message(item) for item in messages[-limit:]]

    def _last_user_message(self, request: AgentRunRequest) -> str:
        for msg in reversed(request.messages or []):
            if msg.role == "user":
                return str(msg.content or "").strip()
        return ""

    def _extract_json(self, content: str) -> Dict[str, Any]:
        text = (content or "").strip()
        if text.startswith("```"):
            text = re.sub(r"^```(?:json)?", "", text).strip()
            text = re.sub(r"```$", "", text).strip()
        if not text.startswith("{"):
            match = re.search(r"\{.*\}", text, re.S)
            text = match.group(0) if match else text
        data = json.loads(text)
        return data if isinstance(data, dict) else {}

    # Runtime Core 只内置通用运行时元数据键；业务侧键（如 resume_id）由部署配置
    # business_metadata_keys 注入，避免在核心代码中硬编码具体业务概念。
    _GENERIC_METADATA_KEYS = (
        "profile",
        "entrypoint",
        "previous_slots",
        "max_turns",
        "max_tool_calls",
        "personal_context",
        "attachments",
    )

    def _safe_metadata(self, metadata: Dict[str, Any]) -> Dict[str, Any]:
        allow_keys = set(self._GENERIC_METADATA_KEYS) | set(settings.business_metadata_keys)
        return {key: value for key, value in metadata.items() if key in allow_keys}

    def _resolved_references(self, value: Any) -> List[ResolvedReference]:
        if not isinstance(value, list):
            return []
        references: List[ResolvedReference] = []
        for item in value:
            if not isinstance(item, dict) or not str(item.get("text") or "").strip():
                continue
            references.append(
                ResolvedReference(
                    text=str(item.get("text")),
                    resolved_to=str(item.get("resolved_to")) if item.get("resolved_to") is not None else None,
                    source=str(item.get("source")) if item.get("source") is not None else None,
                    confidence=self._clamp_float(item.get("confidence"), 0.0),
                )
            )
        return references

    def _safe_list(self, value: Any) -> List[Any]:
        return value if isinstance(value, list) else []

    def _dict(self, value: Any) -> Dict[str, Any]:
        return value if isinstance(value, dict) else {}

    def _clamp_float(self, value: Any, fallback: float) -> float:
        try:
            number = float(value)
        except Exception:
            number = fallback
        return max(0.0, min(1.0, number))
