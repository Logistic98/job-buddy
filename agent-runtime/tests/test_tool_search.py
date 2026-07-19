import pytest

from app.core.tool.search import ToolSearchService


@pytest.mark.asyncio
async def test_search_by_name_keyword_prioritizes_name_match(fresh_registry):
    service = ToolSearchService(fresh_registry)
    results = await service.search("echo")
    assert results
    assert results[0].name == "echo"


@pytest.mark.asyncio
async def test_search_by_chinese_hint(fresh_registry):
    service = ToolSearchService(fresh_registry)
    results = await service.search("回显")
    assert any(item.name == "echo" for item in results)


@pytest.mark.asyncio
async def test_search_empty_query_returns_limited_list(fresh_registry):
    service = ToolSearchService(fresh_registry)
    results = await service.search("", limit=3)
    assert len(results) == 3


@pytest.mark.asyncio
async def test_search_unknown_query_falls_back(fresh_registry):
    service = ToolSearchService(fresh_registry)
    results = await service.search("zzz_no_match_term", limit=4)
    assert results
    assert len(results) <= 5
