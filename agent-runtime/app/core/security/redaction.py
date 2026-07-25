"""在状态、日志、Trace 或检查点持久化前脱敏敏感值。"""

from __future__ import annotations

import json
import re
from typing import Any

_REDACTED = "[REDACTED]"
_SENSITIVE_TEXT_PATTERNS = (
    re.compile(r"(?i)(authorization\s*[:=]\s*(?:bearer\s+)?)[^\s,;]+"),
    re.compile(
        r"""(?i)(["']?(?:api[_-]?key|auth[_-]?token|access[_-]?token|password|secret|token)["']?\s*[:=]\s*)"""
        r"""(?:"[^"]*"|'[^']*'|[^\s,;}]+)"""
    ),
    re.compile(r"(?i)(postgres(?:ql)?://[^:\s/@]+:)[^@\s]+(@)"),
)
_PII_TEXT_PATTERNS = (
    re.compile(r"(?i)(?<![\w.+-])[\w.+-]+@[\w-]+(?:\.[\w-]+)+(?![\w.-])"),
    re.compile(r"(?<!\d)(?:\+?86[-\s]?)?1[3-9]\d{9}(?!\d)"),
    re.compile(r"(?<!\d)\d{17}[\dXx](?!\d)"),
)
_SENSITIVE_KEYS = {
    "api_key",
    "apikey",
    "auth_token",
    "authorization",
    "cookie",
    "credential",
    "credential_json",
    "database_url",
    "dsn",
    "email",
    "id_card",
    "id_number",
    "identity_number",
    "mobile",
    "password",
    "phone",
    "phone_number",
    "secret",
    "token",
}


def _sensitive_key(key: Any) -> bool:
    normalized = str(key).strip().lower().replace("-", "_")
    if normalized in _SENSITIVE_KEYS:
        return True
    return normalized.endswith(("_api_key", "_auth_token", "_access_token", "_password", "_secret", "_credential"))


def redact_sensitive(value: Any, *, max_depth: int = 12, _depth: int = 0) -> Any:
    """在持久化或导出前递归脱敏凭据字段。"""
    if _depth > max_depth:
        return "[TRUNCATED]"
    if hasattr(value, "model_dump"):
        value = value.model_dump()
    # 映射按敏感键整值替换，其他容器递归处理。
    if isinstance(value, dict):
        return {
            str(key): _REDACTED
            if _sensitive_key(key)
            else redact_sensitive(item, max_depth=max_depth, _depth=_depth + 1)
            for key, item in value.items()
        }
    if isinstance(value, (list, tuple, set)):
        return [redact_sensitive(item, max_depth=max_depth, _depth=_depth + 1) for item in value]
    if isinstance(value, str):
        stripped = value.strip()
        # 字符串化 JSON 先还原结构再脱敏，普通文本使用凭据与隐私模式替换。
        if stripped.startswith(("{", "[")) and stripped.endswith(("}", "]")):
            try:
                decoded = json.loads(value)
            except (TypeError, ValueError):
                pass
            else:
                return json.dumps(
                    redact_sensitive(decoded, max_depth=max_depth, _depth=_depth + 1),
                    ensure_ascii=False,
                    separators=(",", ":"),
                )
        redacted = value
        for pattern in _SENSITIVE_TEXT_PATTERNS:
            redacted = pattern.sub(
                lambda match: (
                    f"{match.group(1)}{_REDACTED}{match.group(2) if match.lastindex and match.lastindex > 1 else ''}"
                ),
                redacted,
            )
        for pattern in _PII_TEXT_PATTERNS:
            redacted = pattern.sub(_REDACTED, redacted)
        return redacted
    return value
