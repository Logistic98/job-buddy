import asyncio
from unittest.mock import AsyncMock, Mock

import pytest

from app.tools.boss_browser import tool
from app.tools.boss_browser.core.rate_limiter import RateLimitError
from app.tools.boss_browser.core.service import AuthRequiredError, RiskControlError


@pytest.mark.parametrize(
    ("operation", "method", "payload", "args", "kwargs"),
    [
        ("status", "status", {}, (), {}),
        ("refresh_auth", "refresh_auth", {}, (), {}),
        ("qr_start", "qr_start", {"session_id": " session "}, ("session",), {}),
        (
            "qr_status",
            "qr_status",
            {"session_id": "session", "session_token": "token", "wait_for_update": False},
            ("session", "token"),
            {"wait_for_update": False},
        ),
        ("qr_cancel", "qr_cancel", {"session_id": "session", "session_token": "token"}, ("session", "token"), {}),
        ("favorite_list", "favorite_jobs", {"page": 2}, (), {"page": 2}),
        ("detail", "detail", {"security_id": "id", "url": "url"}, (), {"security_id": "id", "url": "url"}),
        ("profile", "profile", {}, (), {}),
    ],
)
def test_dispatch_routes_trusted_owner_and_operation(monkeypatch, operation, method, payload, args, kwargs):
    service = Mock()
    service.credential_json.return_value = ""
    target = AsyncMock(return_value={"result": "done"})
    setattr(service, method, target)
    factory = Mock(return_value=service)
    monkeypatch.setattr(tool, "get_service", factory)
    envelope = asyncio.run(tool._dispatch(operation, {"_trusted_owner_key": "owner", **payload}))
    assert envelope["code"] == 200
    factory.assert_called_once_with("owner")
    target.assert_awaited_once_with(*args, **kwargs)


@pytest.mark.parametrize(
    ("error", "code"),
    [
        (AuthRequiredError("login"), "boss_auth_required"),
        (RiskControlError("risk"), "boss_risk_control"),
        (RateLimitError("quota"), "boss_rate_limited"),
        (RuntimeError("failure"), "boss_browser_error"),
    ],
)
def test_dispatch_errors_preserve_recovery_classification(monkeypatch, error, code):
    service = Mock()
    service.status = AsyncMock(side_effect=error)
    monkeypatch.setattr(tool, "get_service", lambda owner: service)
    monkeypatch.setattr(tool, "_run_on_loop", asyncio.run)
    result = tool.run_boss_browser(
        {"operation": "status", "payload": {"_trusted_owner_key": "owner"}}, trace_id="trace"
    )
    assert result.error.code == code
    assert result.error.suggested_action
    assert result.trace_id == "trace"


def test_empty_owner_is_rejected_without_service_access(monkeypatch):
    service = Mock()
    monkeypatch.setattr(tool, "get_service", service)
    monkeypatch.setattr(tool, "_run_on_loop", asyncio.run)
    assert tool.run_boss_browser({"operation": "status", "payload": None}).status == "error"
    service.assert_not_called()


def test_rate_snapshot_and_unknown_dispatch(monkeypatch):
    service = Mock()
    service.rate_snapshot.return_value = {"cooldown_active": False}
    monkeypatch.setattr(tool, "get_service", lambda owner: service)
    assert asyncio.run(tool._dispatch("rate", {"_trusted_owner_key": "owner"}))["data"] == {"cooldown_active": False}
    assert asyncio.run(tool._dispatch("unknown", {"_trusted_owner_key": "owner"}))["code"] != 200


def test_unchanged_credentials_are_not_returned_again():
    service = Mock()
    service.credential_json.return_value = "same"
    assert tool._ok_with_refreshed_credential({"jobs": []}, service, "same")["data"] == {"jobs": []}


def test_loop_initialized_by_another_caller_is_reused_after_lock(monkeypatch):
    loop = Mock()
    loop.is_closed.return_value = False

    class PublishingLock:
        def __enter__(self):
            tool._loop = loop

        def __exit__(self, *args):
            pass

    monkeypatch.setattr(tool, "_loop", None)
    monkeypatch.setattr(tool, "_loop_lock", PublishingLock())
    assert tool._ensure_loop() is loop
