from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field, model_validator

from app.core.common.constants import PermissionMode, RuntimeStatus, StepStatus, ToolKind, ToolRiskLevel


class ChatMessage(BaseModel):
    role: str
    content: Any
    name: Optional[str] = None
    tool_call_id: Optional[str] = None


class ToolDefinition(BaseModel):
    name: str
    description: str
    kind: ToolKind = ToolKind.BUILTIN
    input_schema: Dict[str, Any] = Field(default_factory=dict)
    output_schema: Dict[str, Any] = Field(default_factory=dict)
    risk_level: ToolRiskLevel = ToolRiskLevel.LOW
    tags: List[str] = Field(default_factory=list)
    aliases: List[str] = Field(default_factory=list)
    search_hint: Optional[str] = None
    timeout_seconds: int = 30
    max_retries: int = 0
    max_result_size_chars: int = 12000
    enabled: bool = True
    read_only: bool = True
    destructive: bool = False
    concurrency_safe: bool = True
    should_defer: bool = False
    always_load: bool = False


class ToolCall(BaseModel):
    id: str
    name: str
    arguments: Dict[str, Any] = Field(default_factory=dict)
    reason: Optional[str] = None
    plan_step_id: Optional[str] = None


class ToolResult(BaseModel):
    tool_call_id: str
    tool_name: str
    success: bool
    output: Any = None
    error: Optional[str] = None
    latency_ms: int = 0
    status: str = ""
    summary: Optional[str] = None
    data: Any = None
    warnings: List[str] = Field(default_factory=list)
    next_actions: List[str] = Field(default_factory=list)
    trace_id: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)

    @model_validator(mode="after")
    def normalize(self) -> "ToolResult":
        if self.data is None:
            self.data = self.output

        if not self.status:
            if self.success:
                self.status = "success"
            elif self.metadata.get("permission_denied"):
                self.status = "rejected"
            else:
                self.status = "error"

        if self.summary is None:
            if self.error:
                self.summary = self.error
            elif self.success:
                self.summary = f"{self.tool_name} 执行成功"
            else:
                self.summary = "工具执行失败"

        if self.metadata.get("warnings") and not self.warnings:
            raw = self.metadata.get("warnings")
            if isinstance(raw, list):
                self.warnings = [str(item) for item in raw]

        if self.metadata.get("suggested_action") and not self.next_actions:
            self.next_actions = [str(self.metadata.get("suggested_action"))]
        elif not self.next_actions:
            self.next_actions = []

        return self


class PermissionRecord(BaseModel):
    tool_call_id: str
    tool_name: str
    allowed: bool
    reason: str = ""
    requires_confirmation: bool = False
    mode: str = PermissionMode.DEFAULT.value


class AgentPlanStep(BaseModel):
    id: str
    goal: str
    tool_name: Optional[str] = None
    tool_arguments: Dict[str, Any] = Field(default_factory=dict)
    status: StepStatus = StepStatus.PENDING
    depends_on: List[str] = Field(default_factory=list)
    result_summary: Optional[str] = None
    error: Optional[str] = None


class AgentPlan(BaseModel):
    objective: str
    steps: List[AgentPlanStep] = Field(default_factory=list)
    tool_calls: List[ToolCall] = Field(default_factory=list)
    final_answer: Optional[str] = None
    is_complete: bool = False
    need_clarification: bool = False
    clarification_question: Optional[str] = None
    stop_reason: Optional[str] = None


class QueryRewrite(BaseModel):
    """Planner 前置改写结果。

    resolved_query 用于可解释日志，retrieval_query 用于能力召回，planner_query 用于计划生成。
    """

    resolved_query: str = ""
    retrieval_query: str = ""
    planner_query: str = ""


class ResolvedReference(BaseModel):
    text: str
    resolved_to: Optional[str] = None
    source: Optional[str] = None
    confidence: float = 0.0


class ContextUnderstanding(BaseModel):
    dependency: str = "optional"
    context_type: List[str] = Field(default_factory=list)
    resolved_references: List[ResolvedReference] = Field(default_factory=list)
    memory_policy: Dict[str, Any] = Field(default_factory=dict)


class IntentUnderstanding(BaseModel):
    execution_intent: str = "answer_question"
    domain: str = "general"
    intent: str = "chat"
    business_intents: List[str] = Field(default_factory=list)
    capability_intents: List[str] = Field(default_factory=list)
    confidence: float = 0.0
    secondary: List[str] = Field(default_factory=list)


class CapabilityCandidate(BaseModel):
    capability_id: str
    domain: str = "general"
    intent: str
    score: float = 0.0
    reason: str = ""
    next_action: str = "direct_answer"
    execution_mode: str = "OPEN_DOMAIN_QA"
    risk: str = "low"
    missing_slots: List[str] = Field(default_factory=list)


