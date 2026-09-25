from datetime import date, timedelta

import httpx
import pytest

from app.core.common.settings import OfficialSourceConfig, settings
from app.core.tool.base import ToolExecutionContext
from app.tools_builtin.web_fetch_tool import BlockedNetworkAddressError
from app.tools_builtin.web_search_tool import WebSearchTool


def test_web_search_tool_parses_bocha_web_results():
    payload = {
        "data": {
            "webPages": {
                "value": [
                    {
                        "name": "Agent 面试题",
                        "url": "https://example.com/agent",
                        "summary": "RAG、Tool Calling、Agent Loop 高频题。",
                        "datePublished": "2026-05-01",
                        "siteName": "Example",
                    }
                ]
            }
        }
    }
    tool = WebSearchTool()

    rows = tool._parse_bocha_web(payload, 3)

    assert rows == [
        {
            "title": "Agent 面试题",
            "url": "https://example.com/agent",
            "snippet": "RAG、Tool Calling、Agent Loop 高频题。",
            "published_date": "2026-05-01",
            "site_name": "Example",
        }
    ]


def test_web_search_expands_latest_query_with_current_year_and_official_signal():
    tool = WebSearchTool()

    queries = tool._expand_queries("OpenAI 最新模型 发布 2026", current_year=2026)

    assert queries == [
        "OpenAI 最新模型 发布 2026",
        "OpenAI 最新模型 发布 2026 official",
    ]


def test_web_search_scopes_only_the_supplemental_query_to_the_preferred_domain():
    tool = WebSearchTool()

    scopes = tool._query_scopes(
        ["OpenAI 最新模型 发布 2026", "OpenAI 最新模型 发布 2026 official"],
        ["openai.com"],
    )

    assert scopes == [
        {"query": "OpenAI 最新模型 发布 2026", "include_domains": []},
        {"query": "OpenAI 最新模型 发布 2026 official", "include_domains": ["openai.com"]},
    ]


def test_web_search_does_not_treat_untrusted_community_subdomain_as_official():
    tool = WebSearchTool()
    rows = tool._mark_source_tiers(
        [
            {"title": "Community rumor", "url": "https://community.openai.com/t/model-rumor/1"},
            {"title": "API models", "url": "https://developers.openai.com/api/docs/models"},
        ],
        ["openai.com"],
    )

    assert [row["source_tier"] for row in rows] == ["third_party", "official"]


def test_web_search_resolves_configured_anthropic_typo_alias_to_official_domain():
    assert WebSearchTool()._preferred_source_domains("authropic的最新工程博客") == ["anthropic.com"]


@pytest.mark.parametrize(
    "query",
    ["Anthropic 最近发布的一篇工程博客", "Anthropic 最近发布的工程博客", "Anthropic 最晚发布的工程博客"],
)
def test_web_search_recognizes_latest_selection_synonyms(query):
    assert WebSearchTool()._selection_mode("", query) == "latest"


def test_unconfigured_site_scope_does_not_become_official_trust_policy():
    tool = WebSearchTool()

    assert tool._preferred_source_domains("site:medium.com latest agent posts") == []
    assert tool._search_scope_domains("site:medium.com latest agent posts") == ["medium.com"]


def test_web_search_expansion_keeps_original_query_when_official_domain_cannot_be_inferred():
    tool = WebSearchTool()

    queries = tool._expand_queries("最新人工智能监管政策", current_year=2026)

    assert queries == [
        "最新人工智能监管政策",
        "最新人工智能监管政策 2026 最新进展",
    ]


def test_web_search_ranking_prefers_official_source_and_removes_near_duplicates():
    tool = WebSearchTool()
    rows = [
        {
            "title": "OpenAI 据悉将于未来数周发布全新图像模型",
            "url": "https://news.example.com/openai-image?from=feed",
            "snippet": "媒体报道 OpenAI 即将发布全新图像模型。",
            "published_date": "2026-04-20",
            "site_name": "Example News",
        },
        {
            "title": "OpenAI 据悉将于未来数周发布全新图像模型｜快讯",
            "url": "https://news.example.com/openai-image?from=mobile",
            "snippet": "OpenAI 即将发布一款全新的图像模型。",
            "published_date": "2026-04-20",
            "site_name": "Example News",
        },
        {
            "title": "Introducing GPT-5",
            "url": "https://news.example.com/introducing-gpt-5",
            "snippet": "A repost of the OpenAI release.",
            "published_date": "2026-07-30",
            "site_name": "Example News",
        },
        {
            "title": "Introducing GPT-5",
            "url": "https://openai.com/index/introducing-gpt-5/",
            "snippet": "OpenAI official model release.",
            "published_date": "2026-07-30",
            "site_name": "OpenAI",
        },
    ]

    ranked = tool._rank_results("OpenAI 最新模型 发布 2026", rows, 5, ["openai.com"])

    assert [row["url"] for row in ranked] == [
        "https://openai.com/index/introducing-gpt-5/",
        "https://news.example.com/openai-image?from=feed",
    ]


