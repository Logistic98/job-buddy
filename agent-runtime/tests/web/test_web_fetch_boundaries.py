import socket
import zlib
from types import SimpleNamespace
from unittest.mock import AsyncMock, Mock

import pytest

from app.core.tool.base import ToolExecutionContext
from app.tools_builtin import web_fetch_tool as module


def response(chunks, headers=None):
    async def stream(size):
        for chunk in chunks:
            yield chunk

    return SimpleNamespace(headers=headers or {}, content=SimpleNamespace(iter_chunked=stream))


@pytest.mark.asyncio
async def test_pinned_resolver_rejects_mismatched_family_and_closes():
    resolver = module._PinnedResolver("example.test", frozenset({"8.8.8.8"}))
    with pytest.raises(OSError, match="没有可连接"):
        await resolver.resolve("example.test", 443, socket.AF_INET6)
    await resolver.close()


@pytest.mark.asyncio
async def test_dns_resolution_uses_stream_addresses_and_normalizes_failures(monkeypatch):
    monkeypatch.setattr(module.socket, "getaddrinfo", lambda *a, **kw: [(None, None, None, None, ("8.8.8.8", 443))])
    assert await module._resolve_host_addresses("example.test", 443) == {"8.8.8.8"}
    monkeypatch.setattr(module.socket, "getaddrinfo", Mock(side_effect=OSError("DNS failure")))
    with pytest.raises(ValueError, match="解析失败"):
        await module.resolve_public_http_target("https://example.test")
    with pytest.raises(ValueError, match="用户凭据"):
        await module.resolve_public_http_target("https://user:password@example.test")


@pytest.mark.parametrize("addresses", [set(), {"invalid"}])
def test_empty_and_invalid_dns_answers_are_rejected(addresses):
    with pytest.raises(ValueError):
        module._validate_public_addresses("example.test", addresses)


@pytest.mark.asyncio
@pytest.mark.parametrize("declared", ["invalid", "999999999"])
async def test_declared_length_must_be_valid_and_bounded(declared):
    with pytest.raises(ValueError, match="Content-Length|声明长度"):
        await module._read_bounded_body(response([], {"content-length": declared}))


@pytest.mark.asyncio
async def test_compressed_body_decodes_and_invalid_encoding_fails():
    body = zlib.compress(b"plain text")
    assert await module._read_bounded_body(response([body], {"content-encoding": "deflate"})) == (
        b"plain text",
        len(body),
        10,
    )
    with pytest.raises(ValueError, match="压缩内容无效"):
        await module._read_bounded_body(response([b"invalid"], {"content-encoding": "gzip"}))
    with pytest.raises(ValueError, match="Content-Encoding"):
        module._decompressor("br")


@pytest.mark.asyncio
async def test_wire_and_expansion_limits_are_independent(monkeypatch):
    config = module.settings.config.web_fetch
    monkeypatch.setattr(config, "max_wire_bytes", 4)
    with pytest.raises(ValueError, match="传输内容超过"):
        await module._read_bounded_body(response([b"12345"]))
    monkeypatch.setattr(config, "max_wire_bytes", 100000)
    monkeypatch.setattr(config, "max_expansion_ratio", 0.5)
    with pytest.raises(ValueError, match="扩张比例"):
        await module._read_bounded_body(response([b"x" * 1024]))


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", ["invalid", "oversized"])
async def test_decoder_tail_is_checked(monkeypatch, failure):
    decoder = Mock()
    decoder.decompress.return_value = b""
    decoder.flush.return_value = b"x" * 10
    if failure == "invalid":
        decoder.flush.side_effect = zlib.error("bad tail")
    monkeypatch.setattr(module, "_decompressor", lambda _: decoder)
    monkeypatch.setattr(module.settings.config.web_fetch, "max_decoded_bytes", 5)
    with pytest.raises(ValueError, match="压缩内容无效|解码内容超过"):
        await module._read_bounded_body(response([], {"content-encoding": "gzip"}))


