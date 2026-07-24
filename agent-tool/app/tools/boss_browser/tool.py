"""Boss 直聘 boss-cli 工具执行器。

实现放在 agent-tool 中，Runtime 只负责工具选择/权限/编排并通过工具服务调用。
底层使用 jackwener/boss-cli 的本地 Cookie 与 HTTP API 能力，不启动或连接 Chrome CDP。
"""

from __future__ import annotations

import asyncio
import threading
from typing import Any, Coroutine, Dict, TypeVar

from app.models import ToolError, ToolResult
from app.tools.boss_browser.core.boss_cli_engine import BossCliUpstreamRateLimited
from app.tools.boss_browser.core.rate_limiter import BackstopError, RateLimitError, RiskCooldownError
from app.tools.boss_browser.core.response import (
    CODE_AUTH_REQUIRED,
    CODE_BROWSER_ERROR,
    CODE_RATE_LIMITED,
    CODE_RISK_CONTROL,
    err,
    ok,
)
from app.tools.boss_browser.core.service import AuthRequiredError, RiskControlError, get_service

_ALLOWED_OPERATIONS = {
    "status",
    "refresh_auth",
    "qr_start",
    "qr_status",
    "qr_cancel",
    "search",
    "favorite_list",
    "detail",
    "profile",
    "rate",
}

_T = TypeVar("_T")

# Boss 工具服务是进程内单例（get_service 走 lru_cache），RateLimiter 与取数引擎
# 持有 asyncio.Lock。所有协程统一提交到常驻后台事件循环，确保单例异步原语始终
# 绑定到同一个长生命周期循环。
_loop_lock = threading.Lock()
_loop: asyncio.AbstractEventLoop | None = None


def _ensure_loop() -> asyncio.AbstractEventLoop:
    global _loop
    if _loop is not None and not _loop.is_closed():
        return _loop
    with _loop_lock:
        if _loop is not None and not _loop.is_closed():
            return _loop
        loop = asyncio.new_event_loop()
        thread = threading.Thread(
            target=loop.run_forever,
            name="boss-browser-loop",
            daemon=True,
        )
        thread.start()
        _loop = loop
        return _loop


def _run_on_loop(coro: Coroutine[Any, Any, _T]) -> _T:
    loop = _ensure_loop()
    return asyncio.run_coroutine_threadsafe(coro, loop).result()


def run_boss_browser(arguments: Dict[str, Any], trace_id: str | None = None) -> ToolResult:
    operation = str((arguments or {}).get("operation") or "").strip()
    payload = (arguments or {}).get("payload", {})
    if payload is None:
        payload = {}
    if operation not in _ALLOWED_OPERATIONS:
        return _tool_error(
            trace_id,
            code="invalid_arguments",
            message=f"不支持的 Boss 操作: {operation}",
            retryable=False,
            suggested_action=(
                "operation 必须是 status、refresh_auth、qr_start、qr_status、qr_cancel、search、favorite_list、detail、profile 或 rate。"
            ),
        )
    if not isinstance(payload, dict):
        return _tool_error(
            trace_id,
            code="invalid_arguments",
            message="payload 必须是对象",
            retryable=False,
            suggested_action="请传入 JSON object 作为 payload。",
        )

    envelope = _run_on_loop(_dispatch(operation, payload))
    code = int(envelope.get("code") or 500)
    message = str(envelope.get("message") or "success")
    if 200 <= code < 300:
        return ToolResult(
            status="success",
            summary=message,
            data=envelope,
            trace_id=trace_id,
        )
    return ToolResult(
        status="error",
        summary=message,
        data=envelope,
        trace_id=trace_id,
        error=ToolError(
            code=_error_code(code),
            message=message,
            retryable=code in {CODE_AUTH_REQUIRED, CODE_RATE_LIMITED, CODE_BROWSER_ERROR},
            suggested_action=_suggested_action(code),
        ),
    )


async def _dispatch(operation: str, payload: Dict[str, Any]) -> Dict[str, Any]:
    try:
        owner_key = str(payload.pop("_trusted_owner_key", "") or "").strip()
        if not owner_key:
            return err("Boss 工具调用缺少可信所有者身份", code=400)
        service = get_service(owner_key)
        service.load_credential_json(payload.get("credential_json"))
        if operation == "status":
            return ok(await service.status())
        if operation == "refresh_auth":
            return ok(await service.refresh_auth())
        if operation == "qr_start":
            return ok(await service.qr_start(str(payload.get("session_id") or "").strip()))
        if operation == "qr_status":
            return ok(
                await service.qr_status(
                    str(payload.get("session_id") or "").strip(),
                    str(payload.get("session_token") or "").strip(),
                )
            )
        if operation == "qr_cancel":
            return ok(
                await service.qr_cancel(
                    str(payload.get("session_id") or "").strip(),
                    str(payload.get("session_token") or "").strip(),
                )
            )
        if operation == "rate":
            return ok(service.rate_snapshot())
        if operation == "favorite_list":
            return ok(await service.favorite_jobs(page=int(payload.get("page") or 1)))
        if operation == "search":
            jobs = await service.search(
                query=str(payload.get("query") or ""),
                city=str(payload.get("city") or ""),
                page=int(payload.get("page") or 1),
                extra={
                    key: payload.get(key)
                    for key in ("experience", "salary", "degree", "industry", "scale", "stage")
                    if payload.get(key) not in (None, "")
                },
            )
            return ok({"jobs": jobs, "count": len(jobs)})
        if operation == "detail":
            return ok(
                await service.detail(
                    security_id=str(payload.get("securityId") or payload.get("security_id") or ""),
                    url=str(payload.get("url") or ""),
                )
            )
        if operation == "profile":
            return ok(await service.profile())
        return err(f"未知 Boss 工具操作: {operation}", code=CODE_BROWSER_ERROR)
    except AuthRequiredError as exc:
        return err(str(exc), code=CODE_AUTH_REQUIRED)
    except RiskControlError as exc:
        return err(str(exc), code=CODE_RISK_CONTROL)
    except (RateLimitError, RiskCooldownError, BackstopError, BossCliUpstreamRateLimited) as exc:
        return err(str(exc), code=CODE_RATE_LIMITED)
    except Exception as exc:  # noqa: BLE001
        return err(str(exc), code=CODE_BROWSER_ERROR)


def _tool_error(
    trace_id: str | None,
    *,
    code: str,
    message: str,
    retryable: bool,
    suggested_action: str,
) -> ToolResult:
    return ToolResult(
        status="error",
        summary=message,
        trace_id=trace_id,
        error=ToolError(code=code, message=message, retryable=retryable, suggested_action=suggested_action),
    )


def _error_code(code: int) -> str:
    if code == CODE_AUTH_REQUIRED:
        return "boss_auth_required"
    if code == CODE_RISK_CONTROL:
        return "boss_risk_control"
    if code == CODE_RATE_LIMITED:
        return "boss_rate_limited"
    return "boss_browser_error"


def _suggested_action(code: int) -> str:
    if code == CODE_AUTH_REQUIRED:
        return (
            "请尝试二维码登录。浏览器 Cookie 导入默认关闭；仅在明确接受系统钥匙串授权时才显式开启后调用 refresh_auth。"
        )
    if code == CODE_RISK_CONTROL:
        return "已命中风控信号，请停止自动访问，等待账号自然恢复。"
    if code == CODE_RATE_LIMITED:
        return "请等待限速或冷却窗口结束，不要清状态文件或绕过 RateLimiter。"
    return "请确认已安装并同步 kabi-boss-cli，且本机已有可用 Boss Cookie；必要时先在常用浏览器登录 Boss 后重试。"
