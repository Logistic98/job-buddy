"""Internal service authentication for FastAPI services."""

from __future__ import annotations

import hmac
import ipaddress
import logging
import os

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

INTERNAL_AUTH_HEADER = "X-Internal-Service-Token"
_TOKEN_ENV_VAR = "AGENT_INTERNAL_SERVICE_TOKEN"
_ENVIRONMENT_ENV_VAR = "JOB_BUDDY_ENVIRONMENT"
_PRODUCTION_ENVIRONMENTS = {"prod", "production"}
DEFAULT_EXEMPT_PATHS = {"/health"}

_LOGGER = logging.getLogger(__name__)


def install_internal_auth(app: FastAPI, exempt_paths: set[str] | None = None) -> None:
    token = os.environ.get(_TOKEN_ENV_VAR, "").strip()
    environment = os.environ.get(_ENVIRONMENT_ENV_VAR, "development").strip().lower()
    bind_host = os.environ.get("HOST", os.environ.get("JOB_BUDDY_BIND_HOST", "127.0.0.1")).strip()
    if not token and environment in _PRODUCTION_ENVIRONMENTS:
        raise RuntimeError(f"{_TOKEN_ENV_VAR} must be configured when {_ENVIRONMENT_ENV_VAR}={environment}")
    if not token and not _is_loopback_host(bind_host):
        raise RuntimeError(f"{_TOKEN_ENV_VAR} must be configured when Sandbox binds to {bind_host}")

    exempt = exempt_paths or DEFAULT_EXEMPT_PATHS
    if not token:
        _LOGGER.warning(
            "%s is not configured; internal endpoints are unauthenticated in %s",
            _TOKEN_ENV_VAR,
            environment,
        )
        return

    @app.middleware("http")
    async def _verify_internal_token(request: Request, call_next):
        if request.url.path in exempt:
            return await call_next(request)
        if not hmac.compare_digest(request.headers.get(INTERNAL_AUTH_HEADER, ""), token):
            return JSONResponse(
                status_code=401, content={"code": 401, "message": "缺少或无效的服务间鉴权令牌", "data": {}}
            )
        return await call_next(request)


def _is_loopback_host(host: str) -> bool:
    normalized = (host or "").strip().lower().strip("[]")
    if normalized == "localhost":
        return True
    try:
        return ipaddress.ip_address(normalized).is_loopback
    except ValueError:
        return False