@pytest.mark.asyncio
async def test_web_search_executes_bounded_expansion_and_reports_auditable_counts(monkeypatch):
    tool = WebSearchTool()
    calls = []

    async def fake_search(query, limit, timeout, freshness, search_type, include_domains=None):
        calls.append({"query": query, "include_domains": include_domains or []})
        if include_domains == ["openai.com"]:
            return {
                "query": query,
                "source": "bocha_web",
                "results": [
                    {
                        "title": "Introducing GPT-5",
                        "url": "https://openai.com/index/introducing-gpt-5/",
                        "snippet": "OpenAI official model release.",
                        "published_date": "2026-07-30",
                        "site_name": "OpenAI",
                    }
                ],
            }
        return {
            "query": query,
            "source": "bocha_web",
            "results": [
                {
                    "title": "OpenAI 发布新模型",
                    "url": "https://news.example.com/openai-model",
                    "snippet": "媒体报道。",
                    "published_date": "2026-07-29",
                    "site_name": "Example News",
                }
            ],
        }

    monkeypatch.setattr(tool, "_search_bocha", fake_search)
    monkeypatch.setattr(tool, "_fetch_configured_official_sources", lambda *args, **kwargs: _async_result([]))

    result = await tool._run(
        {"query": "OpenAI 最新模型 发布 2026", "max_results": 5},
        ToolExecutionContext(run_id="run-1", trace_id="trace-1", session_id="session-1"),
    )

    assert calls == [
        {"query": "OpenAI 最新模型 发布 2026", "include_domains": []},
        {"query": "OpenAI 最新模型 发布 2026 official", "include_domains": ["openai.com"]},
    ]
    assert result["queries"] == [item["query"] for item in calls]
    assert result["query_scopes"] == calls
    assert result["raw_count"] == 2
    assert result["deduplicated_count"] == 2
    assert result["preferred_source_domains"] == ["openai.com"]
    assert result["preferred_source_found"] is True
    assert result["official_source_count"] == 1
    assert result["third_party_source_count"] == 1
    assert result["official_verification"] == "bocha_result"
    assert result["results"][0]["source_tier"] == "official"
    assert result["results"][0]["url"] == "https://openai.com/index/introducing-gpt-5/"


@pytest.mark.asyncio
async def test_web_search_directly_verifies_configured_official_source_when_bocha_index_misses_it(monkeypatch):
    tool = WebSearchTool()

    async def fake_search(query, limit, timeout, freshness, search_type, include_domains=None):
        return {
            "query": query,
            "source": "bocha_web",
            "results": [
                {
                    "title": "媒体整理 OpenAI 模型",
                    "url": "https://news.example.com/openai-models",
                    "snippet": "第三方整理。",
                    "published_date": "2026-07-29",
                    "site_name": "Example News",
                }
            ],
        }

    async def fake_official_sources(query, preferred_domains, timeout, context, **kwargs):
        return [
            {
                "title": "Models | OpenAI API",
                "url": "https://developers.openai.com/api/docs/models",
                "snippet": "Our latest recommended frontier models are GPT-5.6 Sol, Terra, and Luna.",
                "published_date": "",
                "site_name": "OpenAI",
                "verification_method": "configured_direct_fetch",
            }
        ]

    monkeypatch.setattr(tool, "_search_bocha", fake_search)
    monkeypatch.setattr(tool, "_fetch_configured_official_sources", fake_official_sources)

    result = await tool._run(
        {"query": "OpenAI 模型目录", "max_results": 5},
        ToolExecutionContext(run_id="run-1", trace_id="trace-1", session_id="session-1"),
    )

    assert result["source"] == "bocha_web"
    assert result["preferred_source_found"] is True
    assert result["official_source_count"] == 1
    assert result["official_verification"] == "configured_direct_fetch"
    assert result["preferred_source_trusted_hosts"] == [
        "openai.com",
        "developers.openai.com",
        "platform.openai.com",
        "help.openai.com",
        "cookbook.openai.com",
    ]
    assert result["results"][0] == {
        "title": "Models | OpenAI API",
        "url": "https://developers.openai.com/api/docs/models",
        "snippet": "Our latest recommended frontier models are GPT-5.6 Sol, Terra, and Luna.",
        "published_date": "",
        "site_name": "OpenAI",
        "verification_method": "configured_direct_fetch",
        "source_tier": "official",
    }


@pytest.mark.asyncio
async def test_latest_search_verifies_configured_official_snapshot_in_parallel(monkeypatch):
    tool = WebSearchTool()

    async def fake_search(query, limit, timeout, freshness, search_type, include_domains=None):
        return {
            "query": query,
            "source": "bocha_web",
            "results": [
                {
                    "title": f"OpenAI 最新模型 model GPT {index}",
                    "url": f"https://openai.com/index/model-{index}",
                    "snippet": "搜索提供方返回的官方站内相关页面。",
                    "published_date": date.today().isoformat(),
                    "site_name": "OpenAI",
                }
                for index in range(3)
            ],
        }

    official_calls = 0

    async def fake_official_verifier(*args, **kwargs):
        nonlocal official_calls
        official_calls += 1
        models_url = "https://developers.openai.com/api/docs/models"
        return {
            "results": [
                {
                    "title": "Models | OpenAI API",
                    "url": models_url,
                    "snippet": "# Models\nCurrent official model catalog.",
                    "published_date": "",
                    "published_date_source": "official_snapshot",
                    "site_name": "OpenAI",
                    "verification_method": "configured_direct_fetch",
                    "is_latest": True,
                }
            ],
            "warnings": [],
            "latest_evidence_verified": True,
            "selection_basis": "official_canonical_snapshot",
            "catalog_url": models_url,
            "selected_url": models_url,
            "selected_published_date": "",
            "candidate_count": 1,
        }

    monkeypatch.setattr(tool, "_search_bocha", fake_search)
    monkeypatch.setattr(tool, "_fetch_configured_official_sources", fake_official_verifier)

    result = await tool._run(
        {
            "query": "OpenAI 最新模型",
            "selection_mode": "latest",
            "as_of_date": date.today().isoformat(),
            "max_results": 1,
        },
        ToolExecutionContext(run_id="run-limit", trace_id="trace-limit", session_id="session-limit"),
    )

    assert official_calls == 1
    assert result["results"][0]["url"] == "https://developers.openai.com/api/docs/models"
    assert result["latest_evidence_verified"] is True
    assert result["selection_basis"] == "official_canonical_snapshot"


