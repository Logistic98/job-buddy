from __future__ import annotations

import asyncio
import ipaddress
import socket
import zlib
from dataclasses import dataclass
from typing import Any, Dict
from urllib.parse import urljoin, urlsplit

import aiohttp
from aiohttp.abc import AbstractResolver

from app.core.common.settings import settings
from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult

_MAX_REDIRECTS = 5
_REDIRECT_STATUSES = {301, 302, 303, 307, 308}
_SUPPORTED_ENCODINGS = {"", "identity", "gzip", "deflate"}


@dataclass(frozen=True)
class ResolvedHttpTarget:
    url: str
    hostname: str
    port: int
    addresses: frozenset[str]


@dataclass(frozen=True)
class FetchedHop:
    url: str
    status_code: int
    headers: Dict[str, str]
    text: str
    truncated: bool
    wire_bytes: int
    decoded_bytes: int


class _PinnedResolver(AbstractResolver):
    def __init__(self, hostname: str, addresses: frozenset[str]) -> None:
        self._hostname = hostname
        self._addresses = addresses

    async def resolve(self, host: str, port: int = 0, family: int = socket.AF_UNSPEC) -> list[dict[str, Any]]:
        if host.rstrip(".").lower() != self._hostname:
            raise OSError("连接主机与已验证 DNS 主机不一致")
        rows: list[dict[str, Any]] = []
        for address in sorted(self._addresses):
            ip = ipaddress.ip_address(address)
            address_family = socket.AF_INET6 if ip.version == 6 else socket.AF_INET
            if family not in {socket.AF_UNSPEC, address_family}:
                continue
            rows.append(
                {
                    "hostname": host,
                    "host": address,
                    "port": port,
                    "family": address_family,
                    "proto": socket.IPPROTO_TCP,
                    "flags": socket.AI_NUMERICHOST,
                }
            )
        if not rows:
            raise OSError("已验证地址集合中没有可连接地址")
        return rows

    async def close(self) -> None:
        return None


async def _resolve_host_addresses(hostname: str, port: int) -> set[str]:
    def resolve() -> set[str]:
        rows = socket.getaddrinfo(hostname, port, type=socket.SOCK_STREAM)
        return {str(row[4][0]) for row in rows}

    return await asyncio.to_thread(resolve)


def _validate_public_addresses(hostname: str, addresses: set[str]) -> frozenset[str]:
    if not addresses:
        raise ValueError(f"URL 主机没有可用地址: {hostname}")
    normalized: set[str] = set()
    for address in addresses:
        try:
            ip = ipaddress.ip_address(address)
        except ValueError as exc:
            raise ValueError(f"URL 主机返回无效地址: {address}") from exc
        if not ip.is_global:
            raise ValueError("禁止访问本机、私有、链路本地或保留网络地址")
        normalized.add(str(ip))
    return frozenset(normalized)


async def resolve_public_http_target(raw_url: str) -> ResolvedHttpTarget:
    parsed = urlsplit(str(raw_url or "").strip())
    if parsed.scheme.lower() not in {"http", "https"} or not parsed.hostname:
        raise ValueError("仅支持包含有效主机名的 http/https URL")
    if parsed.username is not None or parsed.password is not None:
        raise ValueError("URL 不允许包含用户凭据")
    hostname = parsed.hostname.rstrip(".").lower()
    if hostname == "localhost" or hostname.endswith(".localhost"):
        raise ValueError("禁止访问本机或私有网络地址")
    port = parsed.port or (443 if parsed.scheme.lower() == "https" else 80)
    try:
        addresses = {str(ipaddress.ip_address(hostname))}
    except ValueError:
        try:
            addresses = await _resolve_host_addresses(hostname, port)
        except OSError as exc:
            raise ValueError(f"URL 主机解析失败: {hostname}") from exc
    return ResolvedHttpTarget(
        url=parsed.geturl(),
        hostname=hostname,
        port=port,
        addresses=_validate_public_addresses(hostname, addresses),
    )


async def validate_public_http_url(raw_url: str) -> str:
    return (await resolve_public_http_target(raw_url)).url


def _decompressor(content_encoding: str) -> zlib.decompressobj | None:
    if content_encoding in {"", "identity"}:
        return None
    if content_encoding == "gzip":
        return zlib.decompressobj(16 + zlib.MAX_WBITS)
    if content_encoding == "deflate":
        return zlib.decompressobj()
    raise ValueError(f"不支持的响应 Content-Encoding: {content_encoding}")


