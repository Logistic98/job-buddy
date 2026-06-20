
"""任务理解使用的纯文本匹配与归一化工具。

这些函数无状态、可独立测试，被槽位抽取与离线能力打分共同复用，
从原 TaskUnderstandingService 中抽离以保持单一职责。
"""

from __future__ import annotations

import math
import re
from typing import Any, List


def normalize_text(value: str) -> str:
    return re.sub(r"\s+", "", (value or "").lower())


def tokens(value: str) -> List[str]:
    text = normalize_text(value)
    ascii_tokens = re.findall(r"[a-z0-9_+#.-]+", text)
    chinese_chunks = re.findall(r"[一-鿿]{2,}", text)
    grams: List[str] = []
    for chunk in chinese_chunks:
        if len(chunk) <= 4:
            grams.append(chunk)
        else:
            grams.extend(chunk[i:i + 2] for i in range(0, len(chunk) - 1))
    return ascii_tokens + grams


def overlap_score(left: set[str], right: set[str]) -> float:
    if not left or not right:
        return 0.0
    return len(left & right) / math.sqrt(len(left) * len(right)) * 2.0


def phrase_match(phrase: str, normalized_message: str) -> bool:
    normalized_phrase = normalize_text(phrase)
    if not normalized_phrase:
        return False
    if normalized_phrase in normalized_message:
        return True
    phrase_tokens = set(tokens(normalized_phrase))
    message_tokens = set(tokens(normalized_message))
    return bool(phrase_tokens) and len(phrase_tokens & message_tokens) >= max(1, min(2, len(phrase_tokens)))


def score_to_confidence(raw_score: float) -> float:
    return round(max(0.35, min(0.95, 1 - math.exp(-raw_score / 5.0))), 4)


def coerce_value(value: str) -> Any:
    if re.fullmatch(r"[-+]?\d+", str(value)):
        return int(value)
    if re.fullmatch(r"[-+]?\d+\.\d+", str(value)):
        return float(value)
    return value
