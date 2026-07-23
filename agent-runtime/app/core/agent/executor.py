import asyncio
import os
from typing import AsyncIterator, Dict, List
from uuid import uuid4

from loguru import logger

from app.core.agent.graph import AgentGraphBuilder
from app.core.capability.registry import CapabilityRegistry
from app.core.checkpoint.store import CheckpointStore
from app.core.common.constants import PermissionMode, RuntimeStatus, TraceEventName
from app.core.common.settings import settings
from app.core.context.assembler import ContextAssembler
from app.core.intent.task_understanding import TaskUnderstandingService
from app.core.llm.openai_client import OpenAICompatibleClient
from app.core.llm.usage import current_usage, start_usage_tracking
from app.core.observability.trace import TraceRecorder, bind_trace_context, unbind_trace_context
from app.core.planner.planner import RuntimePlanner
from app.core.prompt.loader import PromptTemplateLoader
from app.core.tool.gateway import ToolGateway
from app.core.tool.registry import ToolRegistry
from app.core.tool.runtime import ToolRuntime
from app.core.tool.search import ToolSearchService
from app.core.utils.time_utils import ExecutionTimer, TimeUtils
from app.core.workflow.registry import WorkflowRegistry
from app.models.schemas import (
    AgentPlan,
    AgentRunRequest,
    AgentRunResponse,
    ChatMessage,
    PermissionRecord,
    TaskUnderstandingResult,
    ToolCall,
    ToolDefinition,
    ToolResult,
)
from app.tools_builtin import register_builtin_tools


