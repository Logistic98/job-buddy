from typing import Any, Dict, List, Optional

from pydantic import BaseModel, Field


class ToolExecuteRequest(BaseModel):
    arguments: Dict[str, Any] = Field(default_factory=dict)
    confirm: bool = False
    trace_id: Optional[str] = None


class ToolError(BaseModel):
    code: str
    message: str
    retryable: bool = False
    suggested_action: str = ""


class ToolResult(BaseModel):
    status: str
    summary: str
    data: Any = None
    warnings: List[str] = Field(default_factory=list)
    next_actions: List[str] = Field(default_factory=list)
    trace_id: Optional[str] = None
    error: Optional[ToolError] = None
