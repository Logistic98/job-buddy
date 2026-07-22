import time
from contextlib import asynccontextmanager
from uuid import uuid4

from fastapi import FastAPI, Request
from loguru import logger

from app.api.agent import router as agent_router
from app.api.health import router as health_router
from app.api.runtime import get_executor
from app.api.runtime import router as runtime_router
from app.core.common.logging import setup_logging
from app.core.common.settings import settings
from app.core.tool.mcp_adapter import register_mcp_tools
from app.internal_auth import install_internal_auth


@asynccontextmanager
async def lifespan(app: FastAPI):
    executor = get_executor()
    mcp_config = settings.config.mcp
    try:
        registered = await register_mcp_tools(executor.registry, mcp_config)
        if registered:
            logger.info(f"启动期 MCP 工具已注册：count={len(registered)}, names={registered}")
    except Exception as e:
        logger.exception(f"启动期 MCP 工具注册异常，Runtime 继续启动：error={e}")
    try:
        yield
    finally:
        await executor.aclose()


async def request_logging_middleware(request: Request, call_next):
    """统一请求日志：request_id 透传 + 方法/路径/状态码/耗时，支撑全端点的量化观测。

    /health 探活高频且无业务信息，降为 debug 避免日志噪声。
    """
    request_id = request.headers.get("X-Request-Id") or f"req_{uuid4().hex[:16]}"
    started = time.perf_counter()
    # request_id 贯穿本次请求内的所有日志（含路由处理器与嵌套模块），无需各处手工拼接。
    with logger.contextualize(request_id=request_id):
        try:
            response = await call_next(request)
        except Exception:
            elapsed_ms = int((time.perf_counter() - started) * 1000)
            logger.exception(
                f"HTTP 请求异常：method={request.method}, path={request.url.path}, elapsed_ms={elapsed_ms}"
            )
            raise
        elapsed_ms = int((time.perf_counter() - started) * 1000)
        log = logger.debug if request.url.path == "/health" else logger.info
        log(
            f"HTTP 请求完成：method={request.method}, path={request.url.path}, "
            f"status={response.status_code}, elapsed_ms={elapsed_ms}"
        )
    response.headers["X-Request-Id"] = request_id
    return response


def create_app() -> FastAPI:
    setup_logging()
    app = FastAPI(title=settings.app_name, version="1.0.0", lifespan=lifespan)
    # 先装鉴权、后装请求日志：请求日志中间件位于最外层，401 拒绝也会留下访问日志。
    install_internal_auth(app)
    app.middleware("http")(request_logging_middleware)
    app.include_router(health_router)
    app.include_router(agent_router)
    app.include_router(runtime_router)
    return app


app = create_app()
