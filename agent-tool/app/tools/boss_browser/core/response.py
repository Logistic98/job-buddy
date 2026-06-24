"""统一响应结构，遵循项目接口规范 {code, message, data}。"""

from __future__ import annotations

from typing import Any


def ok(data: Any = None, message: str = "success") -> dict[str, Any]:
    return {"code": 200, "message": message, "data": data}


def err(message: str, code: int = 500, data: Any = None) -> dict[str, Any]:
    return {"code": code, "message": message, "data": data}


# 业务错误码，集中维护，便于 Java 侧按语义分流。
CODE_AUTH_REQUIRED = 4001
CODE_RISK_CONTROL = 4002
CODE_RATE_LIMITED = 4003
CODE_BROWSER_ERROR = 5001
CODE_UPSTREAM_TIMEOUT = 5040
