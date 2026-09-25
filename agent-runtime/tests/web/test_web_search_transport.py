import json
from types import SimpleNamespace
from unittest.mock import AsyncMock

import httpx
import pytest

from app.core.tool.base import ToolExecutionContext
from app.tools_builtin import web_search_tool as module

CONTEXT = ToolExecutionContext(run_id="run", trace_id="trace", session_id="session")
SOURCE = SimpleNamespace(
    fetch_url="https://example.test/catalog",
    public_url="https://example.test/catalog",
    trusted_hosts=["example.test"],
    allowed_path_prefixes=["/articles/"],
)


@pytest.mark.asyncio
async def test_search_input_requires_nontrivial_query():
    tool = module.WebSearchTool()
    assert (await tool.validate_input({}, CONTEXT)).result is False
    assert (await tool.validate_input({"query": "x"}, CONTEXT)).result is False
    assert (await tool.validate_input({"query": "query"}, CONTEXT)).result is True


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["web", "ai", "http", "network", "malformed", "missing_key"])
async def test_search_provider_request_and_failures(monkeypatch, mode):
    monkeypatch.setattr(
        module.settings.config.web_search, "bocha_api_key", "" if mode == "missing_key" else "synthetic"
    )
    requests = []

    def handle(request):
        requests.append(request)
        if mode == "network":
            raise httpx.ConnectError("offline")
        if mode == "http":
            return httpx.Response(503)
        if mode == "malformed":
            return httpx.Response(200, text="invalid")
        row = {"name": "Result", "url": "https://example.test/article", "summary": "summary"}
        body = (
            {"messages": [{"content_type": "webpage", "content": json.dumps({"value": [row]})}]}
            if mode == "ai"
            else {"data": {"webPages": {"value": [row]}}}
        )
        return httpx.Response(200, json=body)

    client_type = httpx.AsyncClient
    monkeypatch.setattr(
        module.httpx, "AsyncClient", lambda **kw: client_type(transport=httpx.MockTransport(handle), **kw)
    )
    result = await module.WebSearchTool()._search_bocha(
        "query", 2, 1, "noLimit", "bocha_ai" if mode == "ai" else "bocha_web", include_domains=["example.test"]
    )
    if mode in {"web", "ai"}:
        assert result["results"][0]["title"] == "Result"
        payload = json.loads(requests[0].content)
        assert payload["include"] == "example.test"
        assert requests[0].headers["authorization"] == "Bearer synthetic"
    else:
        assert result["results"] == []
        assert result["warning"]
    assert len(requests) == (0 if mode == "missing_key" else 1)


def test_ai_search_skips_bad_json_and_images_and_caps_rows():
    tool = module.WebSearchTool()
    results = tool._parse_bocha_ai(
        {
            "messages": [
                {"content_type": "webpage", "content": "bad"},
                {"content_type": "image", "content": "image"},
                {"content_type": "answer", "content": "answer"},
            ]
        },
        1,
    )
    assert len(results) == 1
    assert results[0]["title"] == "Bocha AI Search"
    assert tool._normalize_bocha_items([{}, {"name": "one"}, {"name": "two"}], 1)[0]["title"] == "one"
    assert tool._expand_queries(" ") == []
    assert tool._contains_preferred_source([], []) is False
    assert tool._search_scope_domains("普通问题", []) == []
    assert tool._search_scope_domains("ExampleCompany release", []) == ["examplecompany.com"]


@pytest.mark.asyncio
@pytest.mark.parametrize("failure", [False, True])
async def test_provider_warnings_and_failures_are_retained(monkeypatch, failure):
    tool = module.WebSearchTool()
    search = AsyncMock(return_value={"results": [], "warning": "upstream warning"})
    if failure:
        search.side_effect = RuntimeError("upstream failure")
    monkeypatch.setattr(tool, "_search_bocha", search)
    result = await tool._run({"query": "ordinary topic", "expand_query": False}, CONTEXT)
    assert any("upstream" in warning for warning in result["warnings"])


@pytest.mark.asyncio
@pytest.mark.parametrize("kind", ["catalog", "article"])
@pytest.mark.parametrize("mode", ["success", "redirect", "oversized"])
async def test_allowlisted_fetch_is_bounded_and_never_follows_redirects(monkeypatch, kind, mode):
    monkeypatch.setattr(module.settings.config.web_fetch, "max_decoded_bytes", 4)
    client_type = httpx.AsyncClient

    def handle(request):
        if mode == "redirect":
            return httpx.Response(302, headers={"location": "https://evil.invalid"})
        return httpx.Response(200, text="too large" if mode == "oversized" else "body")

    def client(**kw):
        assert kw["follow_redirects"] is False
        return client_type(transport=httpx.MockTransport(handle), **kw)

    monkeypatch.setattr(module.httpx, "AsyncClient", client)
    tool = module.WebSearchTool()

    async def invoke():
        if kind == "catalog":
            return await tool._fetch_allowlisted_official_source(SOURCE, 1)
        return await tool._fetch_allowlisted_official_url(SOURCE, "https://example.test/articles/one", 1)

    if mode == "success":
        assert (await invoke())["text"] == "body"
    else:
        with pytest.raises(ValueError, match="重定向|超过"):
            await invoke()