@pytest.mark.asyncio
async def test_canonical_snapshot_cannot_prove_historical_latest(monkeypatch):
    tool = WebSearchTool()
    source = OfficialSourceConfig(
        domain="openai.com",
        title="Models | OpenAI API",
        fetch_url="https://developers.openai.com/api/docs/models.md",
        public_url="https://developers.openai.com/api/docs/models",
        trusted_hosts=["openai.com", "developers.openai.com"],
        aliases=["openai"],
        topic_terms=["模型", "model"],
        content_markers=["# Models"],
        strategy="official_canonical_snapshot",
    )
    monkeypatch.setattr(settings.config.web_search, "official_sources", [source])

    async def fake_fetch(*args, **kwargs):
        return {
            "url": source.fetch_url,
            "status_code": 200,
            "text": "# Models\nCurrent official models snapshot.",
        }

    monkeypatch.setattr("app.tools_builtin.web_search_tool.WebFetchTool._run", fake_fetch)

    outcome = await tool._fetch_configured_official_sources(
        "OpenAI 2024 年最新模型",
        ["openai.com"],
        12,
        ToolExecutionContext(run_id="run-history", trace_id="trace-history", session_id="session-history"),
        selection_mode="latest",
        time_range_start="2024-01-01",
        as_of_date="2024-12-31",
    )

    assert outcome["latest_evidence_verified"] is False
    assert outcome["selected_url"] == ""
    assert outcome["results"][0]["is_latest"] is False
    assert any("当前官方快照" in warning for warning in outcome["warnings"])


@pytest.mark.asyncio
async def test_latest_evidence_flag_requires_json_boolean(monkeypatch):
    tool = WebSearchTool()
    models_url = "https://developers.openai.com/api/docs/models"

    async def no_results(*args, **kwargs):
        return {"source": "bocha_web", "results": []}

    async def string_false_outcome(*args, **kwargs):
        return {
            "results": [
                {
                    "title": "Models | OpenAI API",
                    "url": models_url,
                    "snippet": "Current official models snapshot.",
                    "published_date": "",
                    "published_date_source": "official_snapshot",
                    "site_name": "OpenAI",
                    "verification_method": "configured_direct_fetch",
                    "is_latest": False,
                }
            ],
            "warnings": [],
            "latest_evidence_verified": "false",
            "selection_basis": "official_canonical_snapshot",
            "catalog_url": models_url,
            "selected_url": "",
            "selected_published_date": "",
            "candidate_count": 1,
        }

    monkeypatch.setattr(tool, "_search_bocha", no_results)
    monkeypatch.setattr(tool, "_fetch_configured_official_sources", string_false_outcome)

    result = await tool._run(
        {
            "query": "OpenAI 最新模型",
            "selection_mode": "latest",
            "as_of_date": date.today().isoformat(),
        },
        ToolExecutionContext(run_id="run-bool", trace_id="trace-bool", session_id="session-bool"),
    )

    assert "latest_evidence_verified" not in result
    assert result["results"] == []


@pytest.mark.asyncio
async def test_official_catalog_cannot_verify_future_cutoff(monkeypatch):
    tool = WebSearchTool()
    source = OfficialSourceConfig(
        domain="anthropic.com",
        title="Engineering | Anthropic",
        fetch_url="https://www.anthropic.com/engineering",
        public_url="https://www.anthropic.com/engineering",
        trusted_hosts=["anthropic.com", "www.anthropic.com"],
        topic_terms=["工程博客"],
        content_markers=["Engineering at Anthropic"],
        strategy="official_catalog_published_at",
        content_scope="engineering_blog",
        allowed_path_prefixes=["/engineering/"],
        max_candidates=4,
        max_detail_fetches=2,
    )
    future_date = date.today() + timedelta(days=1)
    article_url = "https://www.anthropic.com/engineering/future-post"

    async def fake_fetch(source_config, url, *args, **kwargs):
        if url == source.fetch_url:
            return {
                "url": url,
                "status_code": 200,
                "text": (
                    "<h1>Engineering at Anthropic</h1>"
                    f'<article><a href="{article_url}"><h2>Future post</h2></a>'
                    f"<time>{future_date.strftime('%b %d, %Y')}</time></article>"
                ),
            }
        return {
            "url": url,
            "status_code": 200,
            "text": f"<h1>Future post</h1><p>Published {future_date.strftime('%B %d, %Y')}</p>",
        }

    monkeypatch.setattr(tool, "_fetch_trusted_official_url", fake_fetch)

    outcome = await tool._fetch_official_catalog_latest(
        source,
        12,
        ToolExecutionContext(run_id="run-future", trace_id="trace-future", session_id="session-future"),
        "",
        future_date.isoformat(),
    )

    assert outcome["latest_evidence_verified"] is False
    assert outcome["selected_url"] == ""
    assert outcome["results"][0]["is_latest"] is False
    assert any("未来" in warning for warning in outcome["warnings"])


