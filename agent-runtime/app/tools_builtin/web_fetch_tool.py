
from typing import Any, Dict

import httpx

from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult


class WebFetchTool(BaseTool):
    name = "web_fetch"
    aliases = ["fetch_url"]
    search_hint = "抓取 URL HTTP 内容"
    description = "抓取 HTTP/HTTPS URL 文本内容，返回状态码、响应头和文本预览。"
    input_schema = {
        "type": "object",
        "properties": {
            "url": {"type": "string", "description": "HTTP 或 HTTPS URL"},
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
        url = str(arguments.get("url") or "")
        if not (url.startswith("http://") or url.startswith("https://")):
            return ValidationResult(result=False, message="仅支持 http/https URL", error_code=400)
        return ValidationResult(result=True)

    # 凭据类响应头不进入工具结果，避免 Set-Cookie 等敏感信息进入上下文与 Trace
    SENSITIVE_HEADERS = {"set-cookie", "authorization", "proxy-authorization", "www-authenticate", "proxy-authenticate"}

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        timeout = int(arguments.get("timeout_seconds") or self.timeout_seconds)
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=True) as client:
            response = await client.get(arguments["url"])
        headers = {key: value for key, value in response.headers.items() if key.lower() not in self.SENSITIVE_HEADERS}
        return {
            "url": str(response.url),
            "status_code": response.status_code,
            "headers": headers,
            "text": response.text,
        }
