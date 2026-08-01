"""查询已配置搜索服务并规范化有界结果摘要。"""

import asyncio
import json
import re
from dataclasses import replace
from datetime import date, datetime
from typing import Any, Dict, List
from urllib.parse import urlparse, urlunparse

import httpx

from app.core.common.settings import settings
from app.core.common.temporal import requests_latest_selection
from app.core.tool.base import BaseTool, ToolExecutionContext, ValidationResult
from app.tools_builtin.official_source_resolver import (
    OfficialArticle,
    canonicalize_official_url,
    normalize_published_date,
    parse_official_article,
    parse_official_listing,
    select_latest_article,
)
from app.tools_builtin.web_fetch_tool import BlockedNetworkAddressError, WebFetchTool


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
            "selection_mode": {
                "type": "string",
                "enum": ["relevance", "latest"],
                "description": "结果选择方式；latest 表示优先召回和排序近期结果",
                "default": "relevance",
            },
            "as_of_date": {"type": "string", "description": "latest 选择的截止日期，格式 YYYY-MM-DD"},
            "time_range_start": {
                "type": "string",
                "description": "latest 选择的可选起始日期，格式 YYYY-MM-DD；用于某自然年内最新",
            },
            "source_preference": {
                "type": "string",
                "enum": ["balanced", "official_first"],
                "description": "来源偏好；latest 默认 official_first",
                "default": "balanced",
            },
            "content_scope": {
                "type": "string",
                "description": "配置驱动的内容类型范围，例如 engineering_blog",
            },
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
        selection_mode = self._selection_mode(arguments.get("selection_mode"), query)
        as_of_date = self._as_of_date(arguments.get("as_of_date"), selection_mode)
        time_range_start = self._time_range_start(arguments.get("time_range_start"), selection_mode)
        source_preference = str(arguments.get("source_preference") or "").strip().lower()
        if selection_mode == "latest":
            source_preference = "official_first"
        elif source_preference not in {"balanced", "official_first"}:
            source_preference = "balanced"
        content_scope = str(arguments.get("content_scope") or self._infer_content_scope(query)).strip().lower()
        expand_query = arguments.get("expand_query") is not False
        query_year = int(as_of_date[:4]) if as_of_date else None
        queries = self._expand_queries(query, current_year=query_year) if expand_query else [query]
        preferred_source_domains = self._preferred_source_domains(query)
        preferred_source_trusted_hosts = self._preferred_source_trusted_hosts(preferred_source_domains)
        search_scope_domains = self._search_scope_domains(query, preferred_source_domains)
        query_scopes = self._query_scopes(queries, search_scope_domains)

        warnings: List[str] = []
        must_verify_configured = bool(preferred_source_domains) and (selection_mode == "latest" or bool(content_scope))
        official_task = None
        if must_verify_configured:
            official_task = asyncio.create_task(
                self._fetch_configured_official_sources(
                    query,
                    preferred_source_domains,
                    timeout,
                    context,
                    selection_mode=selection_mode,
                    time_range_start=time_range_start,
                    as_of_date=as_of_date,
                    content_scope=content_scope,
                )
            )
        responses = await asyncio.gather(
            *[
                self._search_bocha(
                    scope["query"],
                    limit,
                    timeout,
                    freshness,
                    search_type,
                    include_domains=scope["include_domains"],
                )
                for scope in query_scopes
            ],
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
        bocha_preferred_found = self._contains_preferred_source(combined, preferred_source_domains)
        verification_outcome: Dict[str, Any] = {
            "results": [],
            "warnings": [],
        }
        should_verify_configured = must_verify_configured or (
            bool(preferred_source_domains) and not bocha_preferred_found
        )
        if should_verify_configured:
            raw_verification_outcome = (
                await official_task
                if official_task is not None
                else await self._fetch_configured_official_sources(
                    query,
                    preferred_source_domains,
                    timeout,
                    context,
                    selection_mode=selection_mode,
                    time_range_start=time_range_start,
                    as_of_date=as_of_date,
                    content_scope=content_scope,
                )
            )
            if isinstance(raw_verification_outcome, dict):
                verification_outcome = {**verification_outcome, **raw_verification_outcome}
            else:
                # 兼容测试替身与旧扩展实现；正式实现始终返回结构化结果。
                verification_outcome = {**verification_outcome, "results": raw_verification_outcome or []}
            verified_rows = verification_outcome.get("results") or []
            if selection_mode == "latest" and verification_outcome.get("latest_evidence_verified") is not True:
                verified_rows = []
            warnings.extend(str(item) for item in verification_outcome.get("warnings") or [])
            if verified_rows:
                if not bocha_preferred_found:
                    warnings.append("博查索引未返回官方页，已通过配置的官方稳定入口完成直验。")
                combined.extend(
                    {
                        **row,
                        "_query_index": len(query_scopes),
                        "_provider_rank": provider_rank,
                    }
                    for provider_rank, row in enumerate(verified_rows)
                )
        combined = self._mark_source_tiers(combined, preferred_source_domains, content_scope)
        if combined:
            ranked = self._rank_results(query, combined, limit, preferred_source_domains)
            preferred_source_found = self._contains_preferred_source(ranked, preferred_source_domains)
            official_source_count = sum(row.get("source_tier") == "official" for row in ranked)
            third_party_source_count = sum(row.get("source_tier") == "third_party" for row in ranked)
            official_verification = self._official_verification(ranked, preferred_source_found)
            result = {
                "query": query,
                "queries": queries,
                "query_scopes": query_scopes,
                "source": "+".join(dict.fromkeys(sources)) if sources else "bocha_web",
                "results": ranked,
                "raw_count": len(combined),
                "deduplicated_count": len(ranked),
                "preferred_source_domains": preferred_source_domains,
                "preferred_source_trusted_hosts": preferred_source_trusted_hosts,
                "search_scope_domains": search_scope_domains,
                "preferred_source_found": preferred_source_found,
                "official_source_count": official_source_count,
                "third_party_source_count": third_party_source_count,
                "official_verification": official_verification,
                "selection_mode": selection_mode,
                "time_range_start": time_range_start,
                "as_of_date": as_of_date,
                "source_preference": source_preference,
                "content_scope": content_scope,
                "warnings": warnings,
            }
            if verification_outcome.get("latest_evidence_verified") is True:
                result.update(
                    {
                        "latest_evidence_verified": True,
                        "selection_basis": str(verification_outcome.get("selection_basis") or ""),
                        "catalog_url": str(verification_outcome.get("catalog_url") or ""),
                        "selected_url": str(verification_outcome.get("selected_url") or ""),
                        "selected_published_date": str(verification_outcome.get("selected_published_date") or ""),
                        "candidate_count": int(verification_outcome.get("candidate_count") or 0),
                    }
                )
            return result

        if not combined and not any("没有返回结果" in warning for warning in warnings):
            warnings.append("Bocha 搜索没有返回结果")
        return {
            "query": query,
            "queries": queries,
            "query_scopes": query_scopes,
            "source": "+".join(dict.fromkeys(sources)) if sources else "bocha_web",
            "results": [],
            "raw_count": 0,
            "deduplicated_count": 0,
            "preferred_source_domains": preferred_source_domains,
            "preferred_source_trusted_hosts": preferred_source_trusted_hosts,
            "search_scope_domains": search_scope_domains,
            "preferred_source_found": False,
            "official_source_count": 0,
            "third_party_source_count": 0,
            "official_verification": "not_found" if preferred_source_domains else "not_requested",
            "selection_mode": selection_mode,
            "time_range_start": time_range_start,
            "as_of_date": as_of_date,
            "source_preference": source_preference,
            "content_scope": content_scope,
            "warnings": warnings,
            "next_actions": ["检查 BOCHA_API_KEY 是否配置", "尝试换一个更具体的搜索关键词"],
        }

    def _selection_mode(self, value: Any, query: str) -> str:
        normalized = str(value or "").strip().lower()
        if normalized == "latest" or requests_latest_selection(query):
            return "latest"
        return "relevance"

    def _as_of_date(self, value: Any, selection_mode: str) -> str:
        normalized = normalize_published_date(str(value or ""))
        if normalized:
            return normalized
        return date.today().isoformat() if selection_mode == "latest" else ""

    def _time_range_start(self, value: Any, selection_mode: str) -> str:
        normalized = normalize_published_date(str(value or ""))
        return normalized if selection_mode == "latest" and normalized else ""

    def _infer_content_scope(self, query: str) -> str:
        if re.search(
            r"工程(?:博客|博文|文章)|engineering\s+(?:blog|article|post)",
            str(query or ""),
            re.IGNORECASE,
        ):
            return "engineering_blog"
        return ""

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
            if not re.search(r"\bofficial\b", expanded, re.IGNORECASE):
                expanded = f"{expanded} official"
        else:
            expanded = f"{expanded} 最新进展"
        return list(dict.fromkeys([primary, expanded]))[:2]

    def _query_scopes(self, queries: List[str], preferred_domains: List[str]) -> List[Dict[str, Any]]:
        """把文本查询与结构化域名范围分离，避免把 site: 语法误当成强过滤。"""

        scopes: List[Dict[str, Any]] = []
        for index, raw_query in enumerate(queries or []):
            has_explicit_site = bool(re.search(r"\bsite:[^\s]+", raw_query, re.IGNORECASE))
            clean_query = re.sub(r"\bsite:[^\s]+\s*", "", raw_query, flags=re.IGNORECASE).strip()
            include_domains = preferred_domains if preferred_domains and (index > 0 or has_explicit_site) else []
            scopes.append({"query": clean_query or raw_query, "include_domains": list(include_domains)})
        return scopes

    def _preferred_source_domains(self, query: str) -> List[str]:
        """只返回有配置证据策略的官方域名，未知 site 或实体不能升级信任。"""

        query_lower = str(query or "").lower()
        explicit = {
            domain.lower().removeprefix("www.")
            for domain in re.findall(r"\bsite:([A-Za-z0-9.-]+\.[A-Za-z]{2,})", str(query or ""), re.IGNORECASE)
        }
        configured: List[str] = []
        for source in settings.config.web_search.official_sources:
            domain = source.domain.lower().removeprefix("www.")
            identity_terms = [domain.split(".")[0], *source.aliases]
            if domain in explicit or any(
                str(term).lower() in query_lower for term in identity_terms if str(term).strip()
            ):
                configured.append(domain)
        return list(dict.fromkeys(configured))[:2]

    def _preferred_source_trusted_hosts(self, preferred_domains: List[str]) -> List[str]:
        """从配置派生精确可信主机，供下游独立校验，不接受查询参数扩权。"""

        preferred = {str(item).lower().removeprefix("www.") for item in preferred_domains}
        trusted_hosts: List[str] = []
        for source in settings.config.web_search.official_sources:
            domain = source.domain.lower().removeprefix("www.")
            if domain not in preferred:
                continue
            for raw_host in source.trusted_hosts:
                host = str(raw_host or "").strip().lower().rstrip(".")
                if not host or host in trusted_hosts:
                    continue
                trusted_hosts.append(host)
                if len(trusted_hosts) >= 20:
                    return trusted_hosts
        return trusted_hosts

    def _search_scope_domains(self, query: str, preferred_domains: List[str] | None = None) -> List[str]:
        """返回 Bocha 的结构化搜索范围；该字段不承担官方信任语义。"""

        explicit = [
            domain.lower().removeprefix("www.")
            for domain in re.findall(r"\bsite:([A-Za-z0-9.-]+\.[A-Za-z]{2,})", str(query or ""), re.IGNORECASE)
        ]
        if explicit:
            return list(dict.fromkeys(explicit))[:1]
        if preferred_domains:
            return list(dict.fromkeys(preferred_domains))[:2]
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
            if self._is_official_host(host, domains):
                return True
        return False

    def _rank_results(
        self,
        query: str,
        rows: List[Dict[str, Any]],
        limit: int,
        preferred_domains: List[str] | None = None,
    ) -> List[Dict[str, Any]]:
        """先按权威性排序再去重，防止第三方近重复结果淘汰官方来源。"""

        scored = sorted(
            enumerate(row for row in (rows or []) if isinstance(row, dict)),
            key=lambda item: self._result_score(query, item[1], item[0], preferred_domains or []),
            reverse=True,
        )
        unique: List[Dict[str, Any]] = []
        seen_urls: set[str] = set()
        seen_titles: List[str] = []
        for _, row in scored:
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
            unique.append(row)
        return [
            {str(key): value for key, value in row.items() if not str(key).startswith("_")}
            for row in unique[: max(1, limit)]
        ]

    def _result_score(
        self,
        query: str,
        row: Dict[str, Any],
        original_index: int,
        preferred_domains: List[str],
    ) -> tuple:
        host = (urlparse(str(row.get("url") or "")).hostname or "").lower().removeprefix("www.")
        preferred_score = int(self._is_official_host(host, preferred_domains))
        entities = self._latin_entities(query)
        entity_host_score = int(any(entity.lower() in host.replace("-", "") for entity in entities))
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
        return (
            int(row.get("verification_method") in {"configured_official_index", "configured_direct_fetch"}),
            preferred_score,
            entity_host_score,
            relevance_score,
            published_score,
            -query_index,
            -provider_rank,
            -original_index,
        )

    def _mark_source_tiers(
        self,
        rows: List[Dict[str, Any]],
        preferred_domains: List[str],
        content_scope: str = "",
    ) -> List[Dict[str, Any]]:
        marked: List[Dict[str, Any]] = []
        for row in rows or []:
            host = (urlparse(str(row.get("url") or "")).hostname or "").lower().removeprefix("www.")
            trusted_host = bool(preferred_domains) and self._is_official_host(host, preferred_domains)
            in_scope = not content_scope or self._row_matches_content_scope(row, preferred_domains, content_scope)
            if trusted_host and in_scope:
                source_tier = "official"
            elif trusted_host:
                source_tier = "official_out_of_scope"
            else:
                source_tier = "third_party"
            marked.append({**row, "source_tier": source_tier})
        return marked

    def _is_official_host(self, host: str, preferred_domains: List[str]) -> bool:
        """按配置的可信主机白名单判定官方域名，避免社区子域被误当成官方声明。"""

        normalized_host = str(host or "").lower().removeprefix("www.")
        for preferred in preferred_domains:
            normalized_preferred = str(preferred or "").lower().removeprefix("www.")
            policies = [
                source
                for source in settings.config.web_search.official_sources
                if source.domain.lower().removeprefix("www.") == normalized_preferred
            ]
            if policies and policies[0].trusted_hosts:
                trusted_hosts = {
                    trusted.lower().removeprefix("www.") for trusted in policies[0].trusted_hosts if trusted
                }
                if normalized_host in trusted_hosts:
                    return True
            # 未配置的 site: 或“实体名+.com”只用于搜索收窄，不能自动升级成官方证据。
        return False

    def _row_matches_content_scope(
        self,
        row: Dict[str, Any],
        preferred_domains: List[str],
        content_scope: str,
    ) -> bool:
        url = str(row.get("url") or "")
        for source in settings.config.web_search.official_sources:
            domain = source.domain.lower().removeprefix("www.")
            if domain not in {item.lower().removeprefix("www.") for item in preferred_domains}:
                continue
            if source.content_scope != content_scope or not source.allowed_path_prefixes:
                continue
            if (
                canonicalize_official_url(
                    url,
                    base_url=source.public_url,
                    trusted_hosts=source.trusted_hosts,
                    allowed_path_prefixes=source.allowed_path_prefixes,
                )
                is not None
            ):
                return True
        return False

    def _official_verification(self, rows: List[Dict[str, Any]], found: bool) -> str:
        if not found:
            return "not_found"
        if any(
            row.get("source_tier") == "official" and row.get("verification_method") == "configured_official_index"
            for row in rows
        ):
            return "configured_official_index"
        if any(
            row.get("source_tier") == "official" and row.get("verification_method") == "configured_direct_fetch"
            for row in rows
        ):
            return "configured_direct_fetch"
        return "bocha_result"

    async def _fetch_configured_official_sources(
        self,
        query: str,
        preferred_domains: List[str],
        timeout: int,
        context: ToolExecutionContext,
        *,
        selection_mode: str = "relevance",
        time_range_start: str = "",
        as_of_date: str = "",
        content_scope: str = "",
    ) -> Dict[str, Any]:
        """按配置策略直验固定官方入口，不把搜索提供方日期当作 latest 证明。"""

        fetcher = WebFetchTool()
        rows: List[Dict[str, Any]] = []
        warnings: List[str] = []
        catalog_outcomes: List[Dict[str, Any]] = []
        evidence = {
            "latest_evidence_verified": False,
            "selection_basis": "",
            "catalog_url": "",
            "selected_url": "",
            "selected_published_date": "",
            "candidate_count": 0,
        }
        for source in self._matching_official_sources(query, preferred_domains, content_scope):
            domain = source.domain.lower().removeprefix("www.")
            if source.strategy == "official_catalog_published_at" and selection_mode == "latest":
                outcome = await self._fetch_official_catalog_latest(
                    source,
                    timeout,
                    context,
                    time_range_start,
                    as_of_date,
                )
                rows.extend(outcome.get("results") or [])
                warnings.extend(outcome.get("warnings") or [])
                catalog_outcomes.append(outcome)
                continue
            try:
                fetched = await fetcher._run(
                    {"url": source.fetch_url, "timeout_seconds": max(1, min(60, timeout))},
                    context,
                )
            except BlockedNetworkAddressError as safe_fetch_error:
                try:
                    fetched = await self._fetch_allowlisted_official_source_with_retries(source, timeout)
                except Exception as proxy_fetch_error:
                    warnings.append(
                        "官方稳定入口直验失败："
                        f"{source.title}（安全抓取：{self._error_summary(safe_fetch_error)}；"
                        f"代理抓取：{self._error_summary(proxy_fetch_error)}）"
                    )
                    continue
            except Exception as safe_fetch_error:
                warnings.append(f"官方稳定入口安全抓取失败：{source.title}（{self._error_summary(safe_fetch_error)}）")
                continue
            final_host = (urlparse(str(fetched.get("url") or "")).hostname or "").lower().removeprefix("www.")
            trusted_hosts = {
                str(item).lower().removeprefix("www.") for item in source.trusted_hosts if str(item).strip()
            }
            text = str(fetched.get("text") or "").strip()
            if not (200 <= int(fetched.get("status_code") or 0) < 300):
                continue
            if final_host not in trusted_hosts:
                continue
            if not text or any(marker not in text for marker in source.content_markers):
                continue
            canonical_latest_requested = selection_mode == "latest" and source.strategy == "official_canonical_snapshot"
            canonical_latest_verified = bool(
                canonical_latest_requested
                and not str(time_range_start or "").strip()
                and normalize_published_date(as_of_date) == date.today().isoformat()
            )
            rows.append(
                {
                    "title": source.title,
                    "url": source.public_url,
                    "snippet": text[: max(200, min(8000, source.max_snippet_chars))],
                    "published_date": "",
                    "published_date_source": "official_snapshot",
                    "site_name": source.site_name or domain,
                    "verification_method": "configured_direct_fetch",
                    "is_latest": canonical_latest_verified,
                }
            )
            if canonical_latest_requested:
                if not canonical_latest_verified:
                    warnings.append("当前官方快照不能证明历史时间范围内的最新项。")
                evidence = {
                    "latest_evidence_verified": canonical_latest_verified,
                    "selection_basis": "official_canonical_snapshot",
                    "catalog_url": source.public_url,
                    "selected_url": source.public_url if canonical_latest_verified else "",
                    "selected_published_date": "",
                    "candidate_count": 1,
                }
        if catalog_outcomes:
            rows, evidence, catalog_warnings = self._resolve_catalog_latest_evidence(rows, catalog_outcomes)
            warnings.extend(catalog_warnings)
        return {"results": rows, "warnings": warnings, **evidence}

    def _resolve_catalog_latest_evidence(
        self,
        rows: List[Dict[str, Any]],
        outcomes: List[Dict[str, Any]],
    ) -> tuple[List[Dict[str, Any]], Dict[str, Any], List[str]]:
        """跨同一内容范围的官方栏目比较日期，避免配置顺序决定 latest。"""

        warnings: List[str] = []
        candidate_count = sum(int(outcome.get("candidate_count") or 0) for outcome in outcomes)
        verified = [outcome for outcome in outcomes if outcome.get("latest_evidence_verified") is True]
        all_catalogs_verified = len(verified) == len(outcomes)
        dated = [(normalize_published_date(outcome.get("selected_published_date")), outcome) for outcome in verified]
        dated = [(published, outcome) for published, outcome in dated if published]
        selected_outcome: Dict[str, Any] | None = None
        if all_catalogs_verified and dated:
            latest_date = max(published for published, _ in dated)
            latest = [outcome for published, outcome in dated if published == latest_date]
            selected_urls = {str(outcome.get("selected_url") or "") for outcome in latest}
            if len(selected_urls) == 1 and "" not in selected_urls:
                selected_outcome = latest[0]
            else:
                warnings.append("多个官方技术栏目存在同日最新文章，不能声称唯一最新。")
        elif len(outcomes) > 1:
            warnings.append("至少一个官方技术栏目未完成最新性核验，不能跨栏目声称最新。")

        selected_url = str(selected_outcome.get("selected_url") or "") if selected_outcome else ""
        normalized_rows = [
            {**row, "is_latest": bool(selected_url and str(row.get("url") or "") == selected_url)} for row in rows
        ]
        fallback_outcome = outcomes[0] if outcomes else {}
        return (
            normalized_rows,
            {
                "latest_evidence_verified": selected_outcome is not None,
                "selection_basis": "official_catalog_published_at",
                "catalog_url": str((selected_outcome or fallback_outcome).get("catalog_url") or ""),
                "selected_url": selected_url,
                "selected_published_date": (
                    str(selected_outcome.get("selected_published_date") or "") if selected_outcome else ""
                ),
                "candidate_count": candidate_count,
            },
            warnings,
        )

    def _matching_official_sources(
        self,
        query: str,
        preferred_domains: List[str],
        content_scope: str,
    ) -> List[Any]:
        """返回同一内容范围内的全部受信目录，供最新性校验统一比较。"""

        query_lower = str(query or "").lower()
        preferred = {str(item).lower().removeprefix("www.") for item in preferred_domains}
        matched = []
        for source in settings.config.web_search.official_sources:
            domain = source.domain.lower().removeprefix("www.")
            if domain not in preferred:
                continue
            if str(source.content_scope or "") != str(content_scope or ""):
                continue
            topic_terms = [str(term).strip().lower() for term in source.topic_terms if str(term).strip()]
            if not topic_terms:
                identity_terms = {
                    domain.split(".")[0],
                    *(str(term).strip().lower() for term in source.aliases if str(term).strip()),
                }
                topic_terms = [
                    str(term).strip().lower()
                    for term in source.match_terms
                    if str(term).strip() and str(term).strip().lower() not in identity_terms
                ]
            if not topic_terms or not any(term in query_lower for term in topic_terms):
                continue
            matched.append(source)
        return matched

    async def _fetch_official_catalog_latest(
        self,
        source,
        timeout: int,
        context: ToolExecutionContext,
        time_range_start: str,
        as_of_date: str,
    ) -> Dict[str, Any]:
        warnings: List[str] = []
        try:
            catalog = await self._fetch_trusted_official_url(
                source,
                source.fetch_url,
                timeout,
                context,
                require_content_path=False,
            )
        except Exception as error:
            return {
                "results": [],
                "warnings": [f"官方栏目直验失败：{source.title}（{self._error_summary(error)}）"],
                "latest_evidence_verified": False,
                "selection_basis": "official_catalog_published_at",
                "catalog_url": source.public_url,
                "selected_url": "",
                "selected_published_date": "",
                "candidate_count": 0,
            }
        text = str(catalog.get("text") or "")
        if not text or any(marker not in text for marker in source.content_markers):
            return {
                "results": [],
                "warnings": [f"官方栏目内容标记不匹配：{source.title}"],
                "latest_evidence_verified": False,
                "selection_basis": "official_catalog_published_at",
                "catalog_url": source.public_url,
                "selected_url": "",
                "selected_published_date": "",
                "candidate_count": 0,
            }

        parsed_articles = parse_official_listing(
            text,
            base_url=source.public_url,
            trusted_hosts=source.trusted_hosts,
            allowed_path_prefixes=source.allowed_path_prefixes,
        )
        max_candidates = max(1, min(100, int(source.max_candidates or 1)))
        response_truncated = bool(catalog.get("truncated"))
        truncated = len(parsed_articles) > max_candidates
        if response_truncated:
            warnings.append("官方栏目响应被截断，无法证明候选集合完整。")
        articles = list(parsed_articles[:max_candidates])
        date_sources = {article.url: "official_catalog" for article in articles if article.published_date is not None}
        detailed_urls: set[str] = set()
        detail_budget = max(1, min(10, int(source.max_detail_fetches or 1)))

        async def enrich(article: OfficialArticle) -> OfficialArticle:
            nonlocal detail_budget
            if detail_budget <= 0:
                return article
            detail_budget -= 1
            try:
                fetched = await self._fetch_trusted_official_url(
                    source,
                    article.url,
                    timeout,
                    context,
                    require_content_path=True,
                )
            except Exception as error:
                warnings.append(f"官方文章直验失败：{article.url}（{self._error_summary(error)}）")
                return article
            metadata = parse_official_article(str(fetched.get("text") or ""))
            detailed_urls.add(article.url)
            published_date = metadata.published_date or article.published_date
            if metadata.published_date:
                date_sources[article.url] = "official_detail"
            return replace(
                article,
                title=metadata.title or article.title,
                summary=metadata.description or article.summary,
                published_date=published_date,
            )

        for index, article in enumerate(tuple(articles)):
            if article.needs_metadata_fetch and detail_budget > 0:
                articles[index] = await enrich(article)

        cutoff = as_of_date or date.today().isoformat()
        normalized_cutoff = normalize_published_date(cutoff)
        current_date = date.today().isoformat()
        if not normalized_cutoff or (time_range_start and time_range_start > normalized_cutoff):
            return {
                "results": [],
                "warnings": ["latest 时间范围无效，不能完成官方最新性核验。"],
                "latest_evidence_verified": False,
                "selection_basis": "official_catalog_published_at",
                "catalog_url": source.public_url,
                "selected_url": "",
                "selected_published_date": "",
                "candidate_count": len(parsed_articles),
            }
        cutoff_observable = normalized_cutoff <= current_date
        if not cutoff_observable:
            warnings.append("latest 截止日位于未来，当前无法完成官方最新性核验。")
        selected = (
            select_latest_article(
                articles,
                as_of_date=normalized_cutoff,
                not_before_date=time_range_start or None,
            )
            if articles
            else None
        )
        if selected and selected.url not in detailed_urls and detail_budget > 0:
            selected_index = next(index for index, article in enumerate(articles) if article.url == selected.url)
            articles[selected_index] = await enrich(selected)
            selected = select_latest_article(
                articles,
                as_of_date=normalized_cutoff,
                not_before_date=time_range_start or None,
            )
        if selected and selected.url not in detailed_urls and detail_budget > 0:
            selected_index = next(index for index, article in enumerate(articles) if article.url == selected.url)
            articles[selected_index] = await enrich(selected)
            selected = select_latest_article(
                articles,
                as_of_date=normalized_cutoff,
                not_before_date=time_range_start or None,
            )

        unresolved_dates = any(article.published_date is None for article in articles)
        selected_date = normalize_published_date(selected.published_date) if selected else None
        latest_tie_count = (
            sum(normalize_published_date(article.published_date) == selected_date for article in articles)
            if selected_date
            else 0
        )
        latest_verified = bool(
            selected
            and selected.url in detailed_urls
            and date_sources.get(selected.url) in {"official_catalog", "official_detail"}
            and not unresolved_dates
            and not truncated
            and not response_truncated
            and latest_tie_count == 1
            and cutoff_observable
            and selected_date <= current_date
        )
        if latest_tie_count > 1:
            warnings.append("官方栏目存在同日并列候选，缺少更细粒度发布时间，不能声称唯一最新。")
        if not latest_verified:
            warnings.append("官方栏目候选日期未能全部核验，本轮不能把任一结果表述为最新。")
        rows = []
        if selected:
            rows.append(
                {
                    "title": selected.title,
                    "url": selected.url,
                    "snippet": selected.summary,
                    "published_date": selected.published_date or "",
                    "published_date_source": date_sources.get(selected.url, ""),
                    "site_name": source.site_name or source.domain,
                    "verification_method": "configured_official_index",
                    "is_latest": latest_verified,
                }
            )
        return {
            "results": rows,
            "warnings": warnings,
            "latest_evidence_verified": latest_verified,
            "selection_basis": "official_catalog_published_at",
            "catalog_url": source.public_url,
            "selected_url": selected.url if selected and latest_verified else "",
            "selected_published_date": selected.published_date if selected and latest_verified else "",
            "candidate_count": len(parsed_articles),
        }

    async def _fetch_trusted_official_url(
        self,
        source,
        url: str,
        timeout: int,
        context: ToolExecutionContext,
        *,
        require_content_path: bool,
    ) -> Dict[str, Any]:
        if require_content_path:
            canonical = canonicalize_official_url(
                url,
                base_url=source.public_url,
                trusted_hosts=source.trusted_hosts,
                allowed_path_prefixes=source.allowed_path_prefixes,
            )
            if canonical is None:
                raise ValueError("官方文章 URL 不在配置的 HTTPS 主机与栏目白名单")
        else:
            canonical = self._canonical_configured_url(source, url)
        fetcher = WebFetchTool()
        try:
            fetched = await fetcher._run(
                {"url": canonical, "timeout_seconds": max(1, min(60, timeout))},
                context,
            )
        except BlockedNetworkAddressError:
            if canonical == source.fetch_url:
                fetched = await self._fetch_allowlisted_official_source_with_retries(source, timeout)
            else:
                fetched = await self._fetch_allowlisted_official_url_with_retries(source, canonical, timeout)
        if canonical == source.fetch_url and fetched.get("truncated"):
            try:
                expanded = await self._fetch_allowlisted_official_source_with_retries(source, timeout)
                fetched = {**expanded, "truncated": False}
            except Exception:
                # 保留截断标记，由 latest 完整性门禁失败关闭；不得把不完整目录升级为最新证明。
                pass
        if not (200 <= int(fetched.get("status_code") or 0) < 300):
            raise ValueError(f"官方页面 HTTP 状态异常：{fetched.get('status_code')}")
        final_url = str(fetched.get("url") or "")
        if require_content_path:
            validated_final = canonicalize_official_url(
                final_url,
                base_url=source.public_url,
                trusted_hosts=source.trusted_hosts,
                allowed_path_prefixes=source.allowed_path_prefixes,
            )
        else:
            try:
                validated_final = self._canonical_configured_url(source, final_url)
            except ValueError:
                validated_final = None
        if validated_final != canonical:
            raise ValueError("官方页面重定向后的 URL 与白名单目标不一致")
        return fetched

    def _canonical_configured_url(self, source, url: str) -> str:
        parsed = urlparse(str(url or ""))
        raw_host = (parsed.hostname or "").lower()
        host = raw_host.removeprefix("www.")
        trusted_hosts = {str(item).lower().removeprefix("www.") for item in source.trusted_hosts if item}
        if (
            parsed.scheme.lower() != "https"
            or not host
            or host not in trusted_hosts
            or parsed.username is not None
            or parsed.password is not None
            or parsed.port not in (None, 443)
        ):
            raise ValueError("官方直验 URL 不在配置的 HTTPS 主机白名单")
        path = re.sub(r"/+$", "", parsed.path or "/") or "/"
        return urlunparse(("https", raw_host, path, "", "", ""))

    async def _fetch_allowlisted_official_source_with_retries(self, source, timeout: int) -> Dict[str, Any]:
        """只重试固定白名单 URL 的瞬时传输错误，不放宽主机与重定向校验。"""

        attempts = max(1, min(3, int(settings.config.web_search.official_fetch_max_attempts or 1)))
        backoff = max(
            0.0,
            min(2.0, float(settings.config.web_search.official_fetch_retry_backoff_seconds or 0.0)),
        )
        last_error: httpx.TransportError | None = None
        for attempt in range(attempts):
            if attempt > 0 and backoff > 0:
                await asyncio.sleep(backoff * (2 ** (attempt - 1)))
            try:
                return await self._fetch_allowlisted_official_source(source, timeout)
            except httpx.TransportError as error:
                last_error = error
        if last_error is not None:
            raise last_error
        raise RuntimeError("官方稳定入口代理抓取未执行")

    async def _fetch_allowlisted_official_url_with_retries(
        self,
        source,
        url: str,
        timeout: int,
    ) -> Dict[str, Any]:
        attempts = max(1, min(3, int(settings.config.web_search.official_fetch_max_attempts or 1)))
        backoff = max(
            0.0,
            min(2.0, float(settings.config.web_search.official_fetch_retry_backoff_seconds or 0.0)),
        )
        last_error: httpx.TransportError | None = None
        for attempt in range(attempts):
            if attempt > 0 and backoff > 0:
                await asyncio.sleep(backoff * (2 ** (attempt - 1)))
            try:
                return await self._fetch_allowlisted_official_url(source, url, timeout)
            except httpx.TransportError as error:
                last_error = error
        if last_error is not None:
            raise last_error
        raise RuntimeError("官方文章代理抓取未执行")

    def _error_summary(self, error: Exception) -> str:
        """保留异常类型，避免 httpx 空消息导致审计信息不可读。"""

        name = type(error).__name__
        message = str(error).strip()
        return f"{name}: {message}" if message else name

    async def _fetch_allowlisted_official_source(self, source, timeout: int) -> Dict[str, Any]:
        """通过系统代理抓取配置白名单中的固定 HTTPS URL，不接受重定向或用户输入。"""

        canonical = self._canonical_configured_url(source, source.fetch_url)
        max_bytes = settings.config.web_fetch.max_decoded_bytes
        headers = {"Accept": "text/markdown,text/plain;q=0.9", "Accept-Encoding": "identity"}
        async with httpx.AsyncClient(
            timeout=max(1, min(60, timeout)),
            follow_redirects=False,
            trust_env=True,
        ) as client:
            async with client.stream("GET", canonical, headers=headers) as response:
                if response.is_redirect:
                    raise ValueError("官方直验入口不允许重定向")
                response.raise_for_status()
                body = bytearray()
                async for chunk in response.aiter_bytes():
                    body.extend(chunk)
                    if len(body) > max_bytes:
                        raise ValueError(f"官方直验响应超过 {max_bytes} 字节")
        if self._canonical_configured_url(source, str(response.url)) != canonical:
            raise ValueError("官方直验响应 URL 与白名单目标不一致")
        return {
            "url": str(response.url),
            "status_code": response.status_code,
            "text": bytes(body).decode(response.encoding or "utf-8", errors="replace"),
        }

    async def _fetch_allowlisted_official_url(self, source, url: str, timeout: int) -> Dict[str, Any]:
        """抓取仅能由已验证官方栏目响应派生的文章 URL。"""

        canonical = canonicalize_official_url(
            url,
            base_url=source.public_url,
            trusted_hosts=source.trusted_hosts,
            allowed_path_prefixes=source.allowed_path_prefixes,
        )
        if canonical is None:
            raise ValueError("官方文章 URL 不在配置的 HTTPS 主机与栏目白名单")
        max_bytes = settings.config.web_fetch.max_decoded_bytes
        headers = {"Accept": "text/html,text/markdown;q=0.9", "Accept-Encoding": "identity"}
        async with httpx.AsyncClient(
            timeout=max(1, min(60, timeout)),
            follow_redirects=False,
            trust_env=True,
        ) as client:
            async with client.stream("GET", canonical, headers=headers) as response:
                if response.is_redirect:
                    raise ValueError("官方文章入口不允许重定向")
                response.raise_for_status()
                body = bytearray()
                async for chunk in response.aiter_bytes():
                    body.extend(chunk)
                    if len(body) > max_bytes:
                        raise ValueError(f"官方文章响应超过 {max_bytes} 字节")
        return {
            "url": str(response.url),
            "status_code": response.status_code,
            "text": bytes(body).decode(response.encoding or "utf-8", errors="replace"),
        }

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
        normalized = normalize_published_date(value)
        if not normalized:
            return 0.0
        try:
            return datetime.fromisoformat(normalized).timestamp()
        except ValueError:
            return 0.0

    async def _search_bocha(
        self,
        query: str,
        limit: int,
        timeout: int,
        freshness: str,
        search_type: str,
        include_domains: List[str] | None = None,
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
        if include_domains:
            payload["include"] = include_domains[0]
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
