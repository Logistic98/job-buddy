
from typing import Any, Dict, List, Optional, TypedDict

from app.models.schemas import (
    AgentPlan,
    ChatMessage,
    PermissionRecord,
    TaskUnderstandingResult,
    ToolCall,
    ToolDefinition,
    ToolResult,
)


class AgentGraphState(TypedDict, total=False):
    run_id: str
    trace_id: str
    session_id: str
    objective: str
    messages: List[ChatMessage]
    context_summary: str
    task_understanding: Optional[TaskUnderstandingResult]
    directive: Optional[Dict[str, Any]]
    profile: str
    plan: Optional[AgentPlan]
    candidate_tools: List[ToolDefinition]
    selected_tool_call: Optional[ToolCall]
    selected_tool_calls: List[ToolCall]
    tool_results: List[ToolResult]
    permission_records: List[PermissionRecord]
    observations: List[str]
    answer: Optional[str]
    status: str
    stop_reason: Optional[str]
    should_stop: bool
    turn_count: int
    tool_call_count: int
    failure_count: int
    permission_mode: str
    budget: Dict[str, Any]
    metadata: Dict[str, Any]
    logs: List[Dict[str, Any]]
    context_payload: Dict[str, Any]
    metrics: Dict[str, Any]
    _resume_skip_until: Optional[str]
    _resumed_from_run_id: Optional[str]
