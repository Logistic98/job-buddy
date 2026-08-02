"""依据任务理解生成有界执行计划与确定性降级方案。"""

import asyncio
import json
import re
from typing import List, Optional

from loguru import logger

from app.core.common.constants import StopReason
from app.core.common.settings import settings
from app.core.common.temporal import requests_latest_selection
from app.core.prompt.loader import PromptTemplateLoader
from app.core.utils.time_utils import TimeUtils
from app.models.schemas import AgentPlan, AgentPlanStep, ChatMessage, TaskUnderstandingResult, ToolCall, ToolDefinition

DETERMINISTIC_TOOL_MATCH_MIN_SCORE = 4
PLANNER_MAX_TOKENS = 2048
PLANNER_LLM_TIMEOUT_SECONDS = 30


DEFAULT_PLANNER_PROMPT = """
你是企业级 Agent Runtime 的 Planner。
你必须控制任务边界、成本和工具调用次数。请基于任务理解结果、上下文摘要、历史观察和可用工具，决定下一步动作。
输出必须是严格 JSON，省略 Markdown。
""".strip()


class RuntimePlanner:
    """Planner：LLM 优先，配置 Prompt 驱动；离线场景只保留最小可验证降级策略。"""

    def __init__(self, llm_client=None, prompt_loader: PromptTemplateLoader = None):
        self.llm_client = llm_client
        self.prompt_loader = prompt_loader or PromptTemplateLoader()

    async def create_or_update_plan(
        self,
        objective: str,
        messages: List[ChatMessage],
        observations: List[str],
        tools: List[ToolDefinition],
        current_plan: Optional[AgentPlan] = None,
        context_summary: str = "",
        task_understanding: Optional[TaskUnderstandingResult] = None,
    ) -> tuple[AgentPlan, Optional[ToolCall]]:
        # 澄清、已有充分观察和确定性工具请求优先走可验证的本地计划。
        if task_understanding and task_understanding.clarification.needed:
            return self._clarification_plan(objective, task_understanding), None
        if observations and not self.llm_client and self._observations_are_sufficient(task_understanding, observations):
            return self._complete_from_observations(objective, observations, current_plan), None
        if not self.llm_client:
            return self._fallback_plan(objective, observations, tools, current_plan, task_understanding)
        if self._is_deterministic_tool_request(objective, tools):
            return self._fallback_plan(objective, observations, tools, current_plan, task_understanding)

        prompt_path = "planner/default.md"
        if task_understanding:
            prompt_path = task_understanding.metadata.get("planner_prompt") or prompt_path
        system_prompt = self.prompt_loader.load(prompt_path, DEFAULT_PLANNER_PROMPT)
        tool_lines = [
            f"- {tool.name}: {tool.description}; schema={json.dumps(tool.input_schema, ensure_ascii=False, sort_keys=True)}; risk={tool.risk_level.value if hasattr(tool.risk_level, 'value') else tool.risk_level}; read_only={tool.read_only}"
            for tool in sorted(tools, key=lambda item: item.name)
        ]
        tool_catalog_prompt = (
            "可用候选工具目录如下。目录由 Tool Search 按任务理解结果召回，并按工具名稳定排序；仅使用目录内工具。\n"
            + "\n".join(tool_lines)
        )
        task_payload = task_understanding.model_dump() if task_understanding else {}
        # 稳定工具目录放入可缓存前缀，动态任务上下文只追加到末尾。
        dynamic_prompt = (
            f"用户目标：{objective}\n\n"
            f"任务理解结果：{json.dumps(task_payload, ensure_ascii=False, sort_keys=True)}\n\n"
            f"上下文摘要：{context_summary}\n\n"
            f"已有观察：\n" + "\n".join(observations[-8:]) + "\n\n"
            "请输出 JSON。"
        )
        planner_messages = [ChatMessage(role="system", content=system_prompt)]
        if settings.config.llm_service.prompt_cache_enabled:
            planner_messages.append(ChatMessage(role="system", content=tool_catalog_prompt))
        else:
            dynamic_prompt = f"候选工具：\n{tool_catalog_prompt}\n\n{dynamic_prompt}"
        planner_messages.extend(
            [
                *messages[-8:],
                ChatMessage(role="user", content=dynamic_prompt),
            ]
        )
        try:
            # 模型输出必须经过结构化解析和可用工具白名单校验。
            response = await asyncio.wait_for(
                self.llm_client.chat(
                    planner_messages,
                    max_tokens=PLANNER_MAX_TOKENS,
                    disable_thinking=True,
                ),
                timeout=PLANNER_LLM_TIMEOUT_SECONDS,
            )
            content = response.get("content") or "{}"
            data = self._parse_json(content)
            plan, calls = self._build_plan_and_calls(
                objective,
                data,
                tools,
                task_understanding=task_understanding,
            )
            return plan, calls[0] if calls else None
        except Exception as e:
            logger.warning(f"Planner 模型调用失败，使用降级计划：error={e}")
            return self._fallback_plan(
                objective, observations, tools, current_plan, task_understanding, llm_error=str(e)
            )

    def _build_plan_and_calls(
        self,
        objective: str,
        data: dict,
        tools: List[ToolDefinition],
        task_understanding: Optional[TaskUnderstandingResult] = None,
    ) -> tuple[AgentPlan, List[ToolCall]]:
        available_tool_names = {tool.name for tool in tools}
        raw_steps = [item for item in (data.get("plan_steps", []) or []) if isinstance(item, dict)]
        step_ids = [
            str(item.get("id")).strip() if item.get("id") not in (None, "") else f"step_{index + 1}"
            for index, item in enumerate(raw_steps)
        ]
        steps = []
        calls: List[ToolCall] = []
        for index, item in enumerate(raw_steps):
            step_id = step_ids[index]
            tool_name = item.get("tool_name")
            if tool_name and tool_name not in available_tool_names:
                tool_name = None
            step_arguments = item.get("tool_arguments")
            if not isinstance(step_arguments, dict):
                step_arguments = {}
            step_arguments = self._normalize_tool_arguments(
                tool_name,
                step_arguments,
                objective,
                task_understanding,
            )
            # LLM 常用数字索引表达依赖。缺省步骤 ID 采用稳定的 step_N，数字依赖按 0-based
            # 索引解析；字符串若已命中显式步骤 ID，则优先按 ID 处理。越界索引保留字符串，
            # 交由 Graph 依赖校验产生明确失败，而不是随机 ID 导致不可复现的误判。
            raw_dependencies = item.get("depends_on")
            if raw_dependencies is None:
                raw_dependencies = []
            elif not isinstance(raw_dependencies, (list, tuple, set)):
                raw_dependencies = [raw_dependencies]
            numeric_base = self._dependency_numeric_base(raw_dependencies)
            depends_on = [
                self._normalize_step_reference(
                    dep,
                    step_ids,
                    raw_steps=raw_steps,
                    before_index=index,
                    numeric_base=numeric_base,
                )
                for dep in raw_dependencies
                if dep is not None
            ]
            steps.append(
                AgentPlanStep(
                    id=step_id,
                    goal=item.get("goal", "执行任务步骤"),
                    tool_name=tool_name,
                    tool_arguments=step_arguments,
                    depends_on=depends_on,
                )
            )
            if tool_name and not item.get("defer", False):
                calls.append(
                    ToolCall(
                        id=f"toolu_{TimeUtils.gen_step_id()}",
                        name=tool_name,
                        arguments=step_arguments,
                        reason=item.get("reason") or item.get("goal"),
                        plan_step_id=step_id,
                    )
                )

        raw_calls = data.get("tool_calls") or []
        call_data = data.get("tool_call")
        if call_data:
            raw_calls = [call_data] + list(raw_calls)
        for item in raw_calls:
            if not isinstance(item, dict):
                continue
            name = item.get("name")
            if name in available_tool_names:
                call_arguments = item.get("arguments")
                if not isinstance(call_arguments, dict):
                    call_arguments = {}
                call_arguments = self._normalize_tool_arguments(
                    name,
                    call_arguments,
                    objective,
                    task_understanding,
                )
                calls.append(
                    ToolCall(
                        id=f"toolu_{TimeUtils.gen_step_id()}",
                        name=name,
                        arguments=call_arguments,
                        reason=item.get("reason"),
                        plan_step_id=(
                            self._normalize_step_reference(item.get("plan_step_id"), step_ids)
                            if item.get("plan_step_id") is not None
                            else None
                        ),
                    )
                )

        calls = self._dedupe_calls(calls)
        plan = AgentPlan(
            objective=objective,
            steps=steps,
            tool_calls=calls,
            final_answer=data.get("final_answer"),
            is_complete=bool(data.get("is_complete")),
            need_clarification=bool(data.get("need_clarification")),
            clarification_question=data.get("clarification_question"),
        )
        if plan.is_complete:
            plan.stop_reason = StopReason.TASK_COMPLETE.value
        if plan.need_clarification:
            plan.stop_reason = StopReason.NEED_CLARIFICATION.value
        return plan, [] if plan.is_complete or plan.need_clarification else calls

    def _normalize_tool_arguments(
        self,
        tool_name: str | None,
        arguments: dict,
        objective: str,
        task_understanding: Optional[TaskUnderstandingResult] = None,
    ) -> dict:
        """把任务理解中的确定性约束覆盖到不可信的 Planner 工具参数。"""

        normalized = dict(arguments)
        if tool_name == "web_search":
            return {
                **normalized,
                **self._web_search_selection_arguments(objective, task_understanding),
            }
        if tool_name != "sandbox_code_execute" or normalized.get("language"):
            return normalized
        objective_text = (objective or "").lower()
        language = None
        if any(term in objective_text for term in ("javascript", "typescript", "node.js", "nodejs")):
            language = "javascript"
        elif "java" in objective_text:
            language = "java"
        elif any(term in objective_text for term in ("shell", "bash", "sh 脚本")):
            language = "shell"
        elif "python" in objective_text:
            language = "python"
        return {**normalized, "language": language} if language else normalized

    def _web_search_selection_arguments(
        self,
        objective: str,
        task_understanding: Optional[TaskUnderstandingResult],
    ) -> dict:
        rewrite = task_understanding.rewritten_query if task_understanding else None
        combined = " ".join(
            str(item or "")
            for item in (
                objective,
                rewrite.resolved_query if rewrite else "",
                rewrite.retrieval_query if rewrite else "",
                rewrite.planner_query if rewrite else "",
            )
        )
        latest = bool((rewrite and rewrite.selection_mode == "latest") or requests_latest_selection(combined))
        if not latest:
            return {}
        content_scope = rewrite.content_scope if rewrite else ""
        if not content_scope and re.search(
            r"工程(?:博客|博文|文章)|engineering\s+(?:blog|article|post)",
            combined,
            re.IGNORECASE,
        ):
            content_scope = "engineering_blog"
        return {
            "selection_mode": "latest",
            "as_of_date": (rewrite.as_of_date if rewrite and rewrite.as_of_date else TimeUtils.get_current_date()),
            "source_preference": "official_first",
            **({"time_range_start": rewrite.time_range_start} if rewrite and rewrite.time_range_start else {}),
            **({"content_scope": content_scope} if content_scope else {}),
        }

    def _fallback_plan(
        self,
        objective: str,
        observations: List[str],
        tools: List[ToolDefinition],
        current_plan: Optional[AgentPlan],
        task_understanding: Optional[TaskUnderstandingResult] = None,
        llm_error: Optional[str] = None,
    ):
        if observations and self._observations_are_sufficient(task_understanding, observations):
            return self._complete_from_observations(objective, observations, current_plan), None

        if task_understanding and task_understanding.answer:
            return AgentPlan(
                objective=objective,
                final_answer=task_understanding.answer,
                is_complete=True,
                stop_reason=StopReason.TASK_COMPLETE.value,
            ), None
        if (
            task_understanding
            and task_understanding.planner_constraints.planner_needed
            and task_understanding.profile != "default"
            and not self._is_deterministic_tool_request(objective, tools)
        ):
            # 区分两种降级原因：完全未配置 LLM Planner，与已配置但调用失败（超时/模型服务异常）。
            if self.llm_client is None:
                final_answer = "当前 Runtime 停止在任务理解阶段：未配置可用 LLM Planner，无法继续执行复杂任务。请配置 JOB_BUDDY_LLM_API_KEY 后重试。"
            else:
                final_answer = "LLM Planner 调用失败，可能是模型服务超时或暂时不可用，请稍后重试。"
                if llm_error:
                    final_answer += f"（错误：{llm_error}）"
            return AgentPlan(
                objective=objective,
                final_answer=final_answer,
                is_complete=True,
                stop_reason=StopReason.TOOL_UNAVAILABLE.value,
            ), None

        selected = self._select_fallback_tool(objective, tools)
        steps = [
            AgentPlanStep(id=TimeUtils.gen_step_id(), goal=objective, tool_name=selected.name if selected else None)
        ]
        plan = AgentPlan(objective=objective, steps=steps, is_complete=not bool(selected))
        if not selected:
            if task_understanding and task_understanding.planner_constraints.planner_needed:
                plan.final_answer = "当前 Runtime 缺少可用工具或模型能力，无法继续执行该复杂任务。"
            else:
                plan.final_answer = "当前没有可用工具，无法继续执行。"
            plan.stop_reason = StopReason.TOOL_UNAVAILABLE.value
            return plan, None
        arguments = self._build_default_arguments(selected, objective, task_understanding)
        call = ToolCall(
            id=f"toolu_{TimeUtils.gen_step_id()}",
            name=selected.name,
            arguments=arguments,
            reason="离线降级计划选择低风险默认工具，生产环境应由 LLM Planner 决策",
            plan_step_id=steps[0].id,
        )
        plan.tool_calls = [call]
        return plan, call

    def _clarification_plan(self, objective: str, task_understanding: TaskUnderstandingResult) -> AgentPlan:
        return AgentPlan(
            objective=objective,
            is_complete=False,
            need_clarification=True,
            clarification_question=task_understanding.clarification.question or "需要进一步澄清。",
            stop_reason=StopReason.NEED_CLARIFICATION.value,
        )

    def _complete_from_observations(
        self, objective: str, observations: List[str], current_plan: Optional[AgentPlan]
    ) -> AgentPlan:
        plan = current_plan or AgentPlan(objective=objective)
        plan.is_complete = True
        plan.final_answer = "\n".join(observations[-3:])
        plan.stop_reason = StopReason.TASK_COMPLETE.value
        return plan

    def _observations_are_sufficient(
        self, task_understanding: Optional[TaskUnderstandingResult], observations: List[str]
    ) -> bool:
        if not observations:
            return False
        if any("执行失败" in str(item) for item in observations):
            return False
        contract = (
            task_understanding.metadata.get("capability_contract")
            if task_understanding and isinstance(task_understanding.metadata, dict)
            else None
        )
        required = {str(item) for item in ((contract or {}).get("required_tools") or [])}
        if required:
            succeeded = set()
            for observation in observations:
                match = re.match(r"^工具\s+([^\s：:]+)\s+执行成功[：:]", str(observation).strip())
                if match:
                    succeeded.add(match.group(1))
            if not required.issubset(succeeded):
                return False
        if task_understanding and task_understanding.planner_constraints.planner_needed and len(observations) < 1:
            return False
        return True

    def _normalize_step_reference(
        self,
        value,
        step_ids: List[str],
        *,
        raw_steps: Optional[List[dict]] = None,
        before_index: Optional[int] = None,
        numeric_base: int = 1,
    ) -> str:
        if isinstance(value, int) and not isinstance(value, bool):
            index = value - numeric_base
            return step_ids[index] if 0 <= index < len(step_ids) else str(value)
        normalized = str(value).strip()
        if normalized in step_ids:
            return normalized
        human_step = re.fullmatch(r"(?:步骤|step)\s*(\d+)", normalized, flags=re.IGNORECASE)
        if human_step:
            index = int(human_step.group(1)) - 1
            return step_ids[index] if 0 <= index < len(step_ids) else normalized
        if normalized.isdigit():
            index = int(normalized) - numeric_base
            return step_ids[index] if 0 <= index < len(step_ids) else normalized
        matched_tool_step = self._match_prior_tool_dependency(
            normalized,
            step_ids=step_ids,
            raw_steps=raw_steps,
            before_index=before_index,
        )
        if matched_tool_step:
            return matched_tool_step
        return normalized

    @staticmethod
    def _dependency_numeric_base(values) -> int:
        """含 0 的旧式列表按 0-based 解释，其余数字按人类常用的 1-based 解释。"""

        for value in values:
            if isinstance(value, int) and not isinstance(value, bool) and value == 0:
                return 0
            if isinstance(value, str) and value.strip() == "0":
                return 0
        return 1

    @staticmethod
    def _match_prior_tool_dependency(
        value: str,
        *,
        step_ids: List[str],
        raw_steps: Optional[List[dict]],
        before_index: Optional[int],
    ) -> Optional[str]:
        """只把“唯一前序工具 + 成功状态”归一为步骤 ID，不对模糊文本猜测。"""

        if raw_steps is None or before_index is None or before_index <= 0:
            return None
        matches: List[str] = []
        for index, item in enumerate(raw_steps[:before_index]):
            tool_name = str(item.get("tool_name") or "").strip()
            if not tool_name:
                continue
            pattern = rf"^{re.escape(tool_name)}\s*(?:成功|完成|通过|success|succeeded|completed)$"
            if re.fullmatch(pattern, value, flags=re.IGNORECASE):
                matches.append(step_ids[index])
        return matches[0] if len(matches) == 1 else None

    def _is_deterministic_tool_request(self, objective: str, tools: List[ToolDefinition]) -> bool:
        return any(
            tool.read_only
            and not tool.destructive
            and self._can_build_default_arguments(tool)
            and self._tool_metadata_match_score(objective, tool) >= DETERMINISTIC_TOOL_MATCH_MIN_SCORE
            for tool in tools
        )

    def _select_fallback_tool(self, objective: str, tools: List[ToolDefinition]) -> Optional[ToolDefinition]:
        """从 Tool Search 已排序候选中选择安全、可解释的离线 fallback。

        元数据匹配分数仅用于优先明显命中的只读工具；同分以及完全无匹配时保持候选原始顺序。
        不回退到可写或破坏性工具，避免无 LLM 环境扩大执行权限。
        """
        read_only_tools = [
            tool
            for tool in tools
            if tool.read_only and not tool.destructive and self._can_build_default_arguments(tool)
        ]
        if not read_only_tools:
            return None
        scored = [
            (self._tool_metadata_match_score(objective, tool), index, tool)
            for index, tool in enumerate(read_only_tools)
        ]
        best_score = max(score for score, _, _ in scored)
        if best_score <= 0:
            return read_only_tools[0]
        return min(
            (item for item in scored if item[0] == best_score),
            key=lambda item: item[1],
        )[2]

    def _tool_metadata_match_score(self, objective: str, tool: ToolDefinition) -> int:
        objective_text = (objective or "").strip().lower()
        if not objective_text:
            return 0
        objective_words = set(re.findall(r"[a-z0-9]+", objective_text))
        weighted_fields = [
            ([tool.name], 8),
            (tool.aliases, 6),
            ([tool.search_hint or ""], 4),
            (tool.tags, 3),
            ([tool.description], 1),
        ]
        score = 0
        matched_terms = set()
        for values, weight in weighted_fields:
            for value in values:
                for term in self._metadata_terms(str(value or "")):
                    key = (weight, term)
                    if key in matched_terms:
                        continue
                    if self._objective_contains_term(objective_text, objective_words, term):
                        score += weight
                        matched_terms.add(key)
        return score

    def _metadata_terms(self, value: str) -> set[str]:
        text = value.strip().lower().replace("_", " ").replace("-", " ")
        terms = {item for item in re.findall(r"[a-z0-9]+", text) if len(item) >= 2}
        for sequence in re.findall(r"[\u4e00-\u9fff]+", text):
            if len(sequence) >= 2:
                terms.add(sequence)
            for size in range(2, min(4, len(sequence)) + 1):
                terms.update(sequence[index : index + size] for index in range(len(sequence) - size + 1))
        return terms

    def _objective_contains_term(self, objective: str, objective_words: set[str], term: str) -> bool:
        if re.fullmatch(r"[a-z0-9]+", term):
            return term in objective_words
        return term in objective

    def _build_default_arguments(
        self,
        tool: ToolDefinition,
        objective: str,
        task_understanding: Optional[TaskUnderstandingResult] = None,
    ) -> dict:
        properties = (tool.input_schema or {}).get("properties") or {}
        args = {
            key: value.get("default")
            for key, value in properties.items()
            if isinstance(value, dict) and "default" in value
        }
        required = (tool.input_schema or {}).get("required") or []
        for key in required:
            if key in args:
                continue
            if key in {"text", "query", "objective"}:
                args[key] = objective
            elif key in {"pattern", "keyword"}:
                args[key] = objective[:80]
        if tool.name == "web_search" and task_understanding:
            rewrite = task_understanding.rewritten_query
            search_query = rewrite.retrieval_query or rewrite.resolved_query
            if search_query and search_query.strip():
                args["query"] = search_query.strip()
            args = {
                **args,
                **self._web_search_selection_arguments(objective, task_understanding),
            }
        return args

    def _can_build_default_arguments(self, tool: ToolDefinition) -> bool:
        properties = (tool.input_schema or {}).get("properties") or {}
        required = (tool.input_schema or {}).get("required") or []
        objective_fields = {"text", "query", "objective", "pattern", "keyword"}
        return all(
            key in objective_fields or (isinstance(properties.get(key), dict) and "default" in properties[key])
            for key in required
        )

    def _dedupe_calls(self, calls: List[ToolCall]) -> List[ToolCall]:
        deduped: List[ToolCall] = []
        seen = set()
        seen_signatures = set()
        for call in calls:
            signature = (call.name, json.dumps(call.arguments, ensure_ascii=False, sort_keys=True, default=str))
            # 不同计划步骤即使调用同一工具和参数，也可能有不同依赖关系，不能互相去重。
            # 无步骤绑定的顶层 tool_calls 若与 plan_steps 已生成调用重复，则忽略顶层副本。
            if call.plan_step_id is None and signature in seen_signatures:
                continue
            key = (*signature, call.plan_step_id)
            if key in seen:
                continue
            seen.add(key)
            seen_signatures.add(signature)
            deduped.append(call)
        return deduped

    def _parse_json(self, content: str) -> dict:
        content = content.strip()
        if content.startswith("```"):
            content = content.strip("`")
            if content.startswith("json"):
                content = content[4:]
        start = content.find("{")
        end = content.rfind("}")
        if start >= 0 and end >= start:
            content = content[start : end + 1]
        return json.loads(content)
