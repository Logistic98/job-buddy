"""服务间调用鉴权：校验来自 agent-backend / 其他 Agent 服务的共享密钥。

本地和测试环境未配置 AGENT_INTERNAL_SERVICE_TOKEN 时不做强制校验；生产环境缺少令牌时
直接阻止应用启动，避免因部署漏配而匿名暴露内部接口。
"""

from __future__ import annotations

import hmac
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
    if not token and environment in _PRODUCTION_ENVIRONMENTS:
        raise RuntimeError(f"{_TOKEN_ENV_VAR} must be configured when {_ENVIRONMENT_ENV_VAR}={environment}")

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
                status_code=401,
                content={"code": 401, "message": "缺少或无效的服务间鉴权令牌", "data": {}},
            )
        return await call_next(request)
