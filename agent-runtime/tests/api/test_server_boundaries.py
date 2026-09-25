from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest
from starlette.requests import Request

from app import server


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", [False, True])
async def test_lifespan_releases_executor_even_when_mcp_registration_fails(monkeypatch, failure):
    executor = SimpleNamespace(registry=Mock(), aclose=AsyncMock())
    register = AsyncMock(side_effect=RuntimeError("MCP offline")) if failure else AsyncMock(return_value=["mcp_tool"])
    monkeypatch.setattr(server, "get_executor", lambda: executor)
    monkeypatch.setattr(server, "register_mcp_tools", register)
    async with server.lifespan(server.app):
        register.assert_awaited_once_with(executor.registry, server.settings.config.mcp)
        executor.aclose.assert_not_awaited()
    executor.aclose.assert_awaited_once()


@pytest.mark.asyncio
async def test_request_logging_preserves_handler_error():
    request = Request({"type": "http", "method": "GET", "path": "/failure", "headers": []})
    error = ValueError("handler failed")
    with pytest.raises(ValueError) as caught:
        await server.request_logging_middleware(request, AsyncMock(side_effect=error))
    assert caught.value is error
