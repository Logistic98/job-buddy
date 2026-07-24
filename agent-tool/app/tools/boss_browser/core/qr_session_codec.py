"""Authenticated, owner-bound QR session state for cross-worker polling."""

from __future__ import annotations

import base64
import hashlib
import json
import os
import secrets
import time
from typing import Any

from cryptography.hazmat.primitives.ciphers.aead import AESGCM
from loguru import logger

_MAX_SESSION_STATE_BYTES = 512 * 1024
_MAX_TOKEN_BYTES = _MAX_SESSION_STATE_BYTES + 4096


class QrSessionTokenError(ValueError):
    """The QR state token is missing, expired, tampered with, or bound to another owner."""


class QrSessionCodec:
    def __init__(self, secret: str | None = None) -> None:
        configured = (secret if secret is not None else os.getenv("AGENT_INTERNAL_SERVICE_TOKEN", "")).strip()
        environment = os.getenv("JOB_BUDDY_ENVIRONMENT", "development").strip().lower()
        if not configured:
            if environment in {"prod", "production"}:
                raise RuntimeError("AGENT_INTERNAL_SERVICE_TOKEN 必须配置，才能保护 Boss 二维码会话")
            configured = secrets.token_urlsafe(32)
            logger.warning("Boss 二维码会话使用进程临时密钥；开发环境跨 worker 轮询不可用")
        self._key = hashlib.sha256(("job-buddy:boss-qr:" + configured).encode("utf-8")).digest()

    def encode(
        self,
        *,
        owner_key: str,
        session_id: str,
        state: dict[str, Any],
        expires_at: float,
    ) -> str:
        now = int(time.time())
        payload = {
            "v": 1,
            "owner": self._digest(owner_key),
            "session": session_id,
            "iat": now,
            "exp": int(expires_at),
            "state": state,
        }
        plaintext = json.dumps(payload, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(plaintext) > _MAX_SESSION_STATE_BYTES:
            raise QrSessionTokenError("Boss 二维码会话状态超过安全上限")
        nonce = os.urandom(12)
        ciphertext = AESGCM(self._key).encrypt(nonce, plaintext, self._aad(owner_key, session_id))
        return self._b64(nonce + ciphertext)

    def decode(self, *, owner_key: str, session_id: str, token: str) -> dict[str, Any]:
        try:
            raw = self._unb64(token)
            if len(raw) < 29 or len(raw) > _MAX_TOKEN_BYTES:
                raise ValueError("token too short")
            plaintext = AESGCM(self._key).decrypt(
                raw[:12],
                raw[12:],
                self._aad(owner_key, session_id),
            )
            payload = json.loads(plaintext)
        except Exception as exc:  # noqa: BLE001
            raise QrSessionTokenError("Boss 二维码会话令牌无效或已被篡改") from exc
        if payload.get("v") != 1:
            raise QrSessionTokenError("Boss 二维码会话令牌版本不受支持")
        if payload.get("owner") != self._digest(owner_key) or payload.get("session") != session_id:
            raise QrSessionTokenError("Boss 二维码会话与当前账号或会话不匹配")
        if int(payload.get("exp") or 0) <= int(time.time()):
            raise QrSessionTokenError("Boss 二维码会话已过期")
        state = payload.get("state")
        if not isinstance(state, dict):
            raise QrSessionTokenError("Boss 二维码会话状态无效")
        return state

    def _aad(self, owner_key: str, session_id: str) -> bytes:
        return f"boss-qr-v1:{self._digest(owner_key)}:{session_id}".encode("utf-8")

    @staticmethod
    def _digest(value: str) -> str:
        return hashlib.sha256(value.encode("utf-8")).hexdigest()

    @staticmethod
    def _b64(value: bytes) -> str:
        return base64.urlsafe_b64encode(value).rstrip(b"=").decode("ascii")

    @staticmethod
    def _unb64(value: str) -> bytes:
        text = str(value or "").strip()
        if not text:
            raise ValueError("empty token")
        raw = base64.b64decode(
            text + "=" * (-len(text) % 4),
            altchars=b"-_",
            validate=True,
        )
        if QrSessionCodec._b64(raw) != text:
            raise ValueError("non-canonical token encoding")
        return raw
