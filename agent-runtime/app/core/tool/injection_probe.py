"""工具结果 Prompt Injection 探针。

工具返回、网页内容、Shell 输出默认是不可信数据。本模块提供规则级探针，
在结果进入上下文前扫描指令注入特征；命中时只做标记与告警，不阻断主流程，
由上层（observe/合成）根据标记降权处理。不做内容截断：规则存在误报可能，
截断会破坏合法结果，标记加告警既保留证据也保证功能可用。
"""

from __future__ import annotations

import re
from typing import Any, List

_MAX_SCAN_CHARS = 20000
_MAX_SCAN_DEPTH = 4

_INJECTION_RULES = [
    (
        "override_instructions_en",
        re.compile(
            r"(?i)\b(ignore|disregard|forget)\b.{0,40}\b(previous|prior|above|all)\b.{0,20}\b(instruction|prompt|rule)s?\b"
        ),
    ),
    ("override_instructions_zh", re.compile(r"忽略(之前|上面|以上|先前|所有)的?(全部|所有)?(指令|提示|规则|要求)")),
    (
        "role_hijack_en",
        re.compile(r"(?i)\byou are now\b|\bact as\b.{0,30}\b(system|admin|root)\b|\bnew system prompt\b"),
    ),
    ("role_hijack_zh", re.compile(r"你现在(是|扮演)|从现在开始你(必须|要)(扮演|作为)")),
    ("system_impersonation", re.compile(r"(?i)<\s*/?\s*system\s*>|\[\s*system\s*\]|^\s*system\s*:", re.MULTILINE)),
    (
        "prompt_exfiltration",
        re.compile(
            r"(?i)(reveal|print|output|show).{0,30}(system prompt|initial instructions)|(输出|打印|透露|展示)你的(系统提示|初始指令)"
        ),
    ),
    (
        "credential_exfiltration",
        re.compile(
            r"(?i)(send|post|upload|forward|exfiltrate).{0,40}(api[_ ]?key|secret|token|password|credential)|(发送|上传|转发|泄露).{0,20}(密钥|口令|凭证|令牌|api[_ ]?key|token)"
        ),
    ),
]


def probe_text(text: str) -> List[str]:
    """扫描单段文本，返回命中的规则名列表（去重、按规则顺序）。"""
    if not text:
        return []
    sample = text[:_MAX_SCAN_CHARS]
    hits: List[str] = []
    for name, pattern in _INJECTION_RULES:
        if pattern.search(sample):
            hits.append(name)
    return hits


def probe_payload(payload: Any, depth: int = 0) -> List[str]:
    """递归扫描工具输出结构中的字符串字段，深度与长度有界，避免超大结果拖慢主链路。"""
    if depth > _MAX_SCAN_DEPTH or payload is None:
        return []
    if isinstance(payload, str):
        return probe_text(payload)
    hits: List[str] = []
    if isinstance(payload, dict):
        for value in payload.values():
            hits.extend(probe_payload(value, depth + 1))
    elif isinstance(payload, (list, tuple)):
        for item in payload[:50]:
            hits.extend(probe_payload(item, depth + 1))
    return list(dict.fromkeys(hits))
