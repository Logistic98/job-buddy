import json
import time

import pytest
from cryptography.hazmat.primitives.ciphers.aead import AESGCM

from app.tools.boss_browser.core.browser_identity import platform_browser_headers
from app.tools.boss_browser.core.qr_session_codec import QrSessionCodec, QrSessionTokenError


def test_production_requires_shared_secret(monkeypatch):
    monkeypatch.setenv("JOB_BUDDY_ENVIRONMENT", "production")
    with pytest.raises(RuntimeError, match="必须配置"):
        QrSessionCodec(secret="")


def test_oversized_state_cannot_be_issued():
    codec = QrSessionCodec("synthetic")
    with pytest.raises(QrSessionTokenError, match="安全上限"):
        codec.encode(
            owner_key="owner", session_id="session", state={"large": "x" * (512 * 1024)}, expires_at=time.time() + 60
        )


@pytest.mark.parametrize("token", ["", "AA", "YQ=="])
def test_empty_short_and_noncanonical_tokens_fail_closed(token):
    with pytest.raises(QrSessionTokenError, match="无效"):
        QrSessionCodec("synthetic").decode(owner_key="owner", session_id="session", token=token)


@pytest.mark.parametrize(
    ("field", "value", "message"),
    [("v", 2, "版本"), ("owner", "other", "不匹配"), ("session", "other", "不匹配"), ("state", [], "状态无效")],
)
def test_authenticated_payload_still_requires_version_binding_and_state_schema(field, value, message):
    codec = QrSessionCodec("synthetic")
    payload = {"v": 1, "owner": codec._digest("owner"), "session": "session", "exp": int(time.time()) + 60, "state": {}}
    payload[field] = value
    nonce = b"012345678901"
    cipher = AESGCM(codec._key).encrypt(nonce, json.dumps(payload).encode(), codec._aad("owner", "session"))
    with pytest.raises(QrSessionTokenError, match=message):
        codec.decode(owner_key="owner", session_id="session", token=codec._b64(nonce + cipher))


def test_windows_identity_and_empty_agent_preserve_input():
    source = {"User-Agent": "Browser (old)"}
    headers = platform_browser_headers(source, system="Windows", machine="AMD64")
    assert headers["sec-ch-ua-platform"] == '"Windows"'
    assert "Windows NT" in headers["User-Agent"]
    assert source["User-Agent"] == "Browser (old)"
    assert platform_browser_headers({}, system="Linux")["User-Agent"] == ""
