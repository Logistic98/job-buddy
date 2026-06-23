from app.store import MemoryStore, PostgresMemoryStore


def test_add_and_search_memory():
    store = MemoryStore()
    item = store.add("session", "Agent Loop needs trace")
    assert item.id.startswith("mem_")
    assert store.search("trace", "session")[0].content == "Agent Loop needs trace"


def test_postgres_store_derives_dsn_from_spring_datasource(monkeypatch):
    monkeypatch.delenv("AGENT_MEMORY_DATABASE_URL", raising=False)
    monkeypatch.delenv("DATABASE_URL", raising=False)
    monkeypatch.setenv("SPRING_DATASOURCE_URL", "jdbc:postgresql://127.0.0.1:5432/job_buddy")
    monkeypatch.setenv("SPRING_DATASOURCE_USERNAME", "job_buddy")
    monkeypatch.setenv("SPRING_DATASOURCE_PASSWORD", "")

    store = PostgresMemoryStore()

    assert store.dsn == "postgresql://job_buddy@127.0.0.1:5432/job_buddy"
