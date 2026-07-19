from __future__ import annotations

import asyncio
import ipaddress
import socket
from typing import Any, Dict
from urllib.parse import urljoin, urlsplit

import httpx

from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult

_MAX_REDIRECTS = 5
_REDIRECT_STATUSES = {301, 302, 303, 307, 308}


async def _resolve_host_addresses(hostname: str, port: int) -> set[str]:
    def resolve() -> set[str]:
        rows = socket.getaddrinfo(hostname, port, type=socket.SOCK_STREAM)
        return {str(row[4][0]) for row in rows}

    return await asyncio.to_thread(resolve)


async def validate_public_http_url(raw_url: str) -> str:
    """Validate every request hop against SSRF-sensitive address ranges."""
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
    if not addresses:
        raise ValueError(f"URL 主机没有可用地址: {hostname}")
    for address in addresses:
        try:
            ip = ipaddress.ip_address(address)
        except ValueError as exc:
            raise ValueError(f"URL 主机返回无效地址: {address}") from exc
        if not ip.is_global:
            raise ValueError("禁止访问本机、私有、链路本地或保留网络地址")
    return parsed.geturl()


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

    async def validate_input(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> ValidationResult:
        base = await super().validate_input(arguments, context)
        if not base.result:
            return base
        try:
            await validate_public_http_url(str(arguments.get("url") or ""))
        except ValueError as exc:
            return ValidationResult(result=False, message=str(exc), error_code=403)
        timeout = int(arguments.get("timeout_seconds") or self.timeout_seconds)
        if timeout < 1 or timeout > 60:
            return ValidationResult(result=False, message="timeout_seconds 必须在 1-60 之间", error_code=400)
        return ValidationResult(result=True)

    SENSITIVE_HEADERS = {"set-cookie", "authorization", "proxy-authorization", "www-authenticate", "proxy-authenticate"}

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        timeout = int(arguments.get("timeout_seconds") or self.timeout_seconds)
        current_url = str(arguments["url"])
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=False, trust_env=False) as client:
            for redirect_count in range(_MAX_REDIRECTS + 1):
                current_url = await validate_public_http_url(current_url)
                response = await client.get(current_url)
                if response.status_code not in _REDIRECT_STATUSES:
                    break
                location = response.headers.get("location")
                if not location:
                    break
                if redirect_count >= _MAX_REDIRECTS:
                    raise ValueError("URL 重定向次数超过限制")
                current_url = urljoin(str(response.url), location)
            else:
                raise ValueError("URL 重定向次数超过限制")
        headers = {key: value for key, value in response.headers.items() if key.lower() not in self.SENSITIVE_HEADERS}
        return {
            "url": str(response.url),
            "status_code": response.status_code,
            "headers": headers,
            "text": response.text,
        }