@pytest.mark.asyncio
async def test_official_verification_uses_allowlisted_proxy_fallback_when_dns_is_intercepted(monkeypatch):
    tool = WebSearchTool()

    async def blocked_safe_fetch(*args, **kwargs):
        raise BlockedNetworkAddressError("禁止访问本机、私有、链路本地或保留网络地址")

    async def proxy_fetch(source, timeout):
        return {
            "url": source.fetch_url,
            "status_code": 200,
            "text": "# Models\n\nOur latest recommended frontier models are GPT-5.6 Sol, Terra, and Luna.",
        }

    monkeypatch.setattr("app.tools_builtin.web_search_tool.WebFetchTool._run", blocked_safe_fetch)
    monkeypatch.setattr(tool, "_fetch_allowlisted_official_source", proxy_fetch)

    outcome = await tool._fetch_configured_official_sources(
        "OpenAI 最新模型",
        ["openai.com"],
        12,
        ToolExecutionContext(run_id="run-1", trace_id="trace-1", session_id="session-1"),
    )

    assert outcome["warnings"] == []
    assert outcome["results"][0]["url"] == "https://developers.openai.com/api/docs/models"
    assert "GPT-5.6" in outcome["results"][0]["snippet"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "fetch_url",
    [
        "https://reader@www.anthropic.com/engineering",
        "https://www.anthropic.com:444/engineering",
    ],
)
async def test_allowlisted_proxy_fetch_rejects_credentials_and_non_default_port(monkeypatch, fetch_url):
    tool = WebSearchTool()
    source = OfficialSourceConfig(
        domain="anthropic.com",
        title="Engineering | Anthropic",
        fetch_url=fetch_url,
        public_url="https://www.anthropic.com/engineering",
        site_name="Anthropic",
        trusted_hosts=["anthropic.com", "www.anthropic.com"],
    )

    class ForbiddenClient:
        def __init__(self, *args, **kwargs):
            raise AssertionError("invalid configured URL must fail before opening a client")

    monkeypatch.setattr("app.tools_builtin.web_search_tool.httpx.AsyncClient", ForbiddenClient)

    with pytest.raises(ValueError, match="HTTPS 主机白名单"):
        await tool._fetch_allowlisted_official_source(source, 12)


@pytest.mark.asyncio
async def test_official_verification_retries_transient_proxy_transport_failure(monkeypatch):
    tool = WebSearchTool()
    proxy_attempts = 0

    async def fake_search(query, limit, timeout, freshness, search_type, include_domains=None):
        return {
            "query": query,
            "source": "bocha_web",
            "results": [
                {
                    "title": "媒体整理 OpenAI 模型",
                    "url": "https://news.example.com/openai-models",
                    "snippet": "第三方整理。",
                    "published_date": "2026-07-29",
                    "site_name": "Example News",
                }
            ],
        }

    async def blocked_safe_fetch(*args, **kwargs):
        raise BlockedNetworkAddressError("禁止访问本机、私有、链路本地或保留网络地址")

    async def flaky_proxy_fetch(source, timeout):
        nonlocal proxy_attempts
        proxy_attempts += 1
        if proxy_attempts == 1:
            request = httpx.Request("GET", source.fetch_url)
            raise httpx.ReadError("", request=request)
        return {
            "url": source.fetch_url,
            "status_code": 200,
            "text": "# Models\n\nOur latest recommended frontier model is GPT-5.6 Sol.",
        }

    async def skip_retry_delay(*args, **kwargs):
        return None

    monkeypatch.setattr(tool, "_search_bocha", fake_search)
    monkeypatch.setattr("app.tools_builtin.web_search_tool.WebFetchTool._run", blocked_safe_fetch)
    monkeypatch.setattr(tool, "_fetch_allowlisted_official_source", flaky_proxy_fetch)
    monkeypatch.setattr("app.tools_builtin.web_search_tool.asyncio.sleep", skip_retry_delay)

    result = await tool._run(
        {"query": "查找 OpenAI 模型目录", "max_results": 5},
        ToolExecutionContext(run_id="run-1", trace_id="trace-1", session_id="session-1"),
    )

    assert proxy_attempts == 2
    assert result["official_source_count"] == 1
    assert result["official_verification"] == "configured_direct_fetch"
    assert result["results"][0]["verification_method"] == "configured_direct_fetch"


@pytest.mark.asyncio
async def test_official_verification_does_not_retry_non_transport_proxy_error(monkeypatch):
    tool = WebSearchTool()
    proxy_attempts = 0

    async def blocked_safe_fetch(*args, **kwargs):
        raise BlockedNetworkAddressError("禁止访问本机、私有、链路本地或保留网络地址")

    async def rejected_proxy_fetch(source, timeout):
        nonlocal proxy_attempts
        proxy_attempts += 1
        raise ValueError("官方直验 URL 不在配置的 HTTPS 主机白名单")

    monkeypatch.setattr("app.tools_builtin.web_search_tool.WebFetchTool._run", blocked_safe_fetch)
    monkeypatch.setattr(tool, "_fetch_allowlisted_official_source", rejected_proxy_fetch)

    outcome = await tool._fetch_configured_official_sources(
        "OpenAI 最新模型",
        ["openai.com"],
        12,
        ToolExecutionContext(run_id="run-1", trace_id="trace-1", session_id="session-1"),
    )

    assert proxy_attempts == 1
    assert outcome["results"] == []
    assert "代理抓取：ValueError: 官方直验 URL 不在配置的 HTTPS 主机白名单" in outcome["warnings"][0]