async def _read_bounded_body(response: aiohttp.ClientResponse) -> tuple[bytes, int, int]:
    config = settings.config.web_fetch
    declared = response.headers.get("content-length")
    if declared:
        try:
            if int(declared) > config.max_wire_bytes:
                raise ValueError(f"Web 响应声明长度超过 {config.max_wire_bytes} 字节")
        except ValueError as exc:
            if "超过" in str(exc):
                raise
            raise ValueError("Web 响应 Content-Length 无效") from exc

    encoding = response.headers.get("content-encoding", "").strip().lower()
    if encoding not in _SUPPORTED_ENCODINGS:
        raise ValueError(f"不支持的响应 Content-Encoding: {encoding}")
    decoder = _decompressor(encoding)
    output = bytearray()
    wire_bytes = 0
    decoded_bytes = 0
    async for chunk in response.content.iter_chunked(config.chunk_bytes):
        wire_bytes += len(chunk)
        if wire_bytes > config.max_wire_bytes:
            raise ValueError(f"Web 响应传输内容超过 {config.max_wire_bytes} 字节")
        try:
            decoded = decoder.decompress(chunk) if decoder is not None else chunk
        except zlib.error as exc:
            raise ValueError("Web 响应压缩内容无效") from exc
        decoded_bytes += len(decoded)
        if decoded_bytes > config.max_decoded_bytes:
            raise ValueError(f"Web 响应解码内容超过 {config.max_decoded_bytes} 字节")
        if wire_bytes >= 1024 and decoded_bytes > wire_bytes * config.max_expansion_ratio:
            raise ValueError(f"Web 响应压缩扩张比例超过 {config.max_expansion_ratio}")
        output.extend(decoded)

    if decoder is not None:
        try:
            tail = decoder.flush()
        except zlib.error as exc:
            raise ValueError("Web 响应压缩内容无效") from exc
        decoded_bytes += len(tail)
        if decoded_bytes > config.max_decoded_bytes:
            raise ValueError(f"Web 响应解码内容超过 {config.max_decoded_bytes} 字节")
        output.extend(tail)
    return bytes(output), wire_bytes, decoded_bytes


def _connected_peer(response: aiohttp.ClientResponse) -> str:
    connection = response.connection
    transport = connection.transport if connection is not None else None
    peer = transport.get_extra_info("peername") if transport is not None else None
    if not peer:
        raise ValueError("无法验证 Web 响应的实际连接地址")
    try:
        return str(ipaddress.ip_address(str(peer[0])))
    except ValueError as exc:
        raise ValueError("Web 响应的实际连接地址无效") from exc


async def _request_once(target: ResolvedHttpTarget, timeout_seconds: int) -> FetchedHop:
    resolver = _PinnedResolver(target.hostname, target.addresses)
    connector = aiohttp.TCPConnector(
        resolver=resolver,
        use_dns_cache=False,
        limit=1,
        ttl_dns_cache=0,
    )
    timeout = aiohttp.ClientTimeout(total=timeout_seconds)
    async with aiohttp.ClientSession(
        connector=connector,
        timeout=timeout,
        auto_decompress=False,
        trust_env=False,
    ) as client:
        async with client.get(target.url, allow_redirects=False) as response:
            peer = _connected_peer(response)
            if peer not in target.addresses:
                raise ValueError("Web 实际连接地址不在已验证 DNS 地址集合中")
            body, wire_bytes, decoded_bytes = await _read_bounded_body(response)
            charset = response.charset or "utf-8"
            try:
                text = body.decode(charset, errors="replace")
            except LookupError:
                text = body.decode("utf-8", errors="replace")
            max_chars = settings.config.web_fetch.max_text_chars
            truncated = len(text) > max_chars
            if truncated:
                text = text[:max_chars]
            return FetchedHop(
                url=str(response.url),
                status_code=response.status,
                headers={key: value for key, value in response.headers.items()},
                text=text,
                truncated=truncated,
                wire_bytes=wire_bytes,
                decoded_bytes=decoded_bytes,
            )


class WebFetchTool(BaseTool):
    name = "web_fetch"
    aliases = ["fetch_url"]
    search_hint = "抓取 URL HTTP 内容"
    description = "抓取公网 HTTP/HTTPS URL 文本内容，返回状态码、响应头和文本预览。"
    input_schema = {
        "type": "object",
        "properties": {
            "url": {"type": "string", "description": "公网 HTTP 或 HTTPS URL"},
            "timeout_seconds": {"type": "integer", "description": "请求超时秒数"},
        },
        "required": ["url"],
    }
    tags = ["web", "http", "read"]
    read_only = True
    timeout_seconds = 20
    max_result_size_chars = 24000
    SENSITIVE_HEADERS = {
        "set-cookie",
        "authorization",
        "proxy-authorization",
        "www-authenticate",
        "proxy-authenticate",
    }

    async def validate_input(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> ValidationResult:
        base = await super().validate_input(arguments, context)
        if not base.result:
            return base
        try:
            await resolve_public_http_target(str(arguments.get("url") or ""))
        except ValueError as exc:
            return ValidationResult(result=False, message=str(exc), error_code=403)
        timeout = int(arguments.get("timeout_seconds") or self.timeout_seconds)
        if timeout < 1 or timeout > 60:
            return ValidationResult(result=False, message="timeout_seconds 必须在 1-60 之间", error_code=400)
        return ValidationResult(result=True)

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        timeout = int(arguments.get("timeout_seconds") or self.timeout_seconds)
        current_url = str(arguments["url"])
        response: FetchedHop | None = None
        for redirect_count in range(_MAX_REDIRECTS + 1):
            target = await resolve_public_http_target(current_url)
            response = await _request_once(target, timeout)
            if response.status_code not in _REDIRECT_STATUSES:
                break
            location = response.headers.get("location")
            if not location:
                break
            if redirect_count >= _MAX_REDIRECTS:
                raise ValueError("URL 重定向次数超过限制")
            current_url = urljoin(response.url, location)
        if response is None:
            raise ValueError("Web 请求未产生响应")
        headers = {key: value for key, value in response.headers.items() if key.lower() not in self.SENSITIVE_HEADERS}
        return {
            "url": response.url,
            "status_code": response.status_code,
            "headers": headers,
            "text": response.text,
            "truncated": response.truncated,
            "wire_bytes": response.wire_bytes,
            "decoded_bytes": response.decoded_bytes,
        }