@pytest.mark.asyncio
async def test_article_retry_has_upper_bound_and_does_not_relax_allowlist(monkeypatch):
    tool = module.WebSearchTool()
    monkeypatch.setattr(module.settings.config.web_search, "official_fetch_max_attempts", 2)
    monkeypatch.setattr(module.settings.config.web_search, "official_fetch_retry_backoff_seconds", 0.1)
    monkeypatch.setattr(module.asyncio, "sleep", AsyncMock())
    fetch = AsyncMock(side_effect=httpx.ConnectError("offline"))
    monkeypatch.setattr(tool, "_fetch_allowlisted_official_url", fetch)
    with pytest.raises(httpx.ConnectError):
        await tool._fetch_allowlisted_official_url_with_retries(SOURCE, "https://example.test/articles/one", 1)
    assert fetch.await_count == 2
    module.asyncio.sleep.assert_awaited_once_with(0.1)
    fetch.side_effect = None
    fetch.return_value = {"text": "body"}
    assert await tool._fetch_allowlisted_official_url_with_retries(SOURCE, "https://example.test/articles/one", 1) == {
        "text": "body"
    }


@pytest.mark.asyncio
async def test_article_fetch_rejects_nonallowlisted_url_before_transport():
    with pytest.raises(ValueError, match="白名单"):
        await module.WebSearchTool()._fetch_allowlisted_official_url(SOURCE, "https://evil.invalid/articles/one", 1)


@pytest.mark.asyncio
@pytest.mark.parametrize("article", [False, True])
async def test_trusted_fetch_uses_allowlisted_fallback_for_blocked_dns(monkeypatch, article):
    tool = module.WebSearchTool()
    url = "https://example.test/articles/one" if article else SOURCE.fetch_url
    fetched = {"url": url, "status_code": 200, "text": "body", "truncated": False}
    monkeypatch.setattr(
        module.WebFetchTool, "_run", AsyncMock(side_effect=module.BlockedNetworkAddressError("blocked"))
    )
    catalog = AsyncMock(return_value=fetched)
    detail = AsyncMock(return_value=fetched)
    monkeypatch.setattr(tool, "_fetch_allowlisted_official_source_with_retries", catalog)
    monkeypatch.setattr(tool, "_fetch_allowlisted_official_url_with_retries", detail)
    assert await tool._fetch_trusted_official_url(SOURCE, url, 1, CONTEXT, require_content_path=article) == fetched
    assert detail.await_count == int(article)
    assert catalog.await_count == int(not article)


@pytest.mark.asyncio
@pytest.mark.parametrize("mode", ["expanded", "http", "redirect", "outside"])
async def test_trusted_fetch_rejects_protocol_failures_and_expands_truncated_catalog(monkeypatch, mode):
    tool = module.WebSearchTool()
    fetched = {
        "url": "https://evil.invalid" if mode == "redirect" else SOURCE.fetch_url,
        "status_code": 503 if mode == "http" else 200,
        "text": "partial",
        "truncated": mode == "expanded",
    }
    monkeypatch.setattr(module.WebFetchTool, "_run", AsyncMock(return_value=fetched))
    monkeypatch.setattr(
        tool, "_fetch_allowlisted_official_source_with_retries", AsyncMock(return_value={**fetched, "text": "complete"})
    )
    if mode == "expanded":
        result = await tool._fetch_trusted_official_url(
            SOURCE, SOURCE.fetch_url, 1, CONTEXT, require_content_path=False
        )
        assert result["text"] == "complete" and not result["truncated"]
    else:
        with pytest.raises(ValueError):
            await tool._fetch_trusted_official_url(
                SOURCE,
                "https://evil.invalid" if mode == "outside" else SOURCE.fetch_url,
                1,
                CONTEXT,
                require_content_path=mode == "outside",
            )


def test_official_scope_and_trusted_host_limits_are_explicit(monkeypatch):
    from app.core.common.settings import OfficialSourceConfig

    source = OfficialSourceConfig(
        domain="example.test",
        title="Engineering",
        fetch_url=SOURCE.fetch_url,
        public_url=SOURCE.public_url,
        trusted_hosts=["", "example.test", "example.test"] + [f"host{i}.example.test" for i in range(22)],
        content_scope="engineering_blog",
        allowed_path_prefixes=["/articles/"],
    )
    monkeypatch.setattr(module.settings.config.web_search, "official_sources", [source])
    tool = module.WebSearchTool()
    assert tool._infer_content_scope("engineering blog") == "engineering_blog"
    assert len(tool._preferred_source_trusted_hosts(["example.test"])) == 20
    assert not tool._row_matches_content_scope(
        {"url": "https://example.test/articles/a"}, ["other.test"], "engineering_blog"
    )
    assert not tool._row_matches_content_scope({"url": "https://example.test/articles/a"}, ["example.test"], "news")
    assert tool._canonical_url("relative/path") == "relative/path"