@pytest.mark.asyncio
async def test_official_verification_reports_empty_transport_error_type_after_retry_exhaustion(monkeypatch):
    tool = WebSearchTool()
    proxy_attempts = 0

    async def blocked_safe_fetch(*args, **kwargs):
        raise BlockedNetworkAddressError("禁止访问本机、私有、链路本地或保留网络地址")

    async def failed_proxy_fetch(source, timeout):
        nonlocal proxy_attempts
        proxy_attempts += 1
        request = httpx.Request("GET", source.fetch_url)
        raise httpx.ReadError("", request=request)

    async def skip_retry_delay(*args, **kwargs):
        return None

    monkeypatch.setattr("app.tools_builtin.web_search_tool.WebFetchTool._run", blocked_safe_fetch)
    monkeypatch.setattr(tool, "_fetch_allowlisted_official_source", failed_proxy_fetch)
    monkeypatch.setattr("app.tools_builtin.web_search_tool.asyncio.sleep", skip_retry_delay)

    outcome = await tool._fetch_configured_official_sources(
        "OpenAI 最新模型",
        ["openai.com"],
        12,
        ToolExecutionContext(run_id="run-1", trace_id="trace-1", session_id="session-1"),
    )

    assert proxy_attempts == 2
    assert outcome["results"] == []
    assert "代理抓取：ReadError" in outcome["warnings"][0]


@pytest.mark.asyncio
async def test_official_verification_does_not_proxy_fallback_after_non_dns_safety_failure(monkeypatch):
    tool = WebSearchTool()
    proxy_attempts = 0

    async def rejected_safe_fetch(*args, **kwargs):
        raise ValueError("Web 响应解码内容超过安全预算")

    async def proxy_fetch(source, timeout):
        nonlocal proxy_attempts
        proxy_attempts += 1
        raise AssertionError("proxy fallback must not run")

    monkeypatch.setattr("app.tools_builtin.web_search_tool.WebFetchTool._run", rejected_safe_fetch)
    monkeypatch.setattr(tool, "_fetch_allowlisted_official_source", proxy_fetch)

    outcome = await tool._fetch_configured_official_sources(
        "OpenAI 最新模型",
        ["openai.com"],
        12,
        ToolExecutionContext(run_id="run-1", trace_id="trace-1", session_id="session-1"),
    )

    assert proxy_attempts == 0
    assert outcome["results"] == []
    assert "安全预算" in outcome["warnings"][0]


@pytest.mark.asyncio
async def test_web_search_keeps_third_party_results_when_preferred_source_is_missing(monkeypatch):
    tool = WebSearchTool()

    async def fake_search(query, limit, timeout, freshness, search_type, include_domains=None):
        return {
            "query": query,
            "source": "bocha_web",
            "results": [
                {
                    "title": "媒体称 OpenAI 发布新模型",
                    "url": "https://news.example.com/openai-model",
                    "snippet": "尚未提供官方公告链接。",
                    "published_date": "2026-07-29",
                    "site_name": "Example News",
                }
            ],
        }

    monkeypatch.setattr(tool, "_search_bocha", fake_search)
    monkeypatch.setattr(tool, "_fetch_configured_official_sources", lambda *args, **kwargs: _async_result([]))
    result = await tool._run(
        {"query": "OpenAI 最新模型 官方来源", "max_results": 5},
        ToolExecutionContext(run_id="run-1", trace_id="trace-1", session_id="session-1"),
    )

    assert result["preferred_source_domains"] == ["openai.com"]
    assert result["preferred_source_found"] is False
    assert result["official_source_count"] == 0
    assert result["official_verification"] == "not_found"
    assert all(row["source_tier"] == "third_party" for row in result["results"])
    assert result["warnings"] == []


@pytest.mark.asyncio
async def test_web_search_does_not_call_secondary_provider_when_bocha_has_no_results(monkeypatch):
    tool = WebSearchTool()

    async def fake_bocha(query, limit, timeout, freshness, search_type, include_domains=None):
        return {
            "query": query,
            "source": "bocha_web",
            "results": [],
        }

    async def forbidden_secondary_provider(*args, **kwargs):
        raise AssertionError("不应调用博查之外的搜索提供方")

    monkeypatch.setattr(tool, "_search_bocha", fake_bocha)
    monkeypatch.setattr(tool, "_fetch_configured_official_sources", lambda *args, **kwargs: _async_result([]))
    monkeypatch.setattr("httpx.AsyncClient.get", forbidden_secondary_provider)

    result = await tool._run(
        {"query": "OpenAI 最新模型", "max_results": 5},
        ToolExecutionContext(run_id="run-1", trace_id="trace-1", session_id="session-1"),
    )

    assert result["source"] == "bocha_web"
    assert result["results"] == []
    assert result["preferred_source_found"] is False
    assert result["official_source_count"] == 0
    assert result["official_verification"] == "not_found"
    assert "Bocha 搜索没有返回结果" in result["warnings"]
    assert all("官方域名" not in warning for warning in result["warnings"])


