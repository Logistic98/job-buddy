"""服务间调用鉴权：校验来自 agent-backend / 其他 Agent 服务的共享密钥。

未配置 AGENT_INTERNAL_SERVICE_TOKEN 时不做强制校验，保留本地单机联调的便利性；
生产环境必须设置该环境变量，否则所有接口（除 /health 外）都会被拒绝跨服务匿名访问。
"""

from __future__ import annotations

import os

from fastapi import FastAPI, Request
from fastapi.responses import JSONResponse

INTERNAL_AUTH_HEADER = "X-Internal-Service-Token"
_ENV_VAR = "AGENT_INTERNAL_SERVICE_TOKEN"
DEFAULT_EXEMPT_PATHS = {"/health"}


def install_internal_auth(app: FastAPI, exempt_paths: set[str] | None = None) -> None:
    token = os.environ.get(_ENV_VAR, "").strip()
    exempt = exempt_paths or DEFAULT_EXEMPT_PATHS
    if not token:
        return

    @app.middleware("http")
    async def _verify_internal_token(request: Request, call_next):
        if request.url.path in exempt:
            return await call_next(request)
        if request.headers.get(INTERNAL_AUTH_HEADER) != token:
            return JSONResponse(
                status_code=401,
                content={"code": 401, "message": "缺少或无效的服务间鉴权令牌", "data": {}},
            )
        return await call_next(request)
