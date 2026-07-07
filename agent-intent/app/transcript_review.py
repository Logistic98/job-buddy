"""高风险动作的 Transcript 复核（规则实现）。

业务意图不等于动作授权：本模块只看用户消息与拟执行的工具调用，assistant 解释
一律剥离，防止被模型自我说服式的解释绕过。破坏性工具调用缺少用户意图背书时
直接拒绝；用户确有高风险意图时仍要求人工确认。
"""

import json
from typing import Any, Dict, List

from pydantic import BaseModel, Field

from .service import HIGH_RISK_KEYWORDS as ENTRY_HIGH_RISK_KEYWORDS

# 用户消息侧的高风险意图表达：复用入口词表并补充复核专属条目。
HIGH_RISK_KEYWORDS = [*ENTRY_HIGH_RISK_KEYWORDS, "转账", "清空", "批量投递"]
# 工具调用侧的破坏性标记：命中即认为动作具备破坏性，需要用户意图背书。
DESTRUCTIVE_MARKERS = [
    "delete",
    "drop",
    "truncate",
    "rm -rf",
    "rm ",
    "force",
    "删除",
    "清空",
    "生产",
    "prod",
    "批量投递",
    "转账",
]

DECISION_APPROVE = "approve"
DECISION_CONFIRM = "require_human_confirmation"
DECISION_DENY = "deny"


class TranscriptMessage(BaseModel):
    role: str
    content: str = ""


class TranscriptToolCall(BaseModel):
    name: str
    arguments: Dict[str, Any] = Field(default_factory=dict)


class TranscriptReviewRequest(BaseModel):
    messages: List[TranscriptMessage] = Field(default_factory=list)
    tool_calls: List[TranscriptToolCall] = Field(default_factory=list)


class TranscriptReviewResult(BaseModel):
    decision: str
    risk: str
    matched_rules: List[str] = Field(default_factory=list)
    reviewed_user_messages: int
    reviewed_tool_calls: int


def review_transcript(request: TranscriptReviewRequest) -> TranscriptReviewResult:
    user_text = " ".join(m.content or "" for m in request.messages if m.role == "user").lower()
    user_count = sum(1 for m in request.messages if m.role == "user")

    user_hits = [f"user_keyword:{k}" for k in HIGH_RISK_KEYWORDS if k in user_text]
    tool_hits: List[str] = []
    for call in request.tool_calls:
        call_text = f"{call.name} {json.dumps(call.arguments, ensure_ascii=False, default=str)}".lower()
        tool_hits.extend(f"tool_marker:{call.name}:{marker.strip()}" for marker in DESTRUCTIVE_MARKERS if marker in call_text)

    if tool_hits and not user_hits:
        # 破坏性动作缺少用户意图背书：视为可疑的模型自主行为或注入结果，直接拒绝。
        decision, risk = DECISION_DENY, "high"
    elif tool_hits or user_hits:
        # 用户确有高风险意图，或仅用户侧表达高风险：意图不等于授权，仍须人工确认。
        decision, risk = DECISION_CONFIRM, "high"
    else:
        decision, risk = DECISION_APPROVE, "low"

    return TranscriptReviewResult(
        decision=decision,
        risk=risk,
        matched_rules=[*user_hits, *tool_hits],
        reviewed_user_messages=user_count,
        reviewed_tool_calls=len(request.tool_calls),
    )
