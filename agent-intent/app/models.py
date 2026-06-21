from typing import Any, Dict

from pydantic import BaseModel, Field


class IntentRequest(BaseModel):
    message: str = Field(min_length=1)


class IntentResult(BaseModel):
    domain: str
    intent: str
    confidence: float
    secondary: list[str] = Field(default_factory=list)
    risk: str
    needs_clarification: bool
    next_action: str
    slots: Dict[str, Any] = Field(default_factory=dict)
    router: str = "rule"