@pytest.mark.asyncio
async def test_latest_engineering_blog_is_selected_from_official_catalog_not_newer_news(monkeypatch):
    tool = WebSearchTool()
    source = OfficialSourceConfig(
        domain="anthropic.com",
        title="Engineering | Anthropic",
        fetch_url="https://www.anthropic.com/engineering",
        public_url="https://www.anthropic.com/engineering",
        site_name="Anthropic",
        trusted_hosts=["anthropic.com", "www.anthropic.com"],
        match_terms=["anthropic", "工程博客", "engineering blog"],
        content_markers=["Engineering at Anthropic"],
        strategy="official_catalog_published_at",
        content_scope="engineering_blog",
        allowed_path_prefixes=["/engineering/"],
        max_candidates=8,
        max_detail_fetches=3,
    )
    monkeypatch.setattr(settings.config.web_search, "official_sources", [source])

    async def fake_search(query, limit, timeout, freshness, search_type, include_domains=None):
        return {
            "query": query,
            "source": "bocha_web",
            "results": [
                {
                    "title": "Claude Fable 5",
                    "url": "https://www.anthropic.com/claude/fable",
                    "snippet": "A newer product announcement.",
                    "published_date": "2026-06-09",
                    "site_name": "Anthropic",
                },
                {
                    "title": "Claude Opus 4.7",
                    "url": "https://www.anthropic.com/news/claude-opus-4-7",
                    "snippet": "A news release, not an engineering article.",
                    "published_date": "2026-04-16",
                    "site_name": "Anthropic",
                },
            ],
        }

    async def fake_fetch(self, arguments, context):
        url = arguments["url"]
        if url == source.fetch_url:
            return {
                "url": url,
                "status_code": 200,
                "text": """
                    <main>
                      <h1>Engineering at Anthropic</h1>
                      <a class="featured-card" href="/engineering/how-we-contain-claude">
                        <h2>How we contain Claude across products</h2>
                        <p>How safeguards are deployed across products.</p>
                      </a>
                      <article>
                        <a href="/engineering/april-23-postmortem">
                          <h2>An update on recent Claude Code quality reports</h2>
                        </a>
                        <time>Apr 23, 2026</time>
                      </article>
                    </main>
                """,
            }
        if url == "https://www.anthropic.com/engineering/how-we-contain-claude":
            return {
                "url": url,
                "status_code": 200,
                "text": """
                    <html><head><meta name="description" content="How safeguards are deployed."></head>
                    <body><h1>How we contain Claude across products</h1><p>Published May 25, 2026</p></body></html>
                """,
            }
        raise AssertionError(f"unexpected official fetch: {url}")

    monkeypatch.setattr(tool, "_search_bocha", fake_search)
    monkeypatch.setattr("app.tools_builtin.web_search_tool.WebFetchTool._run", fake_fetch)

    result = await tool._run(
        {
            "query": "Anthropic 最新工程博客",
            "selection_mode": "latest",
            "as_of_date": "2026-08-01",
            "source_preference": "official_first",
            "content_scope": "engineering_blog",
            "max_results": 5,
        },
        ToolExecutionContext(run_id="run-latest", trace_id="trace-latest", session_id="session-latest"),
    )

    assert result["preferred_source_domains"] == ["anthropic.com"]
    assert result["selection_mode"] == "latest"
    assert result["as_of_date"] == "2026-08-01"
    assert result["content_scope"] == "engineering_blog"
    assert result["latest_evidence_verified"] is True
    assert result["official_verification"] == "configured_official_index"
    assert result["selected_url"] == "https://www.anthropic.com/engineering/how-we-contain-claude"
    assert result["selected_published_date"] == "2026-05-25"
    assert all(row["url"] != source.fetch_url for row in result["results"])
    assert all(
        row["source_tier"] != "official"
        for row in result["results"]
        if row["url"].startswith(("https://www.anthropic.com/claude/", "https://www.anthropic.com/news/"))
    )


