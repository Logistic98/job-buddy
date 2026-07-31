"""查询已配置搜索服务并规范化有界结果摘要。"""

import asyncio
import html
import json
import re
from datetime import datetime
from typing import Any, Dict, List
from urllib.parse import parse_qs, unquote, urlparse, urlunparse

import httpx

from app.core.common.settings import settings
from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult


class WebSearchTool(BaseTool):
    """查询搜索服务，但不将返回摘要视为可信指令。"""

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
            "expand_query": {
                "type": "boolean",
                "description": "时效性问题是否生成一条有界补充查询，默认 true",
                "default": True,
            },
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
        expand_query = arguments.get("expand_query") is not False
        queries = self._expand_queries(query) if expand_query else [query]
        preferred_source_domains = self._preferred_source_domains(query)

        warnings: List[str] = []
        responses = await asyncio.gather(
            *[self._search_bocha(item, limit, timeout, freshness, search_type) for item in queries],
            return_exceptions=True,
        )
        combined: List[Dict[str, Any]] = []
        sources: List[str] = []
        for query_index, response in enumerate(responses):
            if isinstance(response, Exception):
                warnings.append(f"Bocha 查询失败：{response}")
                continue
            sources.append(str(response.get("source") or "bocha"))
            if response.get("warning"):
                warnings.append(str(response["warning"]))
            for provider_rank, row in enumerate(response.get("results") or []):
                combined.append({**row, "_query_index": query_index, "_provider_rank": provider_rank})
        if combined:
            ranked = self._rank_results(query, combined, limit)
            preferred_source_found = self._contains_preferred_source(ranked, preferred_source_domains)
            if (
                preferred_source_domains
                and not preferred_source_found
                and settings.config.web_search.fallback_to_duckduckgo
            ):
                try:
                    duck = await self._search_duckduckgo(queries[-1], limit, timeout)
                    sources.append(str(duck.get("source") or "duckduckgo_html"))
                    for provider_rank, row in enumerate(duck.get("results") or []):
                        combined.append(
                            {
                                **row,
                                "_query_index": len(queries),
                                "_provider_rank": provider_rank,
                            }
                        )
                    ranked = self._rank_results(query, combined, limit)
                    preferred_source_found = self._contains_preferred_source(ranked, preferred_source_domains)
                except Exception as exc:
                    warnings.append(f"官方来源降级查询失败：{exc}")
            if preferred_source_domains and not preferred_source_found:
                warnings.append("未检索到推断的官方域名来源；现有结果只能作为第三方线索，不能视为官方确认。")
            return {
                "query": query,
                "queries": queries,
                "source": "+".join(dict.fromkeys(sources)) if sources else "bocha",
                "results": ranked,
                "raw_count": len(combined),
                "deduplicated_count": len(ranked),
                "preferred_source_domains": preferred_source_domains,
                "preferred_source_found": preferred_source_found,
                "warnings": warnings,
            }

        if settings.config.web_search.fallback_to_duckduckgo:
            duck = await self._search_duckduckgo(query, limit, timeout)
            duck["queries"] = queries
            duck["raw_count"] = len(duck.get("results") or [])
            duck["results"] = self._rank_results(query, duck.get("results") or [], limit)
            duck["deduplicated_count"] = len(duck["results"])
            duck["preferred_source_domains"] = preferred_source_domains
            duck["preferred_source_found"] = self._contains_preferred_source(duck["results"], preferred_source_domains)
            if preferred_source_domains and not duck["preferred_source_found"]:
                warnings.append("未检索到推断的官方域名来源；现有结果只能作为第三方线索，不能视为官方确认。")
            duck["warnings"] = warnings + duck.get("warnings", [])
            return duck

        return {
            "query": query,
            "queries": queries,
            "source": "bocha",
            "results": [],
            "raw_count": 0,
            "deduplicated_count": 0,
            "preferred_source_domains": preferred_source_domains,
            "preferred_source_found": False,
            "warnings": warnings or ["Bocha 搜索没有返回结果"],
            "next_actions": ["检查 BOCHA_API_KEY 是否配置", "尝试换一个更具体的搜索关键词"],
        }

    def _expand_queries(self, query: str, current_year: int | None = None) -> List[str]:
        """为时效性问题生成至多一条互补查询，避免无界扩展。"""

        primary = re.sub(r"\s+", " ", str(query or "")).strip()
        if not primary:
            return []
        if not re.search(r"最新|近期|最近|当前|今年|发布|latest|recent|current|release", primary, re.IGNORECASE):
            return [primary]
        year = current_year or datetime.now().year
        expanded = primary
        if not re.search(r"(?<!\d)(?:19|20)\d{2}(?!\d)", expanded):
            expanded = f"{expanded} {year}"
        preferred_domains = self._preferred_source_domains(primary)
        if preferred_domains:
            if not re.search(r"\bsite:[^\s]+", expanded, re.IGNORECASE):
                expanded = f"site:{preferred_domains[0]} {expanded}"
        else:
            expanded = f"{expanded} 最新进展"
        return list(dict.fromkeys([primary, expanded]))[:2]

    def _preferred_source_domains(self, query: str) -> List[str]:
        """从显式 site 条件或主体名推断一个有界官方域名候选。"""

        explicit = [
            domain.lower().removeprefix("www.")
            for domain in re.findall(r"\bsite:([A-Za-z0-9.-]+\.[A-Za-z]{2,})", str(query or ""), re.IGNORECASE)
        ]
        if explicit:
            return list(dict.fromkeys(explicit))[:1]
        entities = self._latin_entities(query)
        if not entities:
            return []
        normalized = re.sub(r"[^a-z0-9]", "", entities[0].lower())
        return [f"{normalized}.com"] if len(normalized) >= 3 else []

    def _contains_preferred_source(self, rows: List[Dict[str, Any]], domains: List[str]) -> bool:
        if not domains:
            return False
        for row in rows or []:
            host = (urlparse(str(row.get("url") or "")).hostname or "").lower().removeprefix("www.")
            if any(host == domain or host.endswith(f".{domain}") for domain in domains):
                return True
        return False

    def _rank_results(self, query: str, rows: List[Dict[str, Any]], limit: int) -> List[Dict[str, str]]:
        """按 URL/标题去重，并稳定融合权威性、相关性、时效和提供方顺序。"""

        unique: List[tuple[int, Dict[str, Any]]] = []
        seen_urls: set[str] = set()
        seen_titles: List[str] = []
        for index, row in enumerate(rows or []):
            if not isinstance(row, dict):
                continue
            canonical_url = self._canonical_url(str(row.get("url") or ""))
            title_key = self._normalized_title(str(row.get("title") or ""))
            if canonical_url and canonical_url in seen_urls:
                continue
            if title_key and any(self._title_similarity(title_key, previous) >= 0.72 for previous in seen_titles):
                continue
            if canonical_url:
                seen_urls.add(canonical_url)
            if title_key:
                seen_titles.append(title_key)
            unique.append((index, row))

        ranked = sorted(
            unique,
            key=lambda item: self._result_score(query, item[1], item[0]),
            reverse=True,
        )
        return [
            {str(key): str(value) for key, value in row.items() if not str(key).startswith("_")}
            for _, row in ranked[: max(1, limit)]
        ]

    def _result_score(self, query: str, row: Dict[str, Any], original_index: int) -> tuple:
        host = (urlparse(str(row.get("url") or "")).hostname or "").lower()
        entities = self._latin_entities(query)
        official_score = int(any(entity.lower() in host.replace("-", "") for entity in entities))
        content = " ".join(
            [
                str(row.get("title") or ""),
                str(row.get("snippet") or ""),
                str(row.get("site_name") or ""),
            ]
        )
        relevance_score = len(self._search_terms(query) & self._search_terms(content))
        published_score = self._published_timestamp(str(row.get("published_date") or ""))
        query_index = int(row.get("_query_index") or 0)
        provider_rank = int(row.get("_provider_rank") or original_index)
        return official_score, relevance_score, published_score, -query_index, -provider_rank, -original_index

    def _latin_entities(self, query: str) -> List[str]:
        stop_words = {
            "current",
            "latest",
            "model",
            "models",
            "official",
            "recent",
            "release",
            "search",
            "web",
        }
        return [
            token
            for token in re.findall(r"[A-Za-z][A-Za-z0-9-]{2,}", str(query or ""))
            if token.lower() not in stop_words
        ]

    def _search_terms(self, value: str) -> set[str]:
        text = str(value or "").lower()
        terms = {token for token in re.findall(r"[a-z0-9]+", text) if len(token) >= 2}
        for sequence in re.findall(r"[\u4e00-\u9fff]+", text):
            terms.update(sequence[index : index + 2] for index in range(max(0, len(sequence) - 1)))
        return terms

    def _canonical_url(self, value: str) -> str:
        parsed = urlparse(value.strip())
        if not parsed.scheme or not parsed.netloc:
            return value.strip()
        host = (parsed.hostname or "").lower()
        if host.startswith("www."):
            host = host[4:]
        port = f":{parsed.port}" if parsed.port else ""
        path = re.sub(r"/+$", "", parsed.path or "/") or "/"
        return urlunparse((parsed.scheme.lower(), f"{host}{port}", path, "", "", ""))

    def _normalized_title(self, value: str) -> str:
        text = re.sub(r"[|｜_\-—–].*$", "", value.lower())
        return re.sub(r"[^a-z0-9\u4e00-\u9fff]+", "", text)

    def _title_similarity(self, left: str, right: str) -> float:
        if left == right:
            return 1.0
        left_grams = {left[index : index + 2] for index in range(max(0, len(left) - 1))}
        right_grams = {right[index : index + 2] for index in range(max(0, len(right) - 1))}
        union = left_grams | right_grams
        return len(left_grams & right_grams) / len(union) if union else 0.0

    def _published_timestamp(self, value: str) -> float:
        if not value:
            return 0.0
        try:
            return datetime.fromisoformat(value.replace("Z", "+00:00")).timestamp()
        except ValueError:
            return 0.0

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