@pytest.mark.parametrize("verified", [True, False])
def test_cross_catalog_latest_requires_unique_fully_verified_result(verified):
    tool = module.WebSearchTool()
    rows = [{"url": "https://example.test/a"}, {"url": "https://example.test/b"}]
    outcomes = [
        {"latest_evidence_verified": True, "selected_url": rows[0]["url"], "selected_published_date": "2026-01-01"},
        {"latest_evidence_verified": verified, "selected_url": rows[1]["url"], "selected_published_date": "2026-01-01"},
    ]
    normalized, evidence, warnings = tool._resolve_catalog_latest_evidence(rows, outcomes)
    assert not evidence["latest_evidence_verified"]
    assert not any(row["is_latest"] for row in normalized)
    assert warnings


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "mode", ["unavailable", "missing_marker", "invalid_date", "detail_failure", "detail_changes_latest"]
)
async def test_catalog_verification_preserves_failure_and_rechecks_changed_dates(monkeypatch, mode):
    from app.core.common.settings import OfficialSourceConfig

    source = OfficialSourceConfig(
        domain="example.test",
        title="Engineering",
        fetch_url=SOURCE.fetch_url,
        public_url=SOURCE.public_url,
        trusted_hosts=SOURCE.trusted_hosts,
        allowed_path_prefixes=SOURCE.allowed_path_prefixes,
        strategy="official_catalog_published_at",
        content_markers=["Engineering"],
        max_detail_fetches=3,
    )
    catalog = '<h1>Engineering</h1><article><h2><a href="/articles/a">Article A</a></h2><time datetime="2026-01-02">Jan 2</time></article><article><h2><a href="/articles/b">Article B</a></h2><time datetime="2026-01-01">Jan 1</time></article>'
    calls = []

    async def fetch(source, url, *args, **kwargs):
        calls.append(url)
        if mode == "unavailable" or (mode == "detail_failure" and url != SOURCE.fetch_url):
            raise httpx.ConnectError("offline")
        if url == SOURCE.fetch_url:
            return {"text": "unrelated" if mode == "missing_marker" else catalog}
        day = "2025-12-01" if url.endswith("/a") else "2026-01-01"
        return {"text": f'<meta property="article:published_time" content="{day}"><h1>Article</h1>'}

    tool = module.WebSearchTool()
    monkeypatch.setattr(tool, "_fetch_trusted_official_url", fetch)
    result = await tool._fetch_official_catalog_latest(
        source, 1, CONTEXT, "", "invalid" if mode == "invalid_date" else "2026-01-10"
    )
    if mode == "detail_changes_latest":
        assert result["selected_url"].endswith("/b")
        assert SOURCE.public_url.replace("/catalog", "/articles/b") in calls
    else:
        assert result["warnings"]


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "payload", [{"status_code": 503, "text": "Engineering"}, {"status_code": 200, "text": "unrelated"}]
)
async def test_canonical_official_source_requires_success_status_and_content_marker(monkeypatch, payload):
    from app.core.common.settings import OfficialSourceConfig

    source = OfficialSourceConfig(
        domain="example.test",
        title="Engineering",
        fetch_url=SOURCE.fetch_url,
        public_url=SOURCE.public_url,
        trusted_hosts=SOURCE.trusted_hosts,
        content_markers=["Engineering"],
        topic_terms=["engineering"],
    )
    monkeypatch.setattr(module.settings.config.web_search, "official_sources", [source])
    monkeypatch.setattr(module.WebFetchTool, "_run", AsyncMock(return_value={"url": SOURCE.fetch_url, **payload}))
    result = await module.WebSearchTool()._fetch_configured_official_sources(
        "engineering", ["example.test"], 1, CONTEXT
    )
    assert result["results"] == []
    assert not result["latest_evidence_verified"]


@pytest.mark.asyncio
async def test_allowlisted_catalog_rejects_response_from_different_path(monkeypatch):
    client_type = httpx.AsyncClient

    async def rewrite_response(response):
        response.request = httpx.Request("GET", "https://example.test/other")

    transport = httpx.MockTransport(lambda request: httpx.Response(200, text="body"))
    monkeypatch.setattr(
        module.httpx,
        "AsyncClient",
        lambda **kwargs: client_type(transport=transport, event_hooks={"response": [rewrite_response]}, **kwargs),
    )
    with pytest.raises(ValueError, match="URL 与白名单目标不一致"):
        await module.WebSearchTool()._fetch_allowlisted_official_source(SOURCE, 1)


def test_unrepresentable_local_timestamp_does_not_break_search_ranking():
    assert module.WebSearchTool()._published_timestamp("0001-01-01") == 0.0
