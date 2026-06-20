
from enum import Enum


class RuntimeStatus(str, Enum):
    SUCCESS = "success"
    FAIL = "fail"
    PAUSED = "paused"
    RUNNING = "running"
    NEED_CONFIRM = "need_confirm"


class StepStatus(str, Enum):
    PENDING = "pending"
    RUNNING = "running"
    SUCCESS = "success"
    FAIL = "fail"
    SKIPPED = "skipped"
    BLOCKED = "blocked"


class ToolKind(str, Enum):
    MCP = "mcp"
    API = "api"
    CLI = "cli"
    CODE = "code"
    BUILTIN = "builtin"


class ToolRiskLevel(str, Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"


class PermissionMode(str, Enum):
    DEFAULT = "default"
    PLAN = "plan"
    AUTO = "auto"
    BYPASS = "bypass"


class StopReason(str, Enum):
    TASK_COMPLETE = "task_complete"
    NEED_CLARIFICATION = "need_clarification"
    PERMISSION_DENIED = "permission_denied"
    MAX_TURNS = "max_turns"
    TOOL_BUDGET_EXCEEDED = "tool_budget_exceeded"
    MAX_FAILURES = "max_failures"
    TOOL_UNAVAILABLE = "tool_unavailable"
    SAFETY_BLOCKED = "safety_blocked"
    RUNTIME_ERROR = "runtime_error"


class TraceEventName(str, Enum):
    RUN_START = "run_start"
    PRECHECK = "precheck"
    UNDERSTAND_GOAL = "understand_goal"
    TASK_UNDERSTANDING = "task_understanding"
    CAPABILITY_ROUTE = "capability_route"
    CONTEXT_COLLECTED = "context_collected"
    TOOL_SEARCH = "tool_search"
    PLAN_CREATED = "plan_created"
    BUDGET_CHECK = "budget_check"
    PERMISSION_CHECK = "permission_check"
    TOOL_EXECUTE_START = "tool_execute_start"
    TOOL_EXECUTE_END = "tool_execute_end"
    OBSERVE = "observe"
    REFLECT = "reflect"
    FINALIZE = "finalize"
    RUN_END = "run_end"
