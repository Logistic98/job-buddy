"""配置、SDK 启停和异常契约；不连接部署数据库或模型服务。"""

import os
from types import SimpleNamespace
from unittest.mock import Mock

import pytest
from fastapi.testclient import TestClient

from app import api, env, store


def test_database_url_prefers_explicit_and_escapes_fallback_credentials(monkeypatch):
    monkeypatch.setenv("AGENT_MEMORY_DATABASE_URL", "postgresql://explicit/test")
    assert store.database_url() == "postgresql://explicit/test"
    monkeypatch.delenv("AGENT_MEMORY_DATABASE_URL")
    monkeypatch.setenv("SPRING_DATASOURCE_URL", "jdbc:postgresql://db.invalid/test")
    monkeypatch.setenv("SPRING_DATASOURCE_USERNAME", "test@user")
    monkeypatch.setenv("SPRING_DATASOURCE_PASSWORD", "synthetic:/password")
    assert store.database_url() == "postgresql://test%40user:synthetic%3A%2Fpassword@db.invalid/test"
    monkeypatch.delenv("SPRING_DATASOURCE_URL")
    with pytest.raises(ValueError, match="DATABASE_URL"):
        store.database_url()


@pytest.mark.parametrize("value", ["", "change-me", "sk-xxx"])
def test_required_configuration_rejects_missing_and_placeholder_values(monkeypatch, value):
    monkeypatch.setenv("TEST_MEMORY_CONFIG", value)
    with pytest.raises(ValueError, match="TEST_MEMORY_CONFIG"):
        store.required("TEST_MEMORY_CONFIG")
    monkeypatch.setenv("TEST_MEMORY_FALLBACK", "configured")
    monkeypatch.setenv("TEST_MEMORY_CONFIG", " ")
    assert store.required("TEST_MEMORY_CONFIG", "TEST_MEMORY_FALLBACK") == "configured"


@pytest.mark.parametrize("provider", ["pgvector", "qdrant"])
def test_build_memory_preserves_configuration_and_bounded_client_timeouts(monkeypatch, tmp_path, provider):
    import mem0

    for name, value in {
        "DATABASE_URL": "postgresql://test:synthetic@db.invalid/memory",
        "EMBEDDING_DIMS": "32",
        "EMBEDDING_API_KEY": "synthetic",
        "EMBEDDING_MODEL": "test-embedding",
        "EMBEDDING_BASE_URL": "https://model.invalid/v1/embeddings",
        "LLM_API_KEY": "synthetic",
        "LLM_MODEL": "test-llm",
        "LLM_BASE_URL": "https://model.invalid/v1",
        "EMBEDDING_TIMEOUT_SECONDS": "2",
        "LLM_TIMEOUT_SECONDS": "3",
        "DB_CMD_TIMEOUT": "1.5",
    }.items():
        monkeypatch.setenv("AGENT_MEMORY_" + name, value)
    monkeypatch.setenv("MEM0_DIR", str(tmp_path))
    monkeypatch.setenv("MEM0_TELEMETRY", "false")
    memory = Mock()
    embedding_client, llm_client = memory.embedding_model.client, memory.llm.client
    factory = Mock(return_value=memory)
    monkeypatch.setattr(mem0.Memory, "from_config", factory)
    vector = None if provider == "pgvector" else {"provider": "qdrant", "config": {"path": ":memory:"}}
    assert store.build_memory(vector_config=vector, history_path=tmp_path / "history.db") is memory
    config = factory.call_args.args[0]
    assert config["vector_store"]["provider"] == provider
    assert config["embedder"]["config"]["openai_base_url"] == "https://model.invalid/v1"
    assert config["llm"]["config"]["temperature"] == 0
    if provider == "pgvector":
        assert "statement_timeout=1500" in config["vector_store"]["config"]["connection_string"]
    embedding_client.with_options.assert_called_once_with(timeout=2, max_retries=0)
    llm_client.with_options.assert_called_once_with(timeout=3, max_retries=0)


