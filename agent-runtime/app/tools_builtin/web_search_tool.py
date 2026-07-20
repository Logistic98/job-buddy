import html
import json
import re
from typing import Any, Dict, List
from urllib.parse import parse_qs, unquote, urlparse

import httpx

from app.core.common.settings import settings
from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult


class WebSearchTool(BaseTool):
    name = "web_search"
    aliases = ["search_web", "bocha_web_search", "bocha_ai_search"]
    search_hint = "联网 搜索 Web 资料 查询 当前 信息 博查 Bocha 面试 题库 趋势 新闻"
    description = "根据关键词执行只读 Web 搜索，优先使用 Bocha Search API，返回标题、链接、摘要、发布时间和站点。用于需要补充公开资料、当前信息、面试题、行业资料但用户没有提供具体 URL 的场景。"
    input_schema = {
        "type": "object",
        "properties": {
            "query": {"type": "string", "description": "搜索关键词"},
            "max_results": {"type": "integer", "description": "最多返回结果数，默认 5，最大 10"},
            "timeout_seconds": {"type": "integer", "description": "请求超时秒数"},
            "freshness": {"type": "string", "description": "Bocha freshness 参数，默认 noLimit"},
            "search_type": {"type": "string", "description": "bocha_web 或 bocha_ai，默认 bocha_web"},
        },
        "required": ["query"],
    }
    tags = ["web", "search", "read", "bocha"]
    read_only = True
    timeout_seconds = 12
    max_result_size_chars = 12000

    async def validate_input(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> ValidationResult:
        base = await super().validate_input(arguments, context)
        if not base.result:
            return base
        query = str(arguments.get("query") or "").strip()
        if len(query) < 2:
            return ValidationResult(result=False, message="query 不能为空", error_code=400)
        return ValidationResult(result=True)

    async def _run(self, arguments: Dict[str, Any], context: ToolExecutionContext) -> Any:
        query = str(arguments.get("query") or "").strip()
        limit = max(1, min(10, int(arguments.get("max_results") or 5)))
        timeout = int(arguments.get("timeout_seconds") or self.timeout_seconds)
        search_type = str(arguments.get("search_type") or "bocha_web").strip().lower()
        freshness = str(arguments.get("freshness") or settings.config.web_search.freshness or "noLimit")

        warnings: List[str] = []
        bocha_result = await self._search_bocha(query, limit, timeout, freshness, search_type)
        if bocha_result.get("results"):
            return bocha_result
        if bocha_result.get("warning"):
            warnings.append(str(bocha_result["warning"]))

        if settings.config.web_search.fallback_to_duckduckgo:
            duck = await self._search_duckduckgo(query, limit, timeout)
            duck["warnings"] = warnings + duck.get("warnings", [])
            return duck

        return {
            "query": query,
            "source": "bocha",
            "results": [],
            "warnings": warnings or ["Bocha 搜索没有返回结果"],
            "next_actions": ["检查 BOCHA_API_KEY 是否配置", "尝试换一个更具体的搜索关键词"],
        }

    async def _search_bocha(
        self, query: str, limit: int, timeout: int, freshness: str, search_type: str
    ) -> Dict[str, Any]:
        api_key = str(settings.config.web_search.bocha_api_key or "").strip()
        if not api_key:
            return {
                "query": query,
                "source": "bocha",
                "results": [],
                "warning": "BOCHA_API_KEY 未配置，跳过 Bocha 搜索",
            }

        use_ai = search_type in {"bocha_ai", "ai", "ai_search"}
        endpoint = (
            settings.config.web_search.bocha_ai_endpoint if use_ai else settings.config.web_search.bocha_web_endpoint
        )
        payload = {"query": query, "freshness": freshness, "count": limit}
        if use_ai:
            payload.update({"answer": False, "stream": False})
        else:
            payload.update({"summary": True})
        headers = {"Authorization": f"Bearer {api_key}", "Content-Type": "application/json"}

        try:
            async with httpx.AsyncClient(timeout=timeout) as client:
                response = await client.post(endpoint, headers=headers, json=payload)
                response.raise_for_status()
            data = response.json()
            results = self._parse_bocha_ai(data, limit) if use_ai else self._parse_bocha_web(data, limit)
            return {
                "query": query,
                "source": "bocha_ai" if use_ai else "bocha_web",
                "results": results,
                "raw_count": len(results),
            }
        except httpx.HTTPStatusError as e:
            return {
                "query": query,
                "source": "bocha",
                "results": [],
                "warning": f"Bocha HTTP 错误：{e.response.status_code}",
            }
        except httpx.RequestError as e:
            return {"query": query, "source": "bocha", "results": [], "warning": f"Bocha 请求错误：{str(e)}"}
        except Exception as e:
            return {"query": query, "source": "bocha", "results": [], "warning": f"Bocha 未知错误：{str(e)}"}

    def _parse_bocha_web(self, data: Dict[str, Any], limit: int) -> List[Dict[str, str]]:
        pages = (((data or {}).get("data") or {}).get("webPages") or {}).get("value") or []
        return self._normalize_bocha_items(pages, limit)

    def _parse_bocha_ai(self, data: Dict[str, Any], limit: int) -> List[Dict[str, str]]:
        results: List[Dict[str, str]] = []
        for message in (data or {}).get("messages") or []:
            content_type = message.get("content_type")
            content = message.get("content")
            if content_type == "webpage":
                try:
                    parsed = json.loads(content or "{}")
                except Exception:
                    parsed = {}
                results.extend(self._normalize_bocha_items(parsed.get("value") or [], limit - len(results)))
            elif content_type != "image" and content not in (None, "", "{}"):
                results.append(
                    {
                        "title": "Bocha AI Search",
                        "url": "",
                        "snippet": str(content),
                        "published_date": "",
                        "site_name": "Bocha",
                    }
                )
            if len(results) >= limit:
                break
        return results[:limit]

    def _normalize_bocha_items(self, items: List[Dict[str, Any]], limit: int) -> List[Dict[str, str]]:
        rows: List[Dict[str, str]] = []
        for item in items or []:
            title = str(item.get("name") or item.get("title") or "").strip()
            url = str(item.get("url") or "").strip()
            snippet = str(item.get("summary") or item.get("snippet") or item.get("description") or "").strip()
            if not title and not snippet:
                continue
            rows.append(
                {
                    "title": title,
                    "url": url,
                    "snippet": snippet,
                    "published_date": str(item.get("datePublished") or item.get("date_published") or ""),
                    "site_name": str(item.get("siteName") or item.get("site_name") or ""),
                }
            )
            if len(rows) >= limit:
                break
        return rows

    async def _search_duckduckgo(self, query: str, limit: int, timeout: int) -> Dict[str, Any]:
        url = "https://duckduckgo.com/html/"
        headers = {"User-Agent": "Mozilla/5.0 job-buddy-runtime/1.0"}
        async with httpx.AsyncClient(timeout=timeout, follow_redirects=True, headers=headers) as client:
            response = await client.get(url, params={"q": query})
            response.raise_for_status()
        return {
            "query": query,
            "source": "duckduckgo_html",
            "results": self._parse_results(response.text, limit),
        }

    def _parse_results(self, text: str, limit: int) -> List[Dict[str, str]]:
        rows: List[Dict[str, str]] = []
        blocks = re.findall(
            r'<a[^>]+class="result__a"[^>]+href="([^"]+)"[^>]*>(.*?)</a>.*?(?:<a[^>]+class="result__snippet"[^>]*>(.*?)</a>|<div[^>]+class="result__snippet"[^>]*>(.*?)</div>)',
            text,
            re.S,
        )
        for raw_url, raw_title, raw_snippet_a, raw_snippet_div in blocks:
            title = self._clean_html(raw_title)
            snippet = self._clean_html(raw_snippet_a or raw_snippet_div)
            link = self._normalize_duckduckgo_url(html.unescape(raw_url))
            if title and link:
                rows.append({"title": title, "url": link, "snippet": snippet})
            if len(rows) >= limit:
                break
        return rows

    def _clean_html(self, value: str) -> str:
        text = re.sub(r"<[^>]+>", " ", value or "")
        text = html.unescape(text)
        return re.sub(r"\s+", " ", text).strip()

    def _normalize_duckduckgo_url(self, value: str) -> str:
        parsed = urlparse(value)
        if parsed.path.startswith("/l/"):
            uddg = parse_qs(parsed.query).get("uddg")
            if uddg:
                return unquote(uddg[0])
        return value
