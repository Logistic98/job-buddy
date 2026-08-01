import pytest


@pytest.fixture(autouse=True)
def disable_external_retrieval_models(monkeypatch):
    """单测默认禁止访问真实模型服务，需要网络 mock 的用例自行显式开启。"""
    monkeypatch.setenv("AGENT_MEMORY_EMBEDDING_ENABLED", "false")
    monkeypatch.setenv("AGENT_MEMORY_RERANK_ENABLED", "false")
