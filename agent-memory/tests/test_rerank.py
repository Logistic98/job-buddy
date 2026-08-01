from unittest.mock import AsyncMock, MagicMock, patch

from app.rerank import RerankClient
from app.store import MemoryItem, hybrid_rank


def _enable(monkeypatch, base_url="http://localhost:9100/v1/rerank"):
    monkeypatch.setenv("AGENT_MEMORY_RERANK_ENABLED", "true")
    monkeypatch.setenv("AGENT_MEMORY_RERANK_BASE_URL", base_url)
    monkeypatch.setenv("AGENT_MEMORY_RERANK_API_KEY", "test-key")
    monkeypatch.setenv("AGENT_MEMORY_RERANK_MODEL", "bge-reranker-v2-m3")


def _mock_http(results):
    client = MagicMock()
    response = MagicMock()
    response.raise_for_status = MagicMock()
    response.json.return_value = {"results": results}
    client.post = AsyncMock(return_value=response)
    context = MagicMock()
    context.__aenter__ = AsyncMock(return_value=client)
    context.__aexit__ = AsyncMock(return_value=False)
    return context, client


async def test_disabled_by_default_returns_none_without_network(monkeypatch):
    monkeypatch.delenv("AGENT_MEMORY_RERANK_ENABLED", raising=False)
    client = RerankClient()

    with patch("app.rerank.httpx.AsyncClient") as http:
        assert await client.rerank("查询", ["文档"], top_n=1) is None
        http.assert_not_called()


async def test_enabled_requires_base_url_and_model(monkeypatch):
    monkeypatch.setenv("AGENT_MEMORY_RERANK_ENABLED", "true")
    monkeypatch.delenv("AGENT_MEMORY_RERANK_BASE_URL", raising=False)
    monkeypatch.delenv("AGENT_MEMORY_RERANK_MODEL", raising=False)

    assert RerankClient().enabled is False


async def test_enabled_rejects_placeholder_key(monkeypatch):
    _enable(monkeypatch)
    monkeypatch.setenv("AGENT_MEMORY_RERANK_API_KEY", "sk-xxx")

    assert RerankClient().enabled is False


async def test_rerank_sends_payload_and_returns_ranked_indices(monkeypatch):
    _enable(monkeypatch)
    monkeypatch.setenv("AGENT_MEMORY_RERANK_API_KEY", "test-key")
    client = RerankClient()
    context, http_client = _mock_http(
        [
            {"index": 1, "relevance_score": 0.92},
            {"index": 0, "relevance_score": 0.31},
        ]
    )

    with patch("app.rerank.httpx.AsyncClient", return_value=context):
        indices = await client.rerank("推荐鲁迅小说", ["《边城》", "《呐喊》"], top_n=2)

    assert indices == [1, 0]
    assert http_client.post.call_args.kwargs["json"] == {
        "model": "bge-reranker-v2-m3",
        "query": "推荐鲁迅小说",
        "documents": ["《边城》", "《呐喊》"],
        "top_n": 2,
        "return_documents": False,
    }
    assert http_client.post.call_args.kwargs["headers"] == {"Authorization": "Bearer test-key"}


async def test_rerank_rejects_duplicate_indices(monkeypatch):
    _enable(monkeypatch)
    client = RerankClient()
    context, _ = _mock_http(
        [
            {"index": 1, "relevance_score": 0.92},
            {"index": 1, "relevance_score": 0.31},
        ]
    )

    with patch("app.rerank.httpx.AsyncClient", return_value=context):
        assert await client.rerank("查询", ["文档一", "文档二"], top_n=2) is None


async def test_rerank_rejects_out_of_range_index(monkeypatch):
    _enable(monkeypatch)
    client = RerankClient()
    context, _ = _mock_http([{"index": 2, "relevance_score": 0.92}])

    with patch("app.rerank.httpx.AsyncClient", return_value=context):
        assert await client.rerank("查询", ["文档一"], top_n=1) is None


