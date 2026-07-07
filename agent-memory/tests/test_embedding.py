from unittest.mock import AsyncMock, MagicMock, patch

from app.embedding import EmbeddingClient
from app.store import MemoryStore


def _enable(monkeypatch, base_url="http://localhost:9100/v1/embeddings"):
    monkeypatch.setenv("AGENT_MEMORY_EMBEDDING_ENABLED", "true")
    monkeypatch.setenv("AGENT_MEMORY_EMBEDDING_BASE_URL", base_url)
    monkeypatch.setenv("AGENT_MEMORY_EMBEDDING_MODEL", "bge-m3")


def _mock_http(vectors_by_call):
    """构造 httpx.AsyncClient 的 mock，按调用顺序返回 embeddings 响应。"""
    client = MagicMock()
    responses = []
    for vectors in vectors_by_call:
        response = MagicMock()
        response.raise_for_status = MagicMock()
        response.json.return_value = {"data": [{"index": i, "embedding": vector} for i, vector in enumerate(vectors)]}
        responses.append(response)
    client.post = AsyncMock(side_effect=responses)
    context = MagicMock()
    context.__aenter__ = AsyncMock(return_value=client)
    context.__aexit__ = AsyncMock(return_value=False)
    return context, client


async def test_disabled_by_default_returns_none_without_network(monkeypatch):
    monkeypatch.delenv("AGENT_MEMORY_EMBEDDING_ENABLED", raising=False)
    client = EmbeddingClient()
    assert client.enabled is False
    with patch("app.embedding.httpx.AsyncClient") as http:
        assert await client.embed(["文本"]) is None
        http.assert_not_called()


async def test_enabled_requires_base_url(monkeypatch):
    monkeypatch.setenv("AGENT_MEMORY_EMBEDDING_ENABLED", "true")
    monkeypatch.delenv("AGENT_MEMORY_EMBEDDING_BASE_URL", raising=False)
    assert EmbeddingClient().enabled is False


async def test_embed_sends_openai_payload_and_parses_by_index(monkeypatch):
    _enable(monkeypatch)
    client = EmbeddingClient()
    context, http_client = _mock_http([[[1.0, 0.0], [0.0, 1.0]]])
    with patch("app.embedding.httpx.AsyncClient", return_value=context):
        vectors = await client.embed(["查询", "文档"])
    assert vectors == [[1.0, 0.0], [0.0, 1.0]]
    kwargs = http_client.post.call_args.kwargs
    assert kwargs["json"] == {"model": "bge-m3", "input": ["查询", "文档"]}


async def test_embed_failure_returns_none(monkeypatch):
    _enable(monkeypatch)
    client = EmbeddingClient()
    with patch("app.embedding.httpx.AsyncClient", side_effect=RuntimeError("service down")):
        assert await client.embed(["查询"]) is None


async def test_embed_count_mismatch_returns_none(monkeypatch):
    _enable(monkeypatch)
    client = EmbeddingClient()
    context, _ = _mock_http([[[1.0, 0.0]]])
    with patch("app.embedding.httpx.AsyncClient", return_value=context):
        assert await client.embed(["查询", "文档"]) is None


async def test_embed_uses_content_cache(monkeypatch):
    _enable(monkeypatch)
    client = EmbeddingClient()
    context, http_client = _mock_http([[[1.0, 0.0]], [[0.0, 1.0]]])
    with patch("app.embedding.httpx.AsyncClient", return_value=context):
        first = await client.embed(["同一段内容"])
        second = await client.embed(["同一段内容", "新内容"])
    assert first == [[1.0, 0.0]]
    assert second == [[1.0, 0.0], [0.0, 1.0]]
    # 第二次调用只补拉未缓存的"新内容"
    assert http_client.post.call_count == 2
    assert http_client.post.call_args.kwargs["json"]["input"] == ["新内容"]


async def test_store_search_degrades_when_embedding_fails(monkeypatch):
    _enable(monkeypatch)
    store = MemoryStore()
    store.add("session", "Java 后端 微服务经验")
    with patch("app.embedding.httpx.AsyncClient", side_effect=RuntimeError("service down")):
        results = await store.search("Java 后端", "session")
    assert results and "Java" in results[0].content