class AgentExecutor:
    """Agent 统一执行入口。

    Executor 只负责组装 Runtime Core 组件，不承载业务规则：Profile/Capability/Prompt 由配置加载，
    Graph 负责 Agent Loop，Planner 负责下一步动作，ToolRuntime 负责权限和执行。
    """

    def __init__(self, registry: ToolRegistry = None, llm_client: OpenAICompatibleClient = None, use_llm: bool = None):
        self.registry = registry or ToolRegistry()
        if registry is None:
            register_builtin_tools(self.registry)
        if use_llm is None:
            use_llm = settings.config.runtime.use_llm_planner
        llm_api_key = str(settings.config.llm_service.api_key or "").strip()
        has_llm_key = bool(llm_api_key and llm_api_key.upper() not in {"EMPTY", "NONE", "NULL"})
        self.llm_client = llm_client or (OpenAICompatibleClient() if use_llm and has_llm_key else None)
        self.default_llm_client = self.llm_client
        self.allow_semantic_fallback = not use_llm
        self.prompt_loader = PromptTemplateLoader()
        self.capability_registry = CapabilityRegistry()
        self.workflow_registry = WorkflowRegistry(capability_registry=self.capability_registry)
        self.task_understanding = TaskUnderstandingService(
            capability_registry=self.capability_registry,
            llm_client=self.llm_client,
            prompt_loader=self.prompt_loader,
            allow_semantic_fallback=self.allow_semantic_fallback,
            workflow_registry=self.workflow_registry,
        )
        self.tool_search = ToolSearchService(self.registry)
        self.tool_runtime = ToolRuntime(self.registry)
        self.tool_gateway = ToolGateway(self.registry, self.tool_search, self.tool_runtime)
        self.context_assembler = ContextAssembler()
        self.planner = RuntimePlanner(llm_client=self.llm_client, prompt_loader=self.prompt_loader)
        self.checkpoint_store = CheckpointStore(database_url=os.getenv("AGENT_RUNTIME_DATABASE_URL", ""))
        self.trace_recorder = TraceRecorder()
        self.graph = AgentGraphBuilder(
            planner=self.planner,
            tool_search=self.tool_search,
            tool_runtime=self.tool_runtime,
            task_understanding=self.task_understanding,
            checkpoint_store=self.checkpoint_store,
            trace_recorder=self.trace_recorder,
            tool_gateway=self.tool_gateway,
            context_assembler=self.context_assembler,
        ).build()

    async def aclose(self) -> None:
        if self.default_llm_client is not None and hasattr(self.default_llm_client, "aclose"):
            await self.default_llm_client.aclose()
        if hasattr(self.context_assembler.memory_client, "aclose"):
            await self.context_assembler.memory_client.aclose()
        await self.checkpoint_store.close()

    async def execute(self, request: AgentRunRequest) -> AgentRunResponse:
        timer = ExecutionTimer()
        timer.start()
        trace_id = TimeUtils.gen_trace_id(request.trace_id)
        run_id = TimeUtils.gen_run_id()
        session_id = request.session_id or f"session_{uuid4().hex[:16]}"
        # 日志上下文贯穿整次 run：graph、工具、LLM 客户端等嵌套模块日志自动携带链路字段。
        request_meta = request.metadata or {}
        trace_context_token = bind_trace_context(
            request_id=request.trace_id or request_meta.get("request_id"),
            session_id=session_id,
            run_id=run_id,
            user_id=str(request_meta.get("user_id") or request_meta.get("operator_id") or ""),
            actor=str(request_meta.get("actor") or "runtime"),
            component="agent-runtime",
            request_path=str(request_meta.get("request_path") or request_meta.get("path") or ""),
            environment=str(request_meta.get("environment") or "runtime"),
        )
        try:
            # 日志上下文贯穿整次 run：graph、工具、LLM 客户端等嵌套模块日志自动携带链路字段。
            with logger.contextualize(run_id=run_id, session_id=session_id, trace_id=trace_id):
                return await self._execute_inner(request, timer, trace_id, run_id, session_id)
        finally:
            unbind_trace_context(trace_context_token)

    async def _execute_inner(
        self, request: AgentRunRequest, timer: ExecutionTimer, trace_id: str, run_id: str, session_id: str
    ) -> AgentRunResponse:
        logger.info("Agent 执行开始")
        await self.trace_recorder.record(
            trace_id, TraceEventName.RUN_START.value, {"session_id": session_id}, run_id=run_id
        )
        llm_client = self._resolve_request_llm(request)
        owns_llm_client = llm_client is not None and llm_client is not self.default_llm_client
        graph = self.graph if not owns_llm_client else self._build_graph(llm_client)
        start_usage_tracking()

        state = await self._initial_state(request, session_id, run_id, trace_id)

        try:
            final_state = await graph.ainvoke(state)
            timer.end()
            await self._record_llm_usage(trace_id, run_id, llm_client)
            await self.trace_recorder.record(
                trace_id,
                TraceEventName.RUN_END.value,
                {"status": final_state.get("status"), "latency_ms": timer.get_latency_ms()},
                run_id=run_id,
                status=self._trace_status(final_state.get("status")),
            )
            logger.info(f"Agent 执行完成：status={final_state.get('status')}")
            return AgentRunResponse(
                run_id=run_id,
                trace_id=trace_id,
                session_id=session_id,
                status=RuntimeStatus(final_state.get("status") or RuntimeStatus.SUCCESS.value),
                start_time=timer.start_time,
                end_time=timer.end_time,
                latency_ms=timer.get_latency_ms(),
                answer=final_state.get("answer") or "",
                messages=request.messages,
                plan=final_state.get("plan"),
                directive=final_state.get("directive"),
                task_understanding=final_state.get("task_understanding"),
                tool_results=final_state.get("tool_results", []),
                permission_records=final_state.get("permission_records", []),
                logs=final_state.get("logs", []),
                trace_events=self.trace_recorder.list_by_run(run_id),
                metrics=self._collect_metrics(final_state, llm_client),
                stop_reason=final_state.get("stop_reason"),
            )
        except Exception as e:
            timer.end()
            latest_checkpoint = await self.checkpoint_store.load_latest(session_id)
            if latest_checkpoint and latest_checkpoint.get("state"):
                latest_state = self._hydrate_state(latest_checkpoint.get("state") or {})
                latest_state.update(
                    {
                        "run_id": run_id,
                        "trace_id": trace_id,
                        "session_id": session_id,
                        "metadata": request.metadata,
                        "_resume_skip_until": latest_checkpoint.get("stage"),
                    }
                )
                state = latest_state
            state["status"] = RuntimeStatus.FAIL.value
            state["stop_reason"] = "runtime_error"
            state["error"] = str(e)
            await self.checkpoint_store.save(session_id, run_id, "runtime_error", state)
            await self._record_llm_usage(trace_id, run_id, llm_client)
            await self.trace_recorder.record(
                trace_id,
                TraceEventName.RUN_END.value,
                {"error": str(e)},
                run_id=run_id,
                status="failed",
                error=str(e),
            )
            logger.exception("Agent 执行失败")
            return AgentRunResponse(
                run_id=run_id,
                trace_id=trace_id,
                session_id=session_id,
                status=RuntimeStatus.FAIL,
                start_time=timer.start_time,
                end_time=timer.end_time,
                latency_ms=timer.get_latency_ms(),
                answer="Agent Runtime 执行失败。",
                messages=request.messages,
                directive=state.get("directive"),
                task_understanding=state.get("task_understanding"),
                trace_events=self.trace_recorder.list_by_run(run_id),
                metrics=self._collect_metrics(state, llm_client),
                stop_reason="runtime_error",
                error=str(e),
            )
        finally:
            if owns_llm_client and hasattr(llm_client, "aclose"):
                await llm_client.aclose()

    async def execute_stream(self, request: AgentRunRequest) -> AsyncIterator[Dict]:
        """以 Token 流式执行问答，逐字 yield SSE 事件。

        事件形态：{"event": "token", "data": {"text": ...}}、
        {"event": "done", "data": {...}}、{"event": "error", "data": {"message": ...}}。

        路径决策：先做任务理解与上下文装配；澄清/安全拦截/directive 既有答案直接成段下发；
        能力声明 required_tools 的任务先跑完整 Graph Loop 收集观察，再流式合成；其余纯生成类
        任务跳过结构化往返，直接流式合成，缩短首字时间。
        """
        timer = ExecutionTimer()
        timer.start()
        trace_id = TimeUtils.gen_trace_id(request.trace_id)
        run_id = TimeUtils.gen_run_id()
        session_id = request.session_id or f"session_{uuid4().hex[:16]}"
        request_meta = request.metadata or {}
        trace_context_token = bind_trace_context(
            request_id=request.trace_id or request_meta.get("request_id"),
            session_id=session_id,
            run_id=run_id,
            user_id=str(request_meta.get("user_id") or request_meta.get("operator_id") or ""),
            actor=str(request_meta.get("actor") or "runtime"),
            component="agent-runtime",
            request_path=str(request_meta.get("request_path") or request_meta.get("path") or ""),
            environment=str(request_meta.get("environment") or "runtime"),
        )
        # 同 execute：日志上下文贯穿整次流式 run，消费方 await 间隙产生的日志同样携带链路字段。
        try:
            with logger.contextualize(run_id=run_id, session_id=session_id, trace_id=trace_id):
                async for event in self._execute_stream_inner(request, timer, trace_id, run_id, session_id):
                    yield event
        finally:
            unbind_trace_context(trace_context_token)

    async def _execute_stream_inner(
        self, request: AgentRunRequest, timer: ExecutionTimer, trace_id: str, run_id: str, session_id: str
    ) -> AsyncIterator[Dict]:
        logger.info("Agent 流式执行开始")
        await self.trace_recorder.record(
            trace_id, TraceEventName.RUN_START.value, {"session_id": session_id, "stream": True}, run_id=run_id
        )
        # 流式路径按请求解析本地客户端，不写实例属性，避免进程级单例执行器并发污染。
        llm_client = self._resolve_request_llm(request)
        owns_llm_client = llm_client is not None and llm_client is not self.default_llm_client
        task_understanding = (
            self.task_understanding
            if not owns_llm_client
            else TaskUnderstandingService(
                capability_registry=self.capability_registry,
                llm_client=llm_client,
                prompt_loader=self.prompt_loader,
                allow_semantic_fallback=self.allow_semantic_fallback,
                workflow_registry=self.workflow_registry,
            )
        )
        start_usage_tracking()

        accumulated: List[str] = []
        reasoning_acc: List[str] = []
        status = RuntimeStatus.SUCCESS.value
        stop_reason = "task_complete"
        metadata = request.metadata or {}
        try:
            # 首包优先：连接建立即下发处理中事件，模型思考阶段前先给到前端可见反馈，避免长时间空白。
            yield {"event": "processing", "data": {"message": "正在理解你的问题并准备作答。"}}
            short_answer = None
            messages: List[ChatMessage] = []
            graph_state: Dict = {}
            task = None
            directive = None
            upstream_directive = (
                metadata.get("upstream_directive") if isinstance(metadata.get("upstream_directive"), dict) else {}
            )
            upstream_contract = (
                upstream_directive.get("capability_contract")
                if isinstance(upstream_directive.get("capability_contract"), dict)
                else {}
            )
            upstream_required_tools = upstream_contract.get("required_tools") or []
            # runtime_execute 走直达合成快路径，但快路径不跑工具。若上游 directive 声明了 required_tools，
            # 直达合成会丢掉这些工具产出、给出空心答案，因此此时退回完整理解+工具收集路径，宁可牺牲首字延迟也要保证产出完整。
            if metadata.get("runtime_execute") and not upstream_required_tools:
                # Java 后端已完成任务理解与能力路由，这里跳过重复理解直达流式合成，缩短首字时间。
                await self.trace_recorder.record(
                    trace_id, TraceEventName.UNDERSTAND_GOAL.value, {"reused_upstream": True}, run_id=run_id
                )
                directive = upstream_directive
                answer = directive.get("answer")
                risk = str(directive.get("risk") or directive.get("risk_level") or "").strip().lower()
                if answer and self._truthy(directive.get("needs_clarification")):
                    short_answer = str(answer)
                    status = RuntimeStatus.PAUSED.value
                    stop_reason = "need_clarification"
                elif answer and risk in {"high", "blocked", "critical"}:
                    short_answer = str(answer)
                    status = RuntimeStatus.PAUSED.value
                    stop_reason = "safety_blocked"
                else:
                    messages = self._build_synthesis_messages_direct(request)
            else:
                await self.trace_recorder.record(trace_id, TraceEventName.UNDERSTAND_GOAL.value, run_id=run_id)
                task = await task_understanding.understand(request, session_id, run_id, trace_id)
                if upstream_required_tools:
                    task.metadata.setdefault("capability_contract", {})["required_tools"] = list(
                        upstream_required_tools
                    )
                    for key in ("allowed_tools", "evidence_requirements", "eval_rubric"):
                        if key in upstream_contract:
                            task.metadata["capability_contract"][key] = upstream_contract[key]
                profile = task_understanding.get_profile(task.profile)
                directive = task_understanding.build_directive(profile, task)
                await self.trace_recorder.record(
                    trace_id,
                    TraceEventName.TASK_UNDERSTANDING.value,
                    {
                        "profile": task.profile,
                        "router": task.router,
                        "domain": task.intent.domain,
                        "intent": task.intent.intent,
                        "confidence": task.intent.confidence,
                        "next_action": task.next_action,
                        "needs_clarification": task.clarification.needed,
                    },
                    run_id=run_id,
                )
                route_payload = task.routing.model_dump()
                workflow = task.metadata.get("workflow") if isinstance(task.metadata, dict) else None
                if isinstance(workflow, dict):
                    route_payload["workflow"] = dict(workflow)
                await self.trace_recorder.record(
                    trace_id, TraceEventName.CAPABILITY_ROUTE.value, route_payload, run_id=run_id
                )

                # 澄清 / 安全拦截 / directive 既有答案：成段下发，不进入合成。
                if task.clarification.needed:
                    short_answer = task.clarification.question or "需要进一步澄清。"
                    status = RuntimeStatus.PAUSED.value
                    stop_reason = "need_clarification"
                elif task.risk_flags.safety_blocked:
                    short_answer = task.answer or "请求被安全策略拦截。"
                    status = RuntimeStatus.PAUSED.value
                    stop_reason = "safety_blocked"
                elif directive and directive.get("answer"):
                    short_answer = str(directive.get("answer"))
                elif self._workflow_has_external_action(task):
                    # external_action 属于 BFF/外部执行器职责。Runtime 仅返回 workflow/directive 元数据，
                    # 不把配置中的动作名解释为工具或函数调用。
                    short_answer = ""
                    graph_state = {
                        "task_understanding": task,
                        "directive": directive,
                        "tool_results": [],
                        "permission_records": [],
                    }
                else:
                    graph_state = await self._execute_required_tools(
                        request, task, session_id, run_id, trace_id, llm_client
                    )
                    if graph_state:
                        status = str(graph_state.get("status") or RuntimeStatus.FAIL.value)
                        stop_reason = str(graph_state.get("stop_reason") or "runtime_error")
                        task = graph_state.get("task_understanding") or task
                        directive = graph_state.get("directive") or directive
                        if self._is_true_graph_success(graph_state, task):
                            observations = list(graph_state.get("observations") or [])
                            if llm_client is None:
                                short_answer = str(graph_state.get("answer") or "")
                            else:
                                messages = await asyncio.to_thread(
                                    self._build_synthesis_messages, request, task, observations
                                )
                        else:
                            if status == RuntimeStatus.SUCCESS.value:
                                status = RuntimeStatus.FAIL.value
                                stop_reason = "tool_execution_failed"
                            short_answer = str(graph_state.get("answer") or self._terminal_answer(status, stop_reason))
                    else:
                        messages = await asyncio.to_thread(self._build_synthesis_messages, request, task, [])

            if short_answer is not None:
                accumulated.append(short_answer)
                yield {"event": "token", "data": {"text": short_answer}}
            elif llm_client is None:
                # 纯生成路径无模型时退回非流式执行；required-tools 路径已直接使用 Graph 终态，禁止重复执行工具。
                response = await self.execute(request)
                accumulated.append(response.answer or "")
                yield {"event": "token", "data": {"text": response.answer or ""}}
                status = response.status.value
                stop_reason = response.stop_reason or stop_reason
                graph_state = {
                    "plan": response.plan,
                    "directive": response.directive,
                    "task_understanding": response.task_understanding,
                    "tool_results": response.tool_results,
                    "permission_records": response.permission_records,
                }
            else:
                async for piece in llm_client.stream_chat(
                    messages,
                    max_tokens=self._remaining_stream_tokens(request),
                ):
                    text = piece.get("text") if isinstance(piece, dict) else piece
                    if not text:
                        continue
                    if isinstance(piece, dict) and piece.get("type") == "reasoning":
                        reasoning_acc.append(text)
                        yield {"event": "reasoning", "data": {"text": text}}
                    else:
                        accumulated.append(text)
                        yield {"event": "token", "data": {"text": text}}

            timer.end()
            answer = "".join(accumulated)
            reasoning = "".join(reasoning_acc)
            await self.trace_recorder.record(trace_id, TraceEventName.FINALIZE.value, {"status": status}, run_id=run_id)
            await self._record_llm_usage(trace_id, run_id, llm_client)
            await self.trace_recorder.record(
                trace_id,
                TraceEventName.RUN_END.value,
                {"status": status, "latency_ms": timer.get_latency_ms(), "stream": True},
                run_id=run_id,
                status=self._trace_status(status),
            )
            logger.info(f"Agent 流式执行完成：chars={len(answer)}")
            yield {
                "event": "done",
                "data": {
                    "run_id": run_id,
                    "trace_id": trace_id,
                    "session_id": session_id,
                    "status": status,
                    "stop_reason": stop_reason,
                    "answer": answer,
                    "reasoning": reasoning,
                    "latency_ms": timer.get_latency_ms(),
                    "metrics": self._collect_metrics(graph_state, llm_client),
                    "plan": self._dump_model(graph_state.get("plan")),
                    "directive": graph_state.get("directive") or directive,
                    "task_understanding": self._dump_model(graph_state.get("task_understanding") or task),
                    "tool_results": [self._dump_model(item) for item in graph_state.get("tool_results", [])],
                    "permission_records": [
                        self._dump_model(item) for item in graph_state.get("permission_records", [])
                    ],
                    "trace_events": [event.model_dump() for event in self.trace_recorder.list_by_run(run_id)],
                },
            }
        except Exception as e:
            timer.end()
            logger.exception("Agent 流式执行失败")
            await self._record_llm_usage(trace_id, run_id, llm_client)
            await self.trace_recorder.record(
                trace_id,
                TraceEventName.RUN_END.value,
                {"error": str(e), "stream": True},
                run_id=run_id,
                status="failed",
                error=str(e),
            )
            yield {
                "event": "error",
                "data": {"message": str(e), "trace_id": trace_id, "session_id": session_id, "run_id": run_id},
            }
        finally:
            if owns_llm_client and hasattr(llm_client, "aclose"):
                await llm_client.aclose()

    async def _execute_required_tools(self, request, task, session_id, run_id, trace_id, llm_client=None) -> Dict:
        """能力声明 required_tools 时执行完整 Graph，并保留其真实终态。"""
        capability_contract = task.metadata.get("capability_contract") if isinstance(task.metadata, dict) else None
        required = capability_contract.get("required_tools") if isinstance(capability_contract, dict) else None
        if not required:
            return {}
        state = await self._initial_state(request, session_id, run_id, trace_id)
        state["request"] = request
        state["task_understanding"] = task
        state["directive"] = self.task_understanding.build_directive(
            self.task_understanding.get_profile(task.profile), task
        )
        state["objective"] = task.rewritten_query.planner_query or task.original_query or state.get("objective", "")
        state["profile"] = task.profile
        state["_resume_skip_until"] = "task_understanding"
        graph = self.graph if llm_client is self.default_llm_client else self._build_graph(llm_client)
        return await graph.ainvoke(state)

    def _is_true_graph_success(self, state: Dict, task: TaskUnderstandingResult | None) -> bool:
        if state.get("status") != RuntimeStatus.SUCCESS.value or state.get("stop_reason") != "task_complete":
            return False
        results = [item for item in state.get("tool_results", []) if not item.metadata.get("synthetic")]
        if any(not item.success for item in results):
            return False
        contract = task.metadata.get("capability_contract") if task and isinstance(task.metadata, dict) else None
        required = {str(item) for item in ((contract or {}).get("required_tools") or [])}
        succeeded = {item.tool_name for item in results if item.success}
        return required.issubset(succeeded)

    def _workflow_has_external_action(self, task: TaskUnderstandingResult | None) -> bool:
        workflow = task.metadata.get("workflow") if task and isinstance(task.metadata, dict) else None
        steps = workflow.get("steps") if isinstance(workflow, dict) else None
        return any(isinstance(step, dict) and bool(step.get("external_action")) for step in (steps or []))

    def _terminal_answer(self, status: str, stop_reason: str) -> str:
        if status == RuntimeStatus.NEED_CONFIRM.value:
            return "任务需要确认后才能继续执行。"
        if status == RuntimeStatus.PAUSED.value:
            return f"任务已暂停：{stop_reason}。"
        return f"任务执行失败：{stop_reason}。"

    def _dump_model(self, value):
        if value is None:
            return None
        return value.model_dump() if hasattr(value, "model_dump") else value

    def _build_synthesis_messages_direct(self, request) -> List[ChatMessage]:
        """快路径合成消息：复用 Java 后端的路由结果，不再二次任务理解。

        稳定系统前缀 + 单条用户消息（原始问题 + 精简个人上下文），保证首字延迟最低且不污染 Prompt 缓存前缀。
        """
        system_prompt = self.prompt_loader.load(
            "synthesis/default.md", fallback="你是答案合成器，直接输出面向用户的自然语言答案。"
        )
        original_query = str(request.messages[-1].content) if request.messages else ""
        metadata = request.metadata or {}
        objective = self._upstream_planner_query(metadata) or original_query
        personal = metadata.get("personal_context")
        context_lines: List[str] = []
        if isinstance(personal, dict):
            for key, value in personal.items():
                if value in (None, "", [], {}):
                    continue
                context_lines.append(f"- {key}: {value}")
        context_text = "\n".join(context_lines) if context_lines else "（无额外个人上下文）"
        if objective == original_query:
            task_text = f"用户问题：\n{original_query}"
        else:
            task_text = f"用户原始问题：\n{original_query}\n\n已解析的独立任务：\n{objective}"
        user_content = f"{task_text}\n\n已知个人上下文：\n{context_text}\n\n请据此直接生成面向用户的最终答案。"
        return [
            ChatMessage(role="system", content=system_prompt),
            ChatMessage(role="user", content=user_content),
        ]

    def _upstream_planner_query(self, metadata: Dict) -> str:
        directive = metadata.get("upstream_directive")
        if not isinstance(directive, dict):
            return ""
        task = directive.get("task")
        if not isinstance(task, dict):
            return ""
        rewrite = task.get("rewritten_query")
        if not isinstance(rewrite, dict):
            return ""
        for key in ("planner_query", "resolved_query"):
            value = rewrite.get(key)
            if value is not None and str(value).strip():
                return str(value).strip()
        return ""

    def _truthy(self, *values) -> bool:
        for value in values:
            if value is True:
                return True
            if isinstance(value, str) and value.strip().lower() == "true":
                return True
        return False

    def _build_synthesis_messages(self, request, task, observations) -> List[ChatMessage]:
        """构造答案合成消息：稳定的系统前缀 + 单条携带上下文与观察的用户消息。"""
        system_prompt = self.prompt_loader.load(
            "synthesis/default.md", fallback="你是答案合成器，直接输出面向用户的自然语言答案。"
        )
        assembled = self.context_assembler.assemble(
            messages=request.messages,
            task=task,
            observations=observations or [],
            tool_results=[],
            metadata=request.metadata or {},
        )
        objective = task.rewritten_query.planner_query or task.original_query or ""
        observation_text = "\n".join(observations or []) or "（无工具观察，请基于上下文与通用知识作答）"
        user_content = (
            f"用户目标：\n{objective}\n\n"
            f"上下文摘要（含 personal_context 一手证据）：\n{assembled['summary']}\n\n"
            f"工具观察：\n{observation_text}\n\n"
            f"请据此直接生成面向用户的最终答案。"
        )
        return [
            ChatMessage(role="system", content=system_prompt),
            ChatMessage(role="user", content=user_content),
        ]

    async def _initial_state(self, request: AgentRunRequest, session_id: str, run_id: str, trace_id: str):
        metadata = request.metadata or {}
        if metadata.get("resume_from_checkpoint") and session_id:
            checkpoint = await self.checkpoint_store.load_latest(session_id)
            if checkpoint and checkpoint.get("state"):
                state = self._hydrate_state(checkpoint.get("state") or {})
                previous_run_id = state.get("run_id")
                state["run_id"] = run_id
                state["trace_id"] = trace_id
                state["session_id"] = session_id
                state["messages"] = request.messages or state.get("messages", [])
                state["objective"] = (
                    str(request.messages[-1].content) if request.messages else state.get("objective", "")
                )
                state["permission_mode"] = self._request_permission_mode(request).value
                state["budget"] = self._effective_budget(request)
                state["metadata"] = metadata
                state["profile"] = str(metadata.get("profile") or state.get("profile") or "default")
                state["status"] = RuntimeStatus.RUNNING.value
                state["should_stop"] = False
                checkpoint_stage = checkpoint.get("stage")
                state["_resume_skip_until"] = (
                    state.get("_resume_skip_until") if checkpoint_stage == "runtime_error" else checkpoint_stage
                )
                state["_resumed_from_run_id"] = previous_run_id
                state.pop("answer", None)
                state.pop("error", None)
                self._attach_token_usage(state)
                logger.info(
                    f"从 checkpoint 恢复：session_id={session_id}, stage={checkpoint.get('stage')}, previous_run_id={previous_run_id}"
                )
                return state

        state = {
            "run_id": run_id,
            "trace_id": trace_id,
            "session_id": session_id,
            "messages": request.messages,
            "objective": str(request.messages[-1].content) if request.messages else "",
            "permission_mode": self._request_permission_mode(request).value,
            "budget": self._effective_budget(request),
            "metadata": metadata,
            "profile": str(metadata.get("profile") or metadata.get("agent_profile") or "default"),
            "status": RuntimeStatus.RUNNING.value,
            "should_stop": False,
            "turn_count": 0,
            "tool_call_count": 0,
            "failure_count": 0,
            "tool_results": [],
            "permission_records": [],
            "observations": [],
            "observed_tool_call_ids": [],
            "reflection": {},
            "logs": [],
            "metrics": {},
        }
        self._attach_token_usage(state)
        return state

    def _request_permission_mode(self, request: AgentRunRequest) -> PermissionMode:
        mode = request.permission_mode
        if mode == PermissionMode.AUTO and not settings.config.permission.allow_auto_permission_mode:
            logger.warning("请求声明 permission_mode=auto 未经服务端授权，已降级为 default")
            return PermissionMode.DEFAULT
        if mode == PermissionMode.BYPASS and not settings.config.permission.allow_bypass_permission_mode:
            logger.warning("请求声明 permission_mode=bypass 未经服务端授权，已降级为 default")
            return PermissionMode.DEFAULT
        return mode

    def _attach_token_usage(self, state) -> None:
        """把当前 run 的 token 累计器挂到 state，供 LoopController 做预算仲裁。

        累计器与客户端写入是同一可变字典对象，state 读取即为最新值；checkpoint 恢复
        路径同样覆盖恢复快照，token 预算按当前 run 重新累计，不跨 run 叠加。
        """
        usage = current_usage()
        if usage is not None:
            state["token_usage"] = usage

    def _effective_budget(self, request: AgentRunRequest) -> Dict:
        budget = request.budget.model_dump()
        if int(budget.get("max_tokens") or 0) <= 0:
            budget["max_tokens"] = max(1, int(settings.config.runtime.max_run_tokens or 32768))
        return budget

    def _remaining_stream_tokens(self, request: AgentRunRequest) -> int:
        run_limit = int(request.budget.max_tokens or settings.config.runtime.max_run_tokens or 32768)
        usage = current_usage() or {}
        remaining = max(1, run_limit - int(usage.get("total_tokens") or 0))
        response_limit = max(1, int(settings.config.llm_service.max_tokens or remaining))
        return min(response_limit, remaining)

    def _trace_status(self, runtime_status) -> str:
        value = str(runtime_status or "").lower()
        if value in {RuntimeStatus.FAIL.value, "failed", "error"}:
            return "failed"
        if value in {RuntimeStatus.PAUSED.value, RuntimeStatus.NEED_CONFIRM.value}:
            return value
        if value == RuntimeStatus.RUNNING.value:
            return "running"
        return "success"

    def _hydrate_state(self, state):
        hydrated = dict(state)
        hydrated["messages"] = [self._model(ChatMessage, item) for item in hydrated.get("messages", [])]
        if hydrated.get("task_understanding") is not None:
            hydrated["task_understanding"] = self._model(TaskUnderstandingResult, hydrated.get("task_understanding"))
        if hydrated.get("plan") is not None:
            hydrated["plan"] = self._model(AgentPlan, hydrated.get("plan"))
        hydrated["candidate_tools"] = [
            self._model(ToolDefinition, item) for item in hydrated.get("candidate_tools", [])
        ]
        if hydrated.get("selected_tool_call") is not None:
            hydrated["selected_tool_call"] = self._model(ToolCall, hydrated.get("selected_tool_call"))
        hydrated["tool_results"] = [self._model(ToolResult, item) for item in hydrated.get("tool_results", [])]
        hydrated["permission_records"] = [
            self._model(PermissionRecord, item) for item in hydrated.get("permission_records", [])
        ]
        hydrated.setdefault("observations", [])
        hydrated.setdefault("observed_tool_call_ids", [])
        hydrated.setdefault("reflection", {})
        hydrated.setdefault("logs", [])
        hydrated.setdefault("profile", "default")
        hydrated.setdefault("metrics", {})
        return hydrated

    def _resolve_request_llm(self, request: AgentRunRequest):
        """按请求解析使用的 LLM 客户端，纯函数无副作用。

        默认返回构造期固定的 default_llm_client；仅当 metadata 显式携带有效的 llm_service
        覆盖时才新建客户端。不写实例属性，避免进程级单例执行器在并发请求间相互污染。
        """
        metadata = request.metadata or {}
        override = metadata.get("llm_service") or metadata.get("llmService")
        if isinstance(override, dict):
            credential = str(
                override.get("api_key")
                or override.get("apiKey")
                or override.get("auth_token")
                or override.get("authToken")
                or ""
            ).strip()
            if credential and credential.upper() not in {"EMPTY", "NONE", "NULL", "****"}:
                return OpenAICompatibleClient.from_config(override)
        return self.default_llm_client

    def _build_graph(self, llm_client):
        """Build a request-local graph when a run overrides its LLM connection."""
        task_understanding = TaskUnderstandingService(
            capability_registry=self.capability_registry,
            llm_client=llm_client,
            prompt_loader=self.prompt_loader,
            allow_semantic_fallback=self.allow_semantic_fallback,
            workflow_registry=self.workflow_registry,
        )
        planner = RuntimePlanner(llm_client=llm_client, prompt_loader=self.prompt_loader)
        return AgentGraphBuilder(
            planner=planner,
            tool_search=self.tool_search,
            tool_runtime=self.tool_runtime,
            task_understanding=task_understanding,
            checkpoint_store=self.checkpoint_store,
            trace_recorder=self.trace_recorder,
            tool_gateway=self.tool_gateway,
            context_assembler=self.context_assembler,
        ).build()

    async def _record_llm_usage(self, trace_id: str, run_id: str, llm_client=None) -> None:
        """把 run 级 token 用量与缓存命中写入 Trace，供评估与成本归因使用。

        无模型调用（纯规则路径 / 无客户端）时不产生事件，保持 Trace 无噪声。
        """
        usage = current_usage()
        if not usage or not usage.get("llm_calls"):
            return
        payload: Dict = dict(usage)
        client = llm_client or self.llm_client
        if client and hasattr(client, "get_cache_metrics"):
            payload["llm_cache"] = client.get_cache_metrics()
        await self.trace_recorder.record(trace_id, TraceEventName.LLM_USAGE.value, payload, run_id=run_id)

    def _collect_metrics(self, state, llm_client=None):
        metrics = dict(state.get("metrics") or {})
        client = llm_client or self.llm_client
        if client and hasattr(client, "get_cache_metrics"):
            metrics["llm_cache"] = client.get_cache_metrics()
        token_usage = current_usage() or state.get("token_usage")
        if token_usage:
            metrics["token_usage"] = dict(token_usage)
        return metrics

    def _model(self, cls, value):
        if isinstance(value, cls):
            return value
        return cls(**value)