class RoutingUnderstanding(BaseModel):
    selected_capability: Optional[CapabilityCandidate] = None
    candidate_capabilities: List[CapabilityCandidate] = Field(default_factory=list)
    preferred_domains: List[str] = Field(default_factory=list)
    blocked_domains: List[str] = Field(default_factory=list)


class SlotValue(BaseModel):
    status: str
    value: Any = None
    source: Optional[str] = None


class SlotPrecheck(BaseModel):
    filled: Dict[str, Any] = Field(default_factory=dict)
    status: Dict[str, SlotValue] = Field(default_factory=dict)
    missing_required: List[str] = Field(default_factory=list)
    missing_optional: List[str] = Field(default_factory=list)
    need_confirm: List[str] = Field(default_factory=list)


class ClarificationDecision(BaseModel):
    needed: bool = False
    blocking: bool = False
    reason: Optional[str] = None
    question: Optional[str] = None
    suggested_options: List[str] = Field(default_factory=list)


class RiskFlags(BaseModel):
    risk_level: str = "low"
    high_risk_operation: bool = False
    need_secondary_confirmation: bool = False
    cross_domain_access: bool = False
    safety_blocked: bool = False


class PlannerConstraints(BaseModel):
    planner_needed: bool = False
    max_candidate_skills: int = 5
    risk_level: str = "low"
    allowed_capability_types: List[str] = Field(default_factory=lambda: ["tool", "workflow", "skill"])
    forbidden_skills: List[str] = Field(default_factory=list)
    execution_mode: str = "OPEN_DOMAIN_QA"


class TaskUnderstandingResult(BaseModel):
    trace_id: str = ""
    profile: str = "default"
    router: str = "semantic_config"
    original_query: str = ""
    rewritten_query: QueryRewrite = Field(default_factory=QueryRewrite)
    context: ContextUnderstanding = Field(default_factory=ContextUnderstanding)
    intent: IntentUnderstanding = Field(default_factory=IntentUnderstanding)
    routing: RoutingUnderstanding = Field(default_factory=RoutingUnderstanding)
    slots: SlotPrecheck = Field(default_factory=SlotPrecheck)
    clarification: ClarificationDecision = Field(default_factory=ClarificationDecision)
    risk_flags: RiskFlags = Field(default_factory=RiskFlags)
    planner_constraints: PlannerConstraints = Field(default_factory=PlannerConstraints)
    next_action: str = "direct_answer"
    answer: Optional[str] = None
    metadata: Dict[str, Any] = Field(default_factory=dict)


class BudgetConfig(BaseModel):
    max_turns: int = 12
    max_tool_calls: int = 20
    max_failures: int = 3
    # 单次 run 的 token 预算；显式传 0 时由 Runtime 回落到服务端有限默认值。
    max_tokens: int = 32768


class AgentRunRequest(BaseModel):
    messages: List[ChatMessage]
    trace_id: Optional[str] = None
    session_id: Optional[str] = None
    permission_mode: PermissionMode = PermissionMode.DEFAULT
    budget: BudgetConfig = Field(default_factory=BudgetConfig)
    stream: bool = False
    metadata: Dict[str, Any] = Field(default_factory=dict)


class AgentStepLog(BaseModel):
    step_id: str
    name: str
    status: str
    start_time: Optional[str] = None
    end_time: Optional[str] = None
    latency_ms: int = 0
    input: Dict[str, Any] = Field(default_factory=dict)
    output: Any = None
    error: Optional[str] = None


class TraceEvent(BaseModel):
    trace_id: str
    run_id: Optional[str] = None
    request_id: Optional[str] = None
    session_id: Optional[str] = None
    user_id: Optional[str] = None
    environment: Optional[str] = None
    request_path: Optional[str] = None
    event: str
    stage: Optional[str] = None
    node_id: Optional[str] = None
    component: Optional[str] = None
    actor: Optional[str] = None
    status: str = "success"
    duration_ms: Optional[int] = None
    error: Optional[str] = None
    span_id: Optional[str] = None
    schema_version: str = "v1"
    timestamp: str
    payload: Dict[str, Any] = Field(default_factory=dict)


class AgentRunResponse(BaseModel):
    run_id: str
    trace_id: str
    session_id: str
    status: RuntimeStatus
    start_time: str
    end_time: str
    latency_ms: int
    answer: str
    messages: List[ChatMessage]
    plan: Optional[AgentPlan] = None
    directive: Optional[Dict[str, Any]] = None
    task_understanding: Optional[TaskUnderstandingResult] = None
    tool_results: List[ToolResult] = Field(default_factory=list)
    permission_records: List[PermissionRecord] = Field(default_factory=list)
    logs: List[AgentStepLog] = Field(default_factory=list)
    trace_events: List[TraceEvent] = Field(default_factory=list)
    metrics: Dict[str, Any] = Field(default_factory=dict)
    stop_reason: Optional[str] = None
    error: Optional[str] = None


class APIResponse(BaseModel):
    code: int = 200
    message: str = "success"
    data: Any = None
