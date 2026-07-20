import pytest

import app.tools_builtin.web_fetch_tool as module
from app.core.common.constants import PermissionMode
from app.core.tool.runtime import ToolRuntime
from app.models.schemas import ToolCall


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "url",
    [
        "http://127.0.0.1/admin",
        "http://[::1]/admin",
        "http://169.254.169.254/latest/meta-data",
        "http://10.0.0.5/internal",
        "http://localhost:8080/health",
        "http://user:password@example.com/",
    ],
)
async def test_web_fetch_rejects_private_and_credential_urls(fresh_registry, tool_context, url):
    runtime = ToolRuntime(fresh_registry)
    result = await runtime.execute(
        ToolCall(id="wf_private", name="web_fetch", arguments={"url": url}),
        PermissionMode.DEFAULT,
        tool_context,
    )
    assert not result.success


@pytest.mark.asyncio
async def test_web_fetch_rejects_dns_name_resolving_private(monkeypatch):
    async def private_dns(_hostname, _port):
        return {"192.168.10.5"}

    monkeypatch.setattr(module, "_resolve_host_addresses", private_dns)
    with pytest.raises(ValueError, match="私有"):
        await module.validate_public_http_url("https://metadata.example.test/value")


class _RedirectResponse:
    status_code = 302
    headers = {"location": "http://127.0.0.1/internal"}
    url = "https://public.example/start"
    text = ""


class _RedirectClient:
    calls = 0

    def __init__(self, *args, **kwargs):
        pass

    async def __aenter__(self):
        return self

    async def __aexit__(self, *args):
        return False

    async def get(self, _url):
        self.__class__.calls += 1
        return _RedirectResponse()


@pytest.mark.asyncio
async def test_web_fetch_revalidates_redirect_target(fresh_registry, tool_context, monkeypatch):
    async def public_dns(_hostname, _port):
        return {"93.184.216.34"}

    _RedirectClient.calls = 0
    monkeypatch.setattr(module, "_resolve_host_addresses", public_dns)
    monkeypatch.setattr(module.httpx, "AsyncClient", _RedirectClient)
    runtime = ToolRuntime(fresh_registry)
    result = await runtime.execute(
        ToolCall(id="wf_redirect", name="web_fetch", arguments={"url": "https://public.example/start"}),
        PermissionMode.DEFAULT,
        tool_context,
    )
    assert not result.success
    assert _RedirectClient.calls == 1
    assert "私有" in (result.error or "") or "本机" in (result.error or "")