async def test_rerank_count_mismatch_returns_none(monkeypatch):
    _enable(monkeypatch)
    client = RerankClient()
    context, _ = _mock_http([{"index": 1, "relevance_score": 0.92}])

    with patch("app.rerank.httpx.AsyncClient", return_value=context):
        assert await client.rerank("查询", ["文档一", "文档二"], top_n=2) is None


async def test_rerank_failure_returns_none(monkeypatch):
    _enable(monkeypatch)
    client = RerankClient()

    with patch("app.rerank.httpx.AsyncClient", side_effect=RuntimeError("service down")):
        assert await client.rerank("查询", ["文档"], top_n=1) is None


async def test_rerank_invalid_timeout_degrades_without_network(monkeypatch):
    _enable(monkeypatch)
    monkeypatch.setenv("AGENT_MEMORY_RERANK_TIMEOUT_SECONDS", "invalid")
    client = RerankClient()

    with patch("app.rerank.httpx.AsyncClient") as http:
        assert await client.rerank("查询", ["文档"], top_n=1) is None
        http.assert_not_called()


def _memory(item_id: str, content: str, created_at: str) -> MemoryItem:
    return MemoryItem(
        id=item_id,
        scope="long_term",
        content=content,
        created_at=created_at,
    )


async def test_hybrid_rank_applies_rerank_after_retrieval(monkeypatch):
    _enable(monkeypatch)
    monkeypatch.setenv("AGENT_MEMORY_SEARCH_TOP_K", "2")
    monkeypatch.setenv("AGENT_MEMORY_RERANK_CANDIDATES", "10")
    candidates = [
        _memory("high", "Java 后端 高并发 微服务 后端架构", "2026-07-01T00:00:00+00:00"),
        _memory("low", "Java 后端 培训课程", "2026-07-02T00:00:00+00:00"),
    ]

    with (
        patch("app.store.rank", return_value=candidates),
        patch("app.store._rerank_client.rerank", AsyncMock(return_value=[1, 0])) as rerank,
    ):
        ranked = await hybrid_rank("Java 后端", candidates)

    assert [item.id for item in ranked] == ["low", "high"]
    rerank.assert_awaited_once_with(
        "Java 后端",
        ["Java 后端 高并发 微服务 后端架构", "Java 后端 培训课程"],
        top_n=2,
    )


async def test_hybrid_rank_degrades_to_retrieval_order_when_rerank_fails(monkeypatch):
    _enable(monkeypatch)
    monkeypatch.setenv("AGENT_MEMORY_SEARCH_TOP_K", "2")
    candidates = [
        _memory("high", "Java 后端 高并发 微服务 后端架构", "2026-07-01T00:00:00+00:00"),
        _memory("low", "Java 后端 培训课程", "2026-07-02T00:00:00+00:00"),
    ]

    with (
        patch("app.store.rank", return_value=candidates),
        patch("app.store._rerank_client.rerank", AsyncMock(return_value=None)),
    ):
        ranked = await hybrid_rank("Java 后端", candidates)

    assert [item.id for item in ranked] == ["high", "low"]


async def test_hybrid_rank_uses_default_candidate_limit_when_config_is_invalid(monkeypatch):
    _enable(monkeypatch)
    monkeypatch.setenv("AGENT_MEMORY_SEARCH_TOP_K", "2")
    monkeypatch.setenv("AGENT_MEMORY_RERANK_CANDIDATES", "invalid")
    candidates = [
        _memory("high", "Java 后端 高并发 微服务 后端架构", "2026-07-01T00:00:00+00:00"),
        _memory("low", "Java 后端 培训课程", "2026-07-02T00:00:00+00:00"),
    ]

    with (
        patch("app.store.rank", return_value=candidates) as local_rank,
        patch("app.store._rerank_client.rerank", AsyncMock(return_value=[0, 1])),
    ):
        ranked = await hybrid_rank("Java 后端", candidates)

    assert [item.id for item in ranked] == ["high", "low"]
    assert local_rank.call_args.args[4] == 30
