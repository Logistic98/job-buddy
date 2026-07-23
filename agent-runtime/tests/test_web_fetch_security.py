import gzip

import pytest

import app.tools_builtin.web_fetch_tool as module
from app.core.common.constants import PermissionMode
from app.core.common.settings import settings
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


@pytest.mark.asyncio
async def test_web_fetch_revalidates_redirect_target(fresh_registry, tool_context, monkeypatch):
    async def public_dns(_hostname, _port):
        return {"93.184.216.34"}

    calls = []

    async def redirect(target, _timeout):
        calls.append(target)
        return module.FetchedHop(
            url="https://public.example/start",
            status_code=302,
            headers={"location": "http://127.0.0.1/internal"},
            text="",
            truncated=False,
            wire_bytes=0,
            decoded_bytes=0,
        )

    monkeypatch.setattr(module, "_resolve_host_addresses", public_dns)
    monkeypatch.setattr(module, "_request_once", redirect)
    runtime = ToolRuntime(fresh_registry)
    result = await runtime.execute(
        ToolCall(id="wf_redirect", name="web_fetch", arguments={"url": "https://public.example/start"}),
        PermissionMode.DEFAULT,
        tool_context,
    )
    assert not result.success
    assert len(calls) == 1
    assert calls[0].addresses == frozenset({"93.184.216.34"})
    assert "私有" in (result.error or "") or "本机" in (result.error or "")


@pytest.mark.asyncio
async def test_pinned_resolver_returns_only_prevalidated_addresses():
    resolver = module._PinnedResolver(
        "public.example", frozenset({"93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946"})
    )

    rows = await resolver.resolve("public.example", 443)

    assert {row["host"] for row in rows} == {
        "93.184.216.34",
        "2606:2800:220:1:248:1893:25c8:1946",
    }
    with pytest.raises(OSError, match="不一致"):
        await resolver.resolve("rebound.example", 443)


class _Chunks:
    def __init__(self, chunks):
        self._chunks = chunks

    async def iter_chunked(self, _size):
        for chunk in self._chunks:
            yield chunk


class _BodyResponse:
    def __init__(self, chunks, headers=None):
        self.content = _Chunks(chunks)
        self.headers = headers or {}


@pytest.mark.asyncio
async def test_web_fetch_rejects_wire_body_before_unbounded_buffer(monkeypatch):
    monkeypatch.setattr(settings.config.web_fetch, "max_wire_bytes", 8)
    response = _BodyResponse([b"12345", b"67890"])

    with pytest.raises(ValueError, match="传输内容超过 8 字节"):
        await module._read_bounded_body(response)


@pytest.mark.asyncio
async def test_web_fetch_rejects_decompression_bomb(monkeypatch):
    compressed = gzip.compress(b"a" * 4096)
    monkeypatch.setattr(settings.config.web_fetch, "max_wire_bytes", len(compressed) + 16)
    monkeypatch.setattr(settings.config.web_fetch, "max_decoded_bytes", 256)
    response = _BodyResponse([compressed], {"content-encoding": "gzip"})

    with pytest.raises(ValueError, match="解码内容超过 256 字节"):
        await module._read_bounded_body(response)


@pytest.mark.asyncio
async def test_web_fetch_rejects_unsupported_content_encoding():
    response = _BodyResponse([b"body"], {"content-encoding": "br"})

    with pytest.raises(ValueError, match="不支持"):
        await module._read_bounded_body(response)
