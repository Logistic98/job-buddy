import time

import pytest
from fastapi.testclient import TestClient

from app.server import app
from app.tools.boss_browser.core.qr_session_codec import QrSessionCodec, QrSessionTokenError
from app.tools.boss_browser.core.service import get_service


def test_owner_scoped_services_do_not_share_mutable_state():
    get_service.cache_clear()

    first = get_service("tenant-a\u0000user-a")
    second = get_service("tenant-b\u0000user-b")

    assert first is not second
    assert first.limiter is not second.limiter


def test_qr_state_can_move_between_workers_with_same_secret():
    issuer = QrSessionCodec("shared-service-secret")
    verifier = QrSessionCodec("shared-service-secret")
    token = issuer.encode(
        owner_key="tenant-a\u0000user-a",
        session_id="qr-a",
        state={"qr_id": "one", "expires_at": time.time() + 60},
        expires_at=time.time() + 60,
    )

    assert (
        verifier.decode(
            owner_key="tenant-a\u0000user-a",
            session_id="qr-a",
            token=token,
        )["qr_id"]
        == "one"
    )


@pytest.mark.parametrize(
    ("owner_key", "session_id"),
    [
        ("tenant-a\u0000user-b", "qr-a"),
        ("tenant-a\u0000user-a", "qr-b"),
    ],
)
def test_qr_state_rejects_cross_owner_or_cross_session_replay(owner_key, session_id):
    codec = QrSessionCodec("shared-service-secret")
    token = codec.encode(
        owner_key="tenant-a\u0000user-a",
        session_id="qr-a",
        state={"qr_id": "one"},
        expires_at=time.time() + 60,
    )

    with pytest.raises(QrSessionTokenError):
        codec.decode(owner_key=owner_key, session_id=session_id, token=token)


def test_qr_state_rejects_tampering_and_expiry():
    codec = QrSessionCodec("shared-service-secret")
    token = codec.encode(
        owner_key="tenant-a\u0000user-a",
        session_id="qr-a",
        state={"qr_id": "one"},
        expires_at=time.time() + 60,
    )
    expired = codec.encode(
        owner_key="tenant-a\u0000user-a",
        session_id="qr-a",
        state={"qr_id": "one"},
        expires_at=time.time() - 1,
    )

    with pytest.raises(QrSessionTokenError):
        codec.decode(
            owner_key="tenant-a\u0000user-a",
            session_id="qr-a",
            token=token[:-1] + ("A" if token[-1] != "A" else "B"),
        )
    with pytest.raises(QrSessionTokenError):
        codec.decode(
            owner_key="tenant-a\u0000user-a",
            session_id="qr-a",
            token=expired,
        )


def test_server_overwrites_untrusted_owner_with_authenticated_headers(monkeypatch):
    observed = {}

    def capture(arguments, trace_id=None):
        observed.update(arguments["payload"])
        from app.models import ToolResult

        return ToolResult(status="success", summary="ok", data={}, trace_id=trace_id)

    monkeypatch.setitem(
        __import__("app.server", fromlist=["TOOL_EXECUTORS"]).TOOL_EXECUTORS,
        "boss_browser",
        capture,
    )
    response = TestClient(app).post(
        "/v1/tools/boss_browser/execute",
        headers={"X-Tenant-Id": "tenant-a", "X-Operator-Id": "user-a"},
        json={
            "arguments": {
                "operation": "rate",
                "payload": {"_trusted_owner_key": "tenant-b\u0000user-b"},
            }
        },
    )

    assert response.json()["code"] == 200
    assert observed["_trusted_owner_key"] == "tenant-a\u0000user-a"


def test_server_rejects_boss_call_without_trusted_owner_headers():
    response = TestClient(app).post(
        "/v1/tools/boss_browser/execute",
        json={"arguments": {"operation": "rate", "payload": {}}},
    )

    body = response.json()
    assert body["code"] == 500
    assert body["data"]["error"]["code"] == "tool_execution_error"
