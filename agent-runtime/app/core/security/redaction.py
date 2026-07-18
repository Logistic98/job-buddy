from __future__ import annotations

import re
from typing import Any

_REDACTED = "[REDACTED]"
_SENSITIVE_TEXT_PATTERNS = (
    re.compile(r"(?i)(authorization\s*[:=]\s*(?:bearer\s+)?)[^\s,;]+"),
    re.compile(r"(?i)((?:api[_-]?key|auth[_-]?token|password|secret|token)\s*[:=]\s*)[^\s,;]+"),
    re.compile(r"(?i)(postgres(?:ql)?://[^:\s/@]+:)[^@\s]+(@)"),
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
    "password",
    "secret",
    "token",
}


def _sensitive_key(key: Any) -> bool:
    normalized = str(key).strip().lower().replace("-", "_")
    if normalized in _SENSITIVE_KEYS:
        return True
    return normalized.endswith(("_api_key", "_auth_token", "_access_token", "_password", "_secret", "_credential"))


def redact_sensitive(value: Any, *, max_depth: int = 12, _depth: int = 0) -> Any:
    """Recursively redact credential-bearing fields before persistence or export."""
    if _depth > max_depth:
        return "[TRUNCATED]"
    if hasattr(value, "model_dump"):
        value = value.model_dump()
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
        redacted = value
        for pattern in _SENSITIVE_TEXT_PATTERNS:
            redacted = pattern.sub(
                lambda match: (
                    f"{match.group(1)}{_REDACTED}{match.group(2) if match.lastindex and match.lastindex > 1 else ''}"
                ),
                redacted,
            )
        return redacted
    return value
