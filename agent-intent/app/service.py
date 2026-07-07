"""意图识别分层编排：规则层 → 评分层 → 可选 LLM 兜底 → 默认结果。

高频稳定意图(求职域、明确工程请求、高风险关键词)下沉在规则层;
规则未命中的模糊样本交给加权评分层;评分仍不足时由可配置的 LLM
兜底分类;全部未命中则按文本长度返回澄清或开放域默认结果。
结果中的 router 字段标记命中层级:rule / scorer / llm / fallback。
"""

from .clarification import apply_clarification_gate
from .domains.job import classify_job
from .llm_classifier import classify_with_llm
from .models import IntentResult
from .scorer import score_intent

# 入口高风险意图表达，transcript 复核在此基础上扩展破坏性标记。
HIGH_RISK_KEYWORDS = ["删除", "delete", "drop", "生产", "prod", "密钥", "token"]
_ENGINEERING_KEYWORDS = ["代码", "实现", "开发", "修复", "重构", "mvp", "workflow", "工作流"]


def classify_intent(message: str) -> IntentResult:
    # 出口统一过澄清门：任何一层给出的低置信结果都被兜住，高风险结果不降级。
    return apply_clarification_gate(_classify_intent(message))


def _classify_intent(message: str) -> IntentResult:
    text = (message or "").strip()
    if not text:
        return _clarify_result()

    rule_result = _classify_by_rules(text)
    if rule_result is not None:
        return rule_result

    scorer_result = score_intent(text)
    if scorer_result is not None:
        return scorer_result

    llm_result = classify_with_llm(text)
    if llm_result is not None:
        return llm_result

    if len(text) < 4:
        return _clarify_result()

    return IntentResult(
        domain="open_domain",
        intent="complex_question_answering",
        confidence=0.78,
        risk="low",
        needs_clarification=False,
        next_action="direct_answer_with_trace",
        router="fallback",
    )


def _classify_by_rules(text: str) -> IntentResult | None:
    lower = text.lower()
    if any(k in lower for k in HIGH_RISK_KEYWORDS):
        return IntentResult(
            domain="security",
            intent="high_risk_request",
            confidence=0.88,
            secondary=["needs_approval"],
            risk="high",
            needs_clarification=True,
            next_action="request_human_confirmation",
            router="rule",
        )

    job_result = classify_job(text)
    if job_result is not None:
        job_result.router = "rule"
        return job_result

    if any(k in lower for k in _ENGINEERING_KEYWORDS):
        return IntentResult(
            domain="runtime",
            intent="complex_engineering_qa",
            confidence=0.91,
            secondary=["plan", "tool_route"],
            risk="medium",
            needs_clarification=False,
            next_action="create_agent_task",
            router="rule",
        )
    return None


def _clarify_result() -> IntentResult:
    return IntentResult(
        domain="unknown",
        intent="unknown",
        confidence=0.2,
        risk="low",
        needs_clarification=True,
        next_action="clarify",
        router="fallback",
    )