@pytest.mark.asyncio
async def test_latest_anthropic_technical_post_is_selected_across_official_catalogs(monkeypatch):
    tool = WebSearchTool()
    research = OfficialSourceConfig(
        domain="anthropic.com",
        title="Research | Anthropic",
        fetch_url="https://www.anthropic.com/research",
        public_url="https://www.anthropic.com/research",
        site_name="Anthropic",
        trusted_hosts=["anthropic.com", "www.anthropic.com"],
        match_terms=["anthropic", "工程博客"],
        topic_terms=["工程博客"],
        content_markers=["Research"],
        strategy="official_catalog_published_at",
        content_scope="engineering_blog",
        allowed_path_prefixes=["/research/"],
        max_candidates=20,
        max_detail_fetches=2,
    )
    engineering = OfficialSourceConfig(
        domain="anthropic.com",
        title="Engineering | Anthropic",
        fetch_url="https://www.anthropic.com/engineering",
        public_url="https://www.anthropic.com/engineering",
        site_name="Anthropic",
        trusted_hosts=["anthropic.com", "www.anthropic.com"],
        match_terms=["anthropic", "工程博客"],
        topic_terms=["工程博客"],
        content_markers=["Engineering at Anthropic"],
        strategy="official_catalog_published_at",
        content_scope="engineering_blog",
        allowed_path_prefixes=["/engineering/"],
        max_candidates=20,
        max_detail_fetches=2,
    )
    supplemental = OfficialSourceConfig(
        domain="anthropic.com",
        title="Research updates | Anthropic",
        fetch_url="https://www.anthropic.com/research/updates",
        public_url="https://www.anthropic.com/research/updates",
        site_name="Anthropic",
        trusted_hosts=["anthropic.com", "www.anthropic.com"],
        match_terms=["anthropic", "工程博客"],
        topic_terms=["工程博客"],
        content_markers=["Research updates"],
        strategy="official_catalog_published_at",
        content_scope="engineering_blog",
        allowed_path_prefixes=["/research/"],
        max_candidates=20,
        max_detail_fetches=2,
    )
    # 第三个目录故意放置最新文章，防止目录数量截断或配置顺序冒充发布日期排序。
    monkeypatch.setattr(settings.config.web_search, "official_sources", [research, engineering, supplemental])

    research_url = "https://www.anthropic.com/research/discovering-cryptographic-weaknesses"
    engineering_url = "https://www.anthropic.com/engineering/how-we-contain-claude"
    supplemental_url = "https://www.anthropic.com/research/latest-supplemental-result"

    async def fake_fetch(source, url, *args, **kwargs):
        if url == research.fetch_url:
            return {
                "url": url,
                "status_code": 200,
                "text": (
                    '<main><h1>Research</h1><article><a href="'
                    f'{research_url}"><h2>Discovering cryptographic weaknesses with Claude</h2></a>'
                    "<time>Jul 28, 2026</time></article></main>"
                ),
            }
        if url == engineering.fetch_url:
            return {
                "url": url,
                "status_code": 200,
                "text": (
                    '<main><h1>Engineering at Anthropic</h1><article><a href="'
                    f'{engineering_url}"><h2>How we contain Claude across products</h2></a>'
                    "<time>May 25, 2026</time></article></main>"
                ),
            }
        if url == supplemental.fetch_url:
            return {
                "url": url,
                "status_code": 200,
                "text": (
                    '<main><h1>Research updates</h1><article><a href="'
                    f'{supplemental_url}"><h2>Latest supplemental result</h2></a>'
                    "<time>Jul 29, 2026</time></article></main>"
                ),
            }
        published_by_url = {
            research_url: "Jul 28, 2026",
            engineering_url: "May 25, 2026",
            supplemental_url: "Jul 29, 2026",
        }
        title_by_url = {
            research_url: "Discovering cryptographic weaknesses with Claude",
            engineering_url: "How we contain Claude across products",
            supplemental_url: "Latest supplemental result",
        }
        return {
            "url": url,
            "status_code": 200,
            "text": f"<h1>{title_by_url[url]}</h1><p>{published_by_url[url]}</p>",
        }

    monkeypatch.setattr(tool, "_fetch_trusted_official_url", fake_fetch)

    outcome = await tool._fetch_configured_official_sources(
        "Anthropic 最新工程博客",
        ["anthropic.com"],
        12,
        ToolExecutionContext(
            run_id="run-cross-catalog", trace_id="trace-cross-catalog", session_id="session-cross-catalog"
        ),
        selection_mode="latest",
        as_of_date="2026-08-01",
        content_scope="engineering_blog",
    )

    assert outcome["latest_evidence_verified"] is True
    assert outcome["selected_url"] == supplemental_url
    assert outcome["selected_published_date"] == "2026-07-29"
    assert sum(row["is_latest"] is True for row in outcome["results"]) == 1
    assert next(row for row in outcome["results"] if row["is_latest"])["url"] == supplemental_url
    marked = tool._mark_source_tiers(outcome["results"], ["anthropic.com"], "engineering_blog")
    assert next(row for row in marked if row["url"] == supplemental_url)["source_tier"] == "official"


@pytest.mark.asyncio
async def test_truncated_official_catalog_cannot_verify_latest_and_keeps_diagnostics(monkeypatch):
    tool = WebSearchTool()
    source = OfficialSourceConfig(
        domain="anthropic.com",
        title="Engineering | Anthropic",
        fetch_url="https://www.anthropic.com/engineering",
        public_url="https://www.anthropic.com/engineering",
        site_name="Anthropic",
        trusted_hosts=["anthropic.com", "www.anthropic.com"],
        match_terms=["anthropic", "工程博客"],
        content_markers=["Engineering at Anthropic"],
        strategy="official_catalog_published_at",
        content_scope="engineering_blog",
        allowed_path_prefixes=["/engineering/"],
        max_candidates=8,
        max_detail_fetches=2,
    )
    monkeypatch.setattr(settings.config.web_search, "official_sources", [source])

    async def fake_search(query, limit, timeout, freshness, search_type, include_domains=None):
        return {"query": query, "source": "bocha_web", "results": []}

    async def fake_fetch(self, arguments, context):
        url = arguments["url"]
        if url == source.fetch_url:
            return {
                "url": url,
                "status_code": 200,
                "truncated": True,
                "text": """
                    <main><h1>Engineering at Anthropic</h1>
                      <article><a href="/engineering/how-we-contain-claude">
                        <h2>How we contain Claude across products</h2></a>
                        <time>May 25, 2026</time>
                      </article>
                    </main>
                """,
            }
        if url == "https://www.anthropic.com/engineering/how-we-contain-claude":
            return {
                "url": url,
                "status_code": 200,
                "truncated": False,
                "text": "<h1>How we contain Claude across products</h1><p>Published May 25, 2026</p>",
            }
        raise AssertionError(f"unexpected official fetch: {url}")

    monkeypatch.setattr(tool, "_search_bocha", fake_search)
    monkeypatch.setattr("app.tools_builtin.web_search_tool.WebFetchTool._run", fake_fetch)

    async def unavailable_complete_catalog(*args, **kwargs):
        raise ValueError("dedicated catalog fetch unavailable")

    monkeypatch.setattr(tool, "_fetch_allowlisted_official_source_with_retries", unavailable_complete_catalog)

    result = await tool._run(
        {
            "query": "Anthropic 最新工程博客",
            "selection_mode": "latest",
            "as_of_date": "2026-08-01",
            "content_scope": "engineering_blog",
        },
        ToolExecutionContext(run_id="run-truncated", trace_id="trace-truncated", session_id="session-truncated"),
    )

    assert "latest_evidence_verified" not in result
    assert result["results"] == []
    assert any("截断" in warning for warning in result["warnings"])


