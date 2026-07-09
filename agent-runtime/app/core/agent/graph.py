
import asyncio
import time
from typing import Literal

from langgraph.graph import END, StateGraph

from app.core.agent.loop_controller import LoopController
from app.core.checkpoint.store import CheckpointStore
from app.core.common.constants import PermissionMode, RuntimeStatus, StopReason, TraceEventName
from app.core.common.settings import settings
from app.core.context.assembler import ContextAssembler
from app.core.context.compactor import ContextCompactor
from app.core.intent.task_understanding import TaskUnderstandingService
from app.core.observability.trace import TraceRecorder
from app.core.planner.planner import RuntimePlanner
from app.core.tool.base import ToolExecutionContext
from app.core.tool.gateway import ToolGateway
from app.core.tool.runtime import ToolRuntime
from app.core.tool.search import ToolSearchService
from app.models.schemas import AgentStepLog, TaskUnderstandingResult, ToolDefinition, ToolResult
from app.models.state import AgentGraphState


class AgentGraphBuilder:
    """基于 LangGraph 的 Agent Loop 图构建器。

    新核心链路为：目标预检 → 任务理解/能力路由 → 上下文装配 → Tool Search → Planner →
    预算/权限 → 工具执行 → 观察反思 → 终止或继续。Job 等业务差异通过 Profile 进入
    TaskUnderstandingService，Java 与 Graph 节点保持业务规则配置化。
    """

    def __init__(
        self,
        planner: RuntimePlanner,
        tool_search: ToolSearchService,
        tool_runtime: ToolRuntime,
        task_understanding: TaskUnderstandingService,
        checkpoint_store: CheckpointStore = None,
        trace_recorder: TraceRecorder = None,
        loop_controller: LoopController = None,
        tool_gateway: ToolGateway = None,
        context_assembler: ContextAssembler = None,
        context_compactor: ContextCompactor = None,
    ):
        self.planner = planner
        self.tool_search = tool_search
        self.tool_runtime = tool_runtime
        self.task_understanding = task_understanding
        self.checkpoint_store = checkpoint_store or CheckpointStore()
        self.trace_recorder = trace_recorder or TraceRecorder()
        self.loop_controller = loop_controller or LoopController()
        self.tool_gateway = tool_gateway or ToolGateway(self.tool_runtime.registry, self.tool_search, self.tool_runtime)
        self.context_assembler = context_assembler or ContextAssembler()
        self.context_compactor = context_compactor or ContextCompactor()

    def build(self):
        builder = StateGraph(AgentGraphState)

        builder.add_node("understand_goal", self._understand_goal)
        builder.add_node("task_understanding_node", self._task_understanding)
        builder.add_node("collect_context", self._collect_context)
        builder.add_node("search_tools", self._tool_search)
        builder.add_node("make_plan", self._plan)
        builder.add_node("check_budget", self._budget_check)
        builder.add_node("run_tool", self._execute_tool)
        builder.add_node("observe_result", self._observe)
        builder.add_node("reflect", self._reflect)
        builder.add_node("build_final", self._finalize)

        builder.set_entry_point("understand_goal")
        builder.add_edge("understand_goal", "task_understanding_node")
        builder.add_conditional_edges(
            "task_understanding_node",
            self._route_after_task_understanding,
            {"finalize": "build_final", "collect_context": "collect_context"},
        )
        builder.add_edge("collect_context", "search_tools")
        builder.add_edge("search_tools", "make_plan")
        builder.add_conditional_edges(
            "make_plan",
            self._route_after_plan,
            {"finalize": "build_final", "budget_check": "check_budget"},
        )
        builder.add_conditional_edges(
            "check_budget",
            self._route_after_budget,
            {"execute_tool": "run_tool", "finalize": "build_final"},
        )
        builder.add_edge("run_tool", "observe_result")
        builder.add_edge("observe_result", "reflect")
        builder.add_conditional_edges(
            "reflect",
            self._route_after_reflect,
            {"tool_search": "search_tools", "finalize": "build_final"},
        )
        builder.add_edge("build_final", END)
        return builder.compile()

    async def _understand_goal(self, state: AgentGraphState) -> AgentGraphState:
        if self._should_skip_resume_stage(state, "understand_goal"):
            return state
        await self.trace_recorder.record(state["trace_id"], TraceEventName.UNDERSTAND_GOAL.value, run_id=state["run_id"])
        messages = state.get("messages", [])
        objective = state.get("objective")
        if not objective and messages:
            objective = str(messages[-1].content)
        state["objective"] = objective or ""
        metadata = state.get("metadata", {}) or {}
        state["profile"] = str(metadata.get("profile") or metadata.get("agent_profile") or "default")
        state.setdefault("observations", [])
        state.setdefault("tool_results", [])
        state.setdefault("permission_records", [])
        state.setdefault("logs", [])
        await self.checkpoint_store.save(state["session_id"], state["run_id"], "understand_goal", state)
        return state

    async def _task_understanding(self, state: AgentGraphState) -> AgentGraphState:
        if self._should_skip_resume_stage(state, "task_understanding"):
            return state
        request = state.get("request")
        if request is None:
            # Checkpoint 恢复时没有原始 request 对象，使用状态字段重建最小请求。
            from app.models.schemas import AgentRunRequest, BudgetConfig

            request = AgentRunRequest(
                messages=state.get("messages", []),
                session_id=state.get("session_id"),
                trace_id=state.get("trace_id"),
                permission_mode=PermissionMode(state.get("permission_mode") or PermissionMode.DEFAULT.value),
                budget=BudgetConfig(**(state.get("budget") or {})),
                metadata=state.get("metadata", {}),
            )
        result = await self.task_understanding.understand(request, state["session_id"], state["run_id"], state["trace_id"])
        profile = self.task_understanding.get_profile(result.profile)
        directive = self.task_understanding.build_directive(profile, result)
        state["task_understanding"] = result
        state["directive"] = directive
        state["objective"] = result.rewritten_query.planner_query or result.original_query or state.get("objective", "")
        await self.trace_recorder.record(
            state["trace_id"],
            TraceEventName.TASK_UNDERSTANDING.value,
            {
                "profile": result.profile,
                "router": result.router,
                "domain": result.intent.domain,
                "intent": result.intent.intent,
                "confidence": result.intent.confidence,
                "next_action": result.next_action,
                "needs_clarification": result.clarification.needed,
            },
            state["run_id"],
        )
        await self.trace_recorder.record(
            state["trace_id"],
            TraceEventName.CAPABILITY_ROUTE.value,
            result.routing.model_dump(),
            state["run_id"],
        )
        if directive:
            state.setdefault("tool_results", []).append(ToolResult(
                tool_call_id="task_understanding",
                tool_name=f"{result.profile}.task_understanding",
                success=True,
                output=directive,
                metadata={"profile": result.profile, "router": result.router, "synthetic": True},
            ))
        state.setdefault("logs", []).append(AgentStepLog(
            step_id="task_understanding",
            name="Task Understanding / Capability Routing",
            status="success",
            input={"objective": state.get("objective"), "profile": result.profile},
            output=directive or result.model_dump(),
        ))
        await self.checkpoint_store.save(state["session_id"], state["run_id"], "task_understanding", state)
        return state

    async def _collect_context(self, state: AgentGraphState) -> AgentGraphState:
        if self._should_skip_resume_stage(state, "collect_context"):
            return state
        messages = state.get("messages", [])
        task = state.get("task_understanding")
        assembled = self.context_assembler.assemble(
            messages=messages,
            task=task,
            observations=state.get("observations", []),
            tool_results=state.get("tool_results", []),
            metadata=state.get("metadata", {}) or {},
            compaction=state.get("compaction"),
        )
        state["context_summary"] = assembled["summary"]
        state["context_payload"] = assembled["payload"]
        state.setdefault("metrics", {})["context"] = assembled["metrics"]
        await self.trace_recorder.record(
            state["trace_id"],
            TraceEventName.CONTEXT_COLLECTED.value,
            {"context_summary": state["context_summary"], "metrics": assembled["metrics"]},
            run_id=state["run_id"],
        )
        await self.checkpoint_store.save(state["session_id"], state["run_id"], "collect_context", state)
        return state

    async def _tool_search(self, state: AgentGraphState) -> AgentGraphState:
        if self._should_skip_resume_stage(state, "tool_search"):
            return state
        task = state.get("task_understanding")
        query = task.rewritten_query.retrieval_query if task else state.get("objective") or ""
        await self.trace_recorder.record(
            state["trace_id"],
            TraceEventName.TOOL_SEARCH.value,
            {"query": query, "objective": state.get("objective")},
            run_id=state["run_id"],
        )
        tool_search_config = settings.config.tool_search
        if tool_search_config.enabled:
            tools = await self.tool_gateway.search(query or "", task, limit=tool_search_config.limit)
        else:
            tools = self.tool_runtime.registry.list_definitions()[:tool_search_config.limit]
        state["candidate_tools"] = tools
        state.setdefault("metrics", {})["tool_search"] = {"candidate_count": len(tools), "query": query}
        await self.checkpoint_store.save(state["session_id"], state["run_id"], "tool_search", state)
        return state

    async def _plan(self, state: AgentGraphState) -> AgentGraphState:
        if self._should_skip_resume_stage(state, "plan"):
            return state
        await self.trace_recorder.record(
            state["trace_id"], TraceEventName.PLAN_CREATED.value, {"turn": state.get("turn_count", 0)}, state["run_id"]
        )
        plan, tool_call = await self.planner.create_or_update_plan(
            objective=state.get("objective") or "",
            messages=state.get("messages", []),
            observations=state.get("observations", []),
            tools=state.get("candidate_tools", []),
            current_plan=state.get("plan"),
            context_summary=state.get("context_summary", ""),
            task_understanding=state.get("task_understanding"),
        )
        state["plan"] = plan
        state["selected_tool_calls"] = list(plan.tool_calls or ([] if tool_call is None else [tool_call]))
        state["selected_tool_call"] = tool_call or (state["selected_tool_calls"][0] if state["selected_tool_calls"] else None)
        state["turn_count"] = int(state.get("turn_count", 0)) + 1
        if plan.stop_reason:
            state["stop_reason"] = plan.stop_reason
        await self.checkpoint_store.save(state["session_id"], state["run_id"], "plan", state)
        return state

    async def _budget_check(self, state: AgentGraphState) -> AgentGraphState:
        if self._should_skip_resume_stage(state, "budget_check"):
            return state
        decision = self.loop_controller.evaluate_budget(state)
        if decision.blocked:
            state["should_stop"] = True
            state["status"] = RuntimeStatus.PAUSED.value
            state["stop_reason"] = decision.stop_reason
            state["answer"] = f"任务已暂停：{decision.reason}。"
        await self.trace_recorder.record(
            state["trace_id"], TraceEventName.BUDGET_CHECK.value, {"blocked_reason": decision.reason}, state["run_id"]
        )
        await self.checkpoint_store.save(state["session_id"], state["run_id"], "budget_check", state)
        return state

    async def _execute_tool(self, state: AgentGraphState) -> AgentGraphState:
        if self._should_skip_resume_stage(state, "execute_tool"):
            return state
        calls = state.get("selected_tool_calls") or ([] if not state.get("selected_tool_call") else [state.get("selected_tool_call")])
        if not calls:
            state["should_stop"] = True
            return state

        mode = PermissionMode(state.get("permission_mode") or PermissionMode.DEFAULT.value)
        context = ToolExecutionContext(
            run_id=state["run_id"],
            trace_id=state["trace_id"],
            session_id=state["session_id"],
            workspace_dir=settings.workspace_dir,
            metadata=state.get("metadata", {}),
        )
        await self.trace_recorder.record(
            state["trace_id"], TraceEventName.TOOL_EXECUTE_START.value, {"tools": [call.name for call in calls]}, state["run_id"]
        )
        async def _timed_execute(call):
            started = time.perf_counter()
            gateway_result = await self.tool_gateway.execute(call, mode, context, state.get("task_understanding"))
            return gateway_result, int((time.perf_counter() - started) * 1000)

        timed_results = []
        if self._can_execute_in_parallel(calls):
            timed_results = await asyncio.gather(*[_timed_execute(call) for call in calls])
        else:
            for call in calls:
                timed_results.append(await _timed_execute(call))
        gateway_results = [item[0] for item in timed_results]
        tool_durations = {id(item[0]): item[1] for item in timed_results}

        tool_stats = []
        for gateway_result in gateway_results:
            result = gateway_result.result
            if gateway_result.permission_record:
                state.setdefault("permission_records", []).append(gateway_result.permission_record)
                await self.trace_recorder.record(
                    state["trace_id"],
                    TraceEventName.PERMISSION_CHECK.value,
                    gateway_result.permission_record.model_dump(),
                    state["run_id"],
                )
            state.setdefault("tool_results", []).append(result)
            state["tool_call_count"] = int(state.get("tool_call_count", 0)) + 1
            duration_ms = tool_durations.get(id(gateway_result), 0)
            tool_stats.append({"tool": result.tool_name, "success": result.success, "duration_ms": duration_ms})
            if not result.success:
                state["failure_count"] = int(state.get("failure_count", 0)) + 1
                await self.trace_recorder.record(
                    state["trace_id"],
                    TraceEventName.TOOL_EXECUTE_FAILED.value,
                    {
                        "tool": result.tool_name,
                        "error": str(result.error or ""),
                        "duration_ms": duration_ms,
                        "permission_denied": bool(result.metadata.get("permission_denied")),
                    },
                    state["run_id"],
                )
                if result.metadata.get("permission_denied"):
                    state["stop_reason"] = StopReason.PERMISSION_DENIED.value
                    state["should_stop"] = True
        await self.trace_recorder.record(
            state["trace_id"],
            TraceEventName.TOOL_EXECUTE_END.value,
            {
                "tools": [item.result.tool_name for item in gateway_results],
                "success": all(item.result.success for item in gateway_results),
                "duration_ms": sum(stat["duration_ms"] for stat in tool_stats),
                "results": tool_stats,
            },
            state["run_id"],
        )
        await self.checkpoint_store.save(state["session_id"], state["run_id"], "execute_tool", state)
        return state

    async def _observe(self, state: AgentGraphState) -> AgentGraphState:
        if self._should_skip_resume_stage(state, "observe"):
            return state
        results = state.get("tool_results", [])
        real_results = [item for item in results if not item.metadata.get("synthetic")]
        if real_results:
            latest = real_results[-1]
            if latest.success:
                state.setdefault("observations", []).append(f"工具 {latest.tool_name} 执行成功：{latest.output}")
            else:
                state.setdefault("observations", []).append(f"工具 {latest.tool_name} 执行失败：{latest.error}")

        compaction_report = self.context_compactor.maybe_compact(state)
        if compaction_report:
            await self.trace_recorder.record(
                state["trace_id"],
                TraceEventName.CONTEXT_COMPACTION.value,
                compaction_report.model_dump(),
                state["run_id"],
            )

        if self.loop_controller.failure_budget_reached(state):
            state["should_stop"] = True
            state["status"] = RuntimeStatus.FAIL.value
            state["stop_reason"] = state.get("stop_reason") or StopReason.MAX_FAILURES.value
            state["answer"] = "工具连续失败，已停止执行。"
        await self.trace_recorder.record(
            state["trace_id"], TraceEventName.OBSERVE.value, {"failure_count": state.get("failure_count", 0)}, state["run_id"]
        )
        await self.checkpoint_store.save(state["session_id"], state["run_id"], "observe", state)
        return state

    async def _reflect(self, state: AgentGraphState) -> AgentGraphState:
        if self._should_skip_resume_stage(state, "reflect"):
            return state
        latest_result = None
        for item in reversed(state.get("tool_results", [])):
            if not item.metadata.get("synthetic"):
                latest_result = item
                break
        payload = {
            "has_latest_result": latest_result is not None,
            "latest_success": latest_result.success if latest_result else None,
            "failure_count": state.get("failure_count", 0),
            "turn_count": state.get("turn_count", 0),
        }
        await self.trace_recorder.record(state["trace_id"], TraceEventName.REFLECT.value, payload, state["run_id"])
        await self.checkpoint_store.save(state["session_id"], state["run_id"], "reflect", state)
        return state

    async def _finalize(self, state: AgentGraphState) -> AgentGraphState:
        task = state.get("task_understanding")
        directive = state.get("directive")
        plan = state.get("plan")
        if not state.get("answer"):
            if task and task.clarification.needed:
                state["answer"] = task.clarification.question or "需要进一步澄清。"
                state["status"] = RuntimeStatus.PAUSED.value
                state["stop_reason"] = StopReason.NEED_CLARIFICATION.value
            elif directive and directive.get("answer"):
                state["answer"] = str(directive.get("answer"))
            elif plan and plan.need_clarification:
                state["answer"] = plan.clarification_question or "需要进一步澄清。"
                state["status"] = RuntimeStatus.PAUSED.value
            elif plan and plan.final_answer:
                state["answer"] = plan.final_answer
            elif state.get("observations"):
                state["answer"] = "\n".join(state["observations"][-3:])
            elif directive:
                state["answer"] = ""
            else:
                state["answer"] = "任务执行完成。"
        if not state.get("status") or state.get("status") == RuntimeStatus.RUNNING.value:
            state["status"] = RuntimeStatus.SUCCESS.value
        state["stop_reason"] = state.get("stop_reason") or StopReason.TASK_COMPLETE.value
        state["should_stop"] = True
        await self.trace_recorder.record(
            state["trace_id"], TraceEventName.FINALIZE.value, {"status": state.get("status")}, state["run_id"]
        )
        await self.checkpoint_store.save(state["session_id"], state["run_id"], "finalize", state)
        return state

    def _can_execute_in_parallel(self, calls) -> bool:
        if len(calls) <= 1:
            return False
        for call in calls:
            tool = self.tool_runtime.registry.get(call.name)
            if not tool or not tool.definition().concurrency_safe:
                return False
        return True

    def _include_required_tools(self, task: TaskUnderstandingResult, tools: list[ToolDefinition], limit: int) -> list[ToolDefinition]:
        """将能力卡声明的 required_tools 补入候选工具，避免关键词召回漏掉关键工具。"""
        if not task:
            return tools
        implementation = task.metadata.get("implementation") if isinstance(task.metadata, dict) else None
        required_names = implementation.get("required_tools") if isinstance(implementation, dict) else []
        if not required_names:
            return tools
        selected = list(tools or [])
        seen = {tool.name for tool in selected}
        for name in required_names:
            if name in seen:
                continue
            tool = self.tool_runtime.registry.get(str(name))
            if not tool:
                continue
            selected.append(tool.definition())
            seen.add(tool.name)
        return selected[: max(limit, len(selected))]

    def _route_after_task_understanding(self, state: AgentGraphState) -> Literal["finalize", "collect_context"]:
        # understanding_only：调用方只需要意图/能力路由/directive，理解完即收口，
        # 跳过上下文装配、Tool Search、Planner 与合成，避免多余的 LLM/工具往返拉长首字延迟。
        metadata = state.get("metadata") or {}
        if metadata.get("understanding_only"):
            return "finalize"
        profile = self.task_understanding.get_profile(state.get("profile"))
        return self.loop_controller.route_after_task_understanding(state, profile)

    def _should_skip_resume_stage(self, state: AgentGraphState, stage: str) -> bool:
        skip_until = state.get("_resume_skip_until")
        if not skip_until:
            return False
        order = [
            "understand_goal",
            "task_understanding",
            "collect_context",
            "tool_search",
            "plan",
            "budget_check",
            "execute_tool",
            "observe",
            "reflect",
        ]
        aliases = {
            "search_tools": "tool_search",
            "make_plan": "plan",
            "run_tool": "execute_tool",
            "observe_result": "observe",
        }
        normalized_skip = aliases.get(str(skip_until), str(skip_until))
        if normalized_skip not in order or stage not in order:
            return False
        if order.index(stage) <= order.index(normalized_skip):
            if stage == normalized_skip:
                state.pop("_resume_skip_until", None)
            return True
        return False

    def _route_after_plan(self, state: AgentGraphState) -> Literal["finalize", "budget_check"]:
        return self.loop_controller.route_after_plan(state)

    def _route_after_budget(self, state: AgentGraphState) -> Literal["execute_tool", "finalize"]:
        return self.loop_controller.route_after_budget(state)

    def _route_after_reflect(self, state: AgentGraphState) -> Literal["tool_search", "finalize"]:
        return self.loop_controller.route_after_reflect(state)
