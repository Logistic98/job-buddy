"""第二层意图分类器：加权关键词评分。

处理规则层未命中的样本。每个意图定义一组带权关键词,
按命中权重累计打分,归一化为置信度。规则层负责高频稳定意图,
本层负责模糊样本,未来可替换为 embedding 分类器而不影响上层编排。
"""

from typing import Optional

from .models import IntentResult

_INTENT_KEYWORDS: list[dict] = [
    {
        "domain": "job",
        "intent": "job.consult",
        "risk": "low",
        "next_action": "direct_answer_with_trace",
        "keywords": {
            "offer": 3,
            "跳槽": 3,
            "薪资": 2,
            "待遇": 2,
            "谈薪": 3,
            "裁员": 2,
            "试用期": 2,
            "入职": 2,
            "离职": 2,
            "晋升": 2,
            "职业规划": 3,
            "行情": 2,
        },
    },
    {
        "domain": "runtime",
        "intent": "complex_engineering_qa",
        "risk": "medium",
        "next_action": "create_agent_task",
        "secondary": ["plan", "tool_route"],
        "keywords": {
            "脚本": 2,
            "部署": 2,
            "调试": 2,
            "报错": 2,
            "异常": 2,
            "性能": 2,
            "架构": 2,
            "数据库": 2,
            "接口": 2,
            "bug": 3,
            "sql": 2,
            "api": 2,
        },
    },
    {
        "domain": "security",
        "intent": "high_risk_request",
        "risk": "high",
        "next_action": "request_human_confirmation",
        "secondary": ["needs_approval"],
        "needs_clarification": True,
        "keywords": {
            "权限": 2,
            "密码": 3,
            "账号": 2,
            "越权": 3,
            "绕过": 3,
            "破解": 3,
        },
    },
]

_SCORE_THRESHOLD = 3
_MAX_SCORE = 8.0


def score_intent(text: str) -> Optional[IntentResult]:
    """加权关键词评分,得分不足阈值返回 None 交给下一层。"""

    if not text:
        return None
    lower = text.lower()

    best_entry = None
    best_score = 0
    for entry in _INTENT_KEYWORDS:
        score = sum(weight for keyword, weight in entry["keywords"].items() if keyword in lower)
        if score > best_score:
            best_score = score
            best_entry = entry

    if best_entry is None or best_score < _SCORE_THRESHOLD:
        return None

    confidence = round(min(0.6 + (best_score / _MAX_SCORE) * 0.3, 0.9), 2)
    return IntentResult(
        domain=best_entry["domain"],
        intent=best_entry["intent"],
        confidence=confidence,
        secondary=list(best_entry.get("secondary", [])),
        risk=best_entry["risk"],
        needs_clarification=bool(best_entry.get("needs_clarification", False)),
        next_action=best_entry["next_action"],
        router="scorer",
    )
