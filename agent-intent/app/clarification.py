"""统一澄清门（Clarification Gate）。

分类结果在出口处经过统一的置信度门：低于阈值且非高风险的结果强制走澄清，
不依赖各分类层自觉设置 needs_clarification。高风险结果不降级——其出口固定
为人工确认，不能被澄清门改写为普通澄清。
"""

import os

from .models import IntentResult

_DEFAULT_THRESHOLD = 0.5
_DEFAULT_QUESTION = "您的问题信息不足，请补充想要达成的目标或关键细节。"


def clarify_confidence_threshold() -> float:
    return float(os.getenv("AGENT_INTENT_CLARIFY_CONFIDENCE_THRESHOLD", str(_DEFAULT_THRESHOLD)))


def apply_clarification_gate(result: IntentResult) -> IntentResult:
    if result.risk == "high":
        return result
    if result.confidence >= clarify_confidence_threshold():
        return result
    result.needs_clarification = True
    result.next_action = "clarify"
    if "low_confidence" not in result.secondary:
        result.secondary.append("low_confidence")
    result.slots.setdefault("clarification_question", _DEFAULT_QUESTION)
    return result