def test_lifespan_initializes_and_closes_store_even_when_request_fails(monkeypatch):
    memory = Mock()
    instance = Mock()
    instance.list_items.side_effect = RuntimeError("private password")
    monkeypatch.setattr(api, "build_memory", lambda: memory)
    constructor = Mock(return_value=instance)
    monkeypatch.setattr(api, "MemoryStore", constructor)
    monkeypatch.setattr(api, "store", None)
    with TestClient(
        api.app, headers={"X-Internal-Service-Token": os.getenv("AGENT_INTERNAL_SERVICE_TOKEN", "").strip()}
    ) as client:
        assert client.get("/health").status_code == 200
        response = client.get("/v1/memories")
        assert response.status_code == 503
        assert response.json() == {"code": 503, "message": "Mem0 operation unavailable", "data": None}
    constructor.assert_called_once_with(memory)
    instance.close.assert_called_once()
    assert api.store is None
    with TestClient(api.app, raise_server_exceptions=False) as client:
        monkeypatch.setattr(api, "store", None)
        assert client.get("/health").status_code == 503
        monkeypatch.setattr(api, "store", instance)


def test_dotenv_missing_file_quotes_and_environment_precedence(monkeypatch, tmp_path):
    monkeypatch.setattr(env, "__file__", str(tmp_path / "module" / "app" / "env.py"))
    env.load_root_dotenv()
    monkeypatch.setenv("TEST_MEMORY_EXISTING", "process")
    monkeypatch.delenv("TEST_MEMORY_QUOTED", raising=False)
    monkeypatch.delenv("TEST_MEMORY_RAW", raising=False)
    (tmp_path / ".env").write_text(
        "# ignored\n\ninvalid\n=empty\nTEST_MEMORY_EXISTING=file\nTEST_MEMORY_QUOTED='text'\nTEST_MEMORY_RAW=plain\n"
    )
    env.load_root_dotenv()
    assert env.os.environ["TEST_MEMORY_EXISTING"] == "process"
    assert env.os.environ["TEST_MEMORY_QUOTED"] == "text"
    assert env.os.environ["TEST_MEMORY_RAW"] == "plain"


@pytest.mark.parametrize("method", ["close", "closeall"])
def test_close_releases_native_postgres_pool(method):
    close = Mock()
    memory = SimpleNamespace(
        db=Mock(), close=Mock(), vector_store=SimpleNamespace(connection_pool=SimpleNamespace(**{method: close}))
    )
    adapter = store.MemoryStore(memory)
    adapter.close()
    memory.close.assert_called_once()
    close.assert_called_once()


def test_sdk_acknowledgement_and_missing_rollback_history_fail_closed(memory_store, monkeypatch):
    with monkeypatch.context() as patch:
        patch.setattr(memory_store.memory, "add", lambda *args, **kwargs: {"results": []})
        with pytest.raises(RuntimeError, match="exactly one"):
            memory_store.add("session", "fact")
    item = memory_store.add("session", "original")
    assert memory_store.search(" ") == []
    updated = memory_store.update(item["id"], "updated", ttl_seconds=60)
    assert updated["expires_at"] is not None
    monkeypatch.setattr(memory_store.memory, "history", lambda _: [])
    with pytest.raises(RuntimeError, match="revision unavailable"):
        memory_store.rollback(item["id"])
    assert memory_store.delete("mem_invalid") is False


def test_readiness_checks_vector_store_and_history(memory_store, monkeypatch):
    memory_store.ensure_ready()
    history = Mock(side_effect=RuntimeError("history unavailable"))
    monkeypatch.setattr(memory_store.memory, "history", history)
    with pytest.raises(RuntimeError, match="history unavailable"):
        memory_store.ensure_ready()
    history.assert_called_once_with("__health_probe__")
