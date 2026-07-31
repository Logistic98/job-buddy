import pytest

from app.core.tool.base import ToolExecutionContext
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


def test_web_search_tool_parses_duckduckgo_html_results():
    html = """
    <a rel="nofollow" class="result__a" href="//duckduckgo.com/l/?uddg=https%3A%2F%2Fexample.com%2Fjob">Agent 面试指南</a>
    <a class="result__snippet">围绕 RAG、Tool Calling 和 Agent 工程准备面试。</a>
    """
    tool = WebSearchTool()

    rows = tool._parse_results(html, 3)

    assert rows == [
        {
            "title": "Agent 面试指南",
            "url": "https://example.com/job",
            "snippet": "围绕 RAG、Tool Calling 和 Agent 工程准备面试。",
        }
    ]


def test_web_search_expands_latest_query_with_current_year_and_official_signal():
    tool = WebSearchTool()

    queries = tool._expand_queries("OpenAI 最新模型 发布 2026", current_year=2026)

    assert queries == [
        "OpenAI 最新模型 发布 2026",
        "site:openai.com OpenAI 最新模型 发布 2026",
    ]


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
            "url": "https://openai.com/index/introducing-gpt-5/",
            "snippet": "OpenAI official model release.",
            "published_date": "2026-07-30",
            "site_name": "OpenAI",
        },
    ]

    ranked = tool._rank_results("OpenAI 最新模型 发布 2026", rows, 5)

    assert [row["url"] for row in ranked] == [
        "https://openai.com/index/introducing-gpt-5/",
        "https://news.example.com/openai-image?from=feed",
    ]


@pytest.mark.asyncio
async def test_web_search_executes_bounded_expansion_and_reports_auditable_counts(monkeypatch):
    tool = WebSearchTool()
    calls = []

    async def fake_search(query, limit, timeout, freshness, search_type):
        calls.append(query)
        if query.startswith("site:openai.com "):
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

    result = await tool._run(
        {"query": "OpenAI 最新模型 发布 2026", "max_results": 5},
        ToolExecutionContext(run_id="run-1", trace_id="trace-1", session_id="session-1"),
    )

    assert calls == [
        "OpenAI 最新模型 发布 2026",
        "site:openai.com OpenAI 最新模型 发布 2026",
    ]
    assert result["queries"] == calls
    assert result["raw_count"] == 2
    assert result["deduplicated_count"] == 2
    assert result["preferred_source_domains"] == ["openai.com"]
    assert result["preferred_source_found"] is True
    assert result["results"][0]["url"] == "https://openai.com/index/introducing-gpt-5/"


@pytest.mark.asyncio
async def test_web_search_marks_missing_preferred_source_and_warns_against_unverified_claims(monkeypatch):
    tool = WebSearchTool()

    async def fake_search(query, limit, timeout, freshness, search_type):
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
    monkeypatch.setattr(
        tool,
        "_search_duckduckgo",
        lambda query, limit, timeout: _async_value({"query": query, "source": "duckduckgo_html", "results": []}),
    )

    result = await tool._run(
        {"query": "OpenAI 最新模型 官方来源", "max_results": 5},
        ToolExecutionContext(run_id="run-1", trace_id="trace-1", session_id="session-1"),
    )

    assert result["preferred_source_domains"] == ["openai.com"]
    assert result["preferred_source_found"] is False
    assert any("不能视为官方确认" in warning for warning in result["warnings"])


@pytest.mark.asyncio
async def test_web_search_uses_provider_fallback_when_bocha_misses_preferred_source(monkeypatch):
    tool = WebSearchTool()
    duck_queries = []

    async def fake_bocha(query, limit, timeout, freshness, search_type):
        return {
            "query": query,
            "source": "bocha_web",
            "results": [
                {
                    "title": "媒体称 OpenAI 发布新模型",
                    "url": "https://news.example.com/openai-model",
                    "snippet": "第三方报道。",
                    "published_date": "2026-07-29",
                    "site_name": "Example News",
                }
            ],
        }

    async def fake_duckduckgo(query, limit, timeout):
        duck_queries.append(query)
        return {
            "query": query,
            "source": "duckduckgo_html",
            "results": [
                {
                    "title": "Models | OpenAI API",
                    "url": "https://platform.openai.com/docs/models",
                    "snippet": "Explore available OpenAI models.",
                }
            ],
        }

    monkeypatch.setattr(tool, "_search_bocha", fake_bocha)
    monkeypatch.setattr(tool, "_search_duckduckgo", fake_duckduckgo)

    result = await tool._run(
        {"query": "OpenAI 最新模型", "max_results": 5},
        ToolExecutionContext(run_id="run-1", trace_id="trace-1", session_id="session-1"),
    )

    assert duck_queries == ["site:openai.com OpenAI 最新模型 2026"]
    assert result["source"] == "bocha_web+duckduckgo_html"
    assert result["preferred_source_found"] is True
    assert result["results"][0]["url"] == "https://platform.openai.com/docs/models"


async def _async_value(value):
    return value
