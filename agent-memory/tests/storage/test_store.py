import time

import pytest

from app.store import owner_key


def test_sdk_lifecycle_and_isolation(memory_store):
    store = memory_store
    owner = {"tenant_id": "a", "operator_id": "u"}
    item = store.add("long_term", "目标城市上海", **owner)
    assert item["id"].startswith("mem_")
    assert store.search("目标城市上海", "long_term", **owner)[0]["id"] == item["id"]
    assert store.search("目标城市上海", **dict(owner, operator_id="other")) == []
    assert store.search("目标城市上海", **dict(owner, tenant_id="other")) == []
    for other in [dict(owner, operator_id="other"), dict(owner, tenant_id="other")]:
        assert store.update(item["id"], "wrong", **other) is None
        assert store.rollback(item["id"], **other) is None
        assert not store.delete(item["id"], **other)
    store.update(item["id"], "目标城市杭州", **owner)
    store.update(item["id"], "目标城市北京", **owner)
    assert store.rollback(item["id"], **owner)["content"] == "目标城市杭州"
    assert store.rollback(item["id"], **owner)["content"] == "目标城市上海"
    assert store.rollback(item["id"], **owner) is None
    assert store.delete(item["id"], **owner)
    assert not store.list_items(**owner)


def test_filter_before_top_k_and_purge_owner(memory_store, monkeypatch):
    store = memory_store
    monkeypatch.setenv("AGENT_MEMORY_SEARCH_TOP_K", "1")
    valid = store.add("long_term", "有效记忆")
    disabled = store.add("long_term", "禁用记忆", enabled=False)
    expired = store.add("long_term", "过期记忆", ttl_seconds=1)
    store.add("long_term", "别人的过期记忆", ttl_seconds=1, operator_id="other")
    future = time.time() + 2
    monkeypatch.setattr("app.store.time.time", lambda: future)
    assert store.search("禁用记忆")[0]["id"] == valid["id"]
    assert store.search("记忆", "session") == []
    assert store.update(expired["id"], "resurrect") is None
    assert store.purge_expired() == 1
    assert store.purge_expired(operator_id="other") == 1
    assert len(store.list_items()) == 2
    assert store.delete(disabled["id"])
    assert store.clear() == 1


def test_native_history_erased_on_delete(memory_store):
    item = memory_store.add("long_term", "private")
    raw = memory_store.memory.get_all(filters={"user_id": owner_key("default-tenant", "anonymous")})["results"][0]
    assert memory_store.memory.history(raw["id"])
    memory_store.delete(item["id"])
    assert memory_store.memory.history(raw["id"]) == []


def test_model_failure_is_not_success(memory_store, monkeypatch):
    def fail(*args, **kwargs):
        raise TimeoutError("provider timeout")

    monkeypatch.setattr(memory_store.memory.embedding_model, "embed", fail)
    with pytest.raises(TimeoutError):
        memory_store.add("long_term", "data")
    with pytest.raises(TimeoutError):
        memory_store.search("data")


def test_identity_key_has_unambiguous_boundaries():
    assert owner_key("a:b", "c") != owner_key("a", "b:c")