def test_scoped_official_catalog_requires_matching_content_scope(monkeypatch):
    tool = WebSearchTool()
    source = OfficialSourceConfig(
        domain="anthropic.com",
        title="Engineering | Anthropic",
        fetch_url="https://www.anthropic.com/engineering",
        public_url="https://www.anthropic.com/engineering",
        trusted_hosts=["anthropic.com", "www.anthropic.com"],
        match_terms=["anthropic"],
        content_scope="engineering_blog",
        allowed_path_prefixes=["/engineering/"],
    )
    monkeypatch.setattr(settings.config.web_search, "official_sources", [source])

    assert tool._matching_official_sources("Anthropic 最新模型", ["anthropic.com"], "") == []


def test_official_snapshot_requires_topic_match_and_exact_content_scope(monkeypatch):
    tool = WebSearchTool()
    source = OfficialSourceConfig(
        domain="openai.com",
        title="Models | OpenAI API",
        fetch_url="https://developers.openai.com/api/docs/models.md",
        public_url="https://developers.openai.com/api/docs/models",
        trusted_hosts=["openai.com", "developers.openai.com"],
        aliases=["openai", "gpt"],
        match_terms=["openai", "模型", "model"],
        topic_terms=["模型", "model", "gpt"],
    )
    monkeypatch.setattr(settings.config.web_search, "official_sources", [source])

    assert tool._matching_official_sources("OpenAI 最新新闻", ["openai.com"], "") == []
    assert tool._matching_official_sources("OpenAI 最新工程博客", ["openai.com"], "engineering_blog") == []
    assert tool._matching_official_sources("OpenAI 最新模型", ["openai.com"], "") == [source]
    assert tool._matching_official_sources("OpenAI latest GPT", ["openai.com"], "") == [source]


@pytest.mark.asyncio
async def test_same_day_official_articles_cannot_be_claimed_as_unique_latest(monkeypatch):
    tool = WebSearchTool()
    source = OfficialSourceConfig(
        domain="anthropic.com",
        title="Engineering | Anthropic",
        fetch_url="https://www.anthropic.com/engineering",
        public_url="https://www.anthropic.com/engineering",
        site_name="Anthropic",
        trusted_hosts=["anthropic.com", "www.anthropic.com"],
        match_terms=["anthropic", "工程博客"],
        content_markers=["Engineering at Anthropic"],
        strategy="official_catalog_published_at",
        content_scope="engineering_blog",
        allowed_path_prefixes=["/engineering/"],
        max_candidates=8,
        max_detail_fetches=2,
    )
    monkeypatch.setattr(settings.config.web_search, "official_sources", [source])

    async def fake_search(query, limit, timeout, freshness, search_type, include_domains=None):
        return {"query": query, "source": "bocha_web", "results": []}

    async def fake_fetch(self, arguments, context):
        url = arguments["url"]
        if url == source.fetch_url:
            return {
                "url": url,
                "status_code": 200,
                "truncated": False,
                "text": """
                    <main><h1>Engineering at Anthropic</h1>
                      <article><a href="/engineering/post-a"><h2>Post A</h2></a><time>May 25, 2026</time></article>
                      <article><a href="/engineering/post-b"><h2>Post B</h2></a><time>May 25, 2026</time></article>
                    </main>
                """,
            }
        if url.endswith("/post-a"):
            return {
                "url": url,
                "status_code": 200,
                "truncated": False,
                "text": "<h1>Post A</h1><p>Published May 25, 2026</p>",
            }
        raise AssertionError(f"unexpected official fetch: {url}")

    monkeypatch.setattr(tool, "_search_bocha", fake_search)
    monkeypatch.setattr("app.tools_builtin.web_search_tool.WebFetchTool._run", fake_fetch)

    result = await tool._run(
        {
            "query": "Anthropic 最新工程博客",
            "selection_mode": "latest",
            "as_of_date": "2026-08-01",
            "content_scope": "engineering_blog",
        },
        ToolExecutionContext(run_id="run-tie", trace_id="trace-tie", session_id="session-tie"),
    )

    assert "latest_evidence_verified" not in result
    assert result["results"] == []
    assert any("同日并列" in warning for warning in result["warnings"])


@pytest.mark.asyncio
async def test_official_fetch_rejects_final_host_outside_exact_trusted_hosts(monkeypatch):
    tool = WebSearchTool()
    source = OfficialSourceConfig(
        domain="openai.com",
        title="Models | OpenAI API",
        fetch_url="https://developers.openai.com/api/docs/models.md",
        public_url="https://developers.openai.com/api/docs/models",
        trusted_hosts=["developers.openai.com"],
        match_terms=["openai", "models"],
        content_markers=["# Models"],
    )
    monkeypatch.setattr(settings.config.web_search, "official_sources", [source])

    async def redirected_fetch(*args, **kwargs):
        return {
            "url": "https://community.openai.com/t/models/1",
            "status_code": 200,
            "text": "# Models\nUntrusted community content.",
        }

    monkeypatch.setattr("app.tools_builtin.web_search_tool.WebFetchTool._run", redirected_fetch)

    outcome = await tool._fetch_configured_official_sources(
        "OpenAI models",
        ["openai.com"],
        12,
        ToolExecutionContext(run_id="run-host", trace_id="trace-host", session_id="session-host"),
    )

    assert outcome["results"] == []


async def _async_result(value):
    return value
