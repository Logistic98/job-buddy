
from contextlib import asynccontextmanager

from fastapi import FastAPI
from loguru import logger

from app.api.agent import router as agent_router
from app.api.health import router as health_router
from app.api.runtime import get_executor, router as runtime_router
from app.core.common.settings import settings
from app.core.tool.mcp_adapter import register_mcp_tools


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
    yield


def create_app() -> FastAPI:
    app = FastAPI(title=settings.app_name, version="0.1.0", lifespan=lifespan)
    app.include_router(health_router)
    app.include_router(agent_router)
    app.include_router(runtime_router)
    return app


app = create_app()