@pytest.mark.parametrize("peer", [None, ("invalid", 443)])
def test_connected_peer_must_be_verifiable(peer):
    transport = Mock()
    transport.get_extra_info.return_value = peer
    with pytest.raises(ValueError, match="连接地址"):
        module._connected_peer(SimpleNamespace(connection=SimpleNamespace(transport=transport)))


@pytest.mark.asyncio
@pytest.mark.parametrize("peer", ["8.8.8.8", "1.1.1.1"])
async def test_single_request_pins_peer_disables_proxy_and_truncates(monkeypatch, peer):
    from contextlib import asynccontextmanager

    resp = response([b"abcdef"])
    resp.connection = SimpleNamespace(transport=SimpleNamespace(get_extra_info=lambda _: (peer, 443)))
    resp.charset, resp.url, resp.status = "invalid-charset", "https://example.test", 200

    @asynccontextmanager
    async def opened_response(*args, **kwargs):
        assert kwargs == {"allow_redirects": False}
        yield resp

    @asynccontextmanager
    async def opened_session(**kwargs):
        assert kwargs["trust_env"] is False
        assert kwargs["auto_decompress"] is False
        yield SimpleNamespace(get=opened_response)

    monkeypatch.setattr(module.aiohttp, "TCPConnector", Mock())
    monkeypatch.setattr(module.aiohttp, "ClientSession", opened_session)
    monkeypatch.setattr(module.settings.config.web_fetch, "max_text_chars", 3)
    target = module.ResolvedHttpTarget("https://example.test", "example.test", 443, frozenset({"8.8.8.8"}))
    if peer != "8.8.8.8":
        with pytest.raises(ValueError, match="已验证 DNS"):
            await module._request_once(target, 1)
    else:
        hop = await module._request_once(target, 1)
        assert hop.text == "abc"
        assert hop.truncated is True
        assert hop.decoded_bytes == hop.wire_bytes == 6


@pytest.mark.asyncio
async def test_web_tool_validates_timeout_and_follows_bounded_redirects(monkeypatch):
    context = ToolExecutionContext(run_id="run", trace_id="trace", session_id="session")
    tool = module.WebFetchTool()
    assert (await tool.validate_input({}, context)).result is False
    monkeypatch.setattr(
        module,
        "resolve_public_http_target",
        AsyncMock(
            return_value=module.ResolvedHttpTarget("https://example.test", "example.test", 443, frozenset({"8.8.8.8"}))
        ),
    )
    assert (await tool.validate_input({"url": "https://example.test", "timeout_seconds": 61}, context)).result is False
    hop = module.FetchedHop("https://example.test", 302, {"location": "/next"}, "", False, 0, 0)
    request = AsyncMock(return_value=hop)
    monkeypatch.setattr(module, "_request_once", request)
    with pytest.raises(ValueError, match="重定向次数"):
        await tool._run({"url": "https://example.test"}, context)
    assert request.await_count == 6
    request.return_value = module.FetchedHop("https://example.test", 302, {}, "", False, 0, 0)
    assert (await tool._run({"url": "https://example.test"}, context))["status_code"] == 302


@pytest.mark.asyncio
@pytest.mark.parametrize("url", ["ftp://example.test", "https://", "relative/path"])
async def test_target_requires_absolute_http_url(url):
    with pytest.raises(ValueError):
        await module.resolve_public_http_target(url)


@pytest.mark.asyncio
@pytest.mark.parametrize("status", [200, 302])
async def test_http_response_without_location_returns_without_second_request(monkeypatch, status):
    monkeypatch.setattr(module, "resolve_public_http_target", AsyncMock(return_value=SimpleNamespace()))
    hop = module.FetchedHop(
        url="https://example.test",
        status_code=status,
        headers={},
        text="redirect",
        truncated=False,
        wire_bytes=8,
        decoded_bytes=8,
    )
    request = AsyncMock(return_value=hop)
    monkeypatch.setattr(module, "_request_once", request)
    result = await module.WebFetchTool()._run(
        {"url": "https://example.test"}, ToolExecutionContext(run_id="run", trace_id="trace", session_id="session")
    )
    assert result["status_code"] == status
    assert result["text"] == "redirect"
    request.assert_awaited_once()
