
import httpx
import pytest

from app.core.common.settings import settings
from app.core.context.assembler import ContextAssembler
from app.core.memory import MemoryClient
from app.models.schemas import ChatMessage


class FakeResponse:
    def __init__(self, body):
        self._body = body

    def raise_for_status(self):
        return None

    def json(self):
        return self._body


@pytest.fixture
def memory_enabled(monkeypatch):
    monkeypatch.setattr(settings.config.memory, "enabled", True)


def test_memory_client_disabled_by_default(monkeypatch):
    def fail_get(*args, **kwargs):
        raise AssertionError("memory disabled 时不应发起 HTTP 请求")

    monkeypatch.setattr(httpx, "get", fail_get)
    assert MemoryClient().search("java 岗位偏好") == []


def test_memory_client_returns_normalized_refs(monkeypatch, memory_enabled):
    body = {
        "code": 0,
        "data": [
            {"id": "mem_1", "scope": "session", "content": "偏好 Java 后端岗位", "created_at": "2026-06-01"},
            {"id": "mem_2", "scope": "session", "content": "期望薪资 30k", "created_at": "2026-06-02"},
        ],
    }
    captured = {}

    def fake_get(url, params=None, timeout=None):
        captured["url"] = url
        captured["params"] = params
        return FakeResponse(body)

    monkeypatch.setattr(httpx, "get", fake_get)
    refs = MemoryClient().search("java", scope="session", trace_id="t1")
    assert len(refs) == 2
    assert refs[0]["source"] == "agent-memory"
    assert refs[0]["id"] == "mem_1"
    assert captured["url"].endswith("/v1/memories/search")
    assert captured["params"] == {"q": "java", "scope": "session"}


def test_memory_client_respects_top_k(monkeypatch, memory_enabled):
    monkeypatch.setattr(settings.config.memory, "top_k", 1)
    body = {"code": 0, "data": [{"id": f"mem_{i}", "content": "x"} for i in range(5)]}
    monkeypatch.setattr(httpx, "get", lambda *a, **kw: FakeResponse(body))
    assert len(MemoryClient().search("java")) == 1


def test_memory_client_degrades_on_connection_error(monkeypatch, memory_enabled):
    def raise_connect(*args, **kwargs):
        raise httpx.ConnectError("connection refused")

    monkeypatch.setattr(httpx, "get", raise_connect)
    assert MemoryClient().search("java") == []


def test_memory_client_skips_empty_query(monkeypatch, memory_enabled):
    def fail_get(*args, **kwargs):
        raise AssertionError("空查询不应发起 HTTP 请求")

    monkeypatch.setattr(httpx, "get", fail_get)
    assert MemoryClient().search("   ") == []


def test_assembler_injects_memory_refs_when_enabled(monkeypatch, memory_enabled):
    body = {"code": 0, "data": [{"id": "mem_1", "scope": "session", "content": "偏好 Java"}]}
    monkeypatch.setattr(httpx, "get", lambda *a, **kw: FakeResponse(body))
    result = ContextAssembler().assemble(
        messages=[ChatMessage(role="user", content="推荐 Java 岗位")],
        task=None,
        observations=[],
        tool_results=[],
        metadata={},
    )
    assert result["payload"]["memory_refs"][0]["id"] == "mem_1"
    assert result["metrics"]["memory_ref_count"] == 1


def test_assembler_omits_memory_refs_when_disabled():
    result = ContextAssembler().assemble(
        messages=[ChatMessage(role="user", content="推荐 Java 岗位")],
        task=None,
        observations=[],
        tool_results=[],
        metadata={},
    )
    assert "memory_refs" not in result["payload"]
    assert result["metrics"]["memory_ref_count"] == 0


def test_assembler_degrades_when_memory_unavailable(monkeypatch, memory_enabled):
    def raise_connect(*args, **kwargs):
        raise httpx.ConnectError("connection refused")

    monkeypatch.setattr(httpx, "get", raise_connect)
    result = ContextAssembler().assemble(
        messages=[ChatMessage(role="user", content="推荐 Java 岗位")],
        task=None,
        observations=[],
        tool_results=[],
        metadata={},
    )
    assert "memory_refs" not in result["payload"]
    assert result["metrics"]["memory_ref_count"] == 0
