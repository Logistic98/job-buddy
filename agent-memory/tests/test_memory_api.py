import os

from fastapi.testclient import TestClient

import app.api as server
from app.store import MemoryStore


def make_client(monkeypatch) -> TestClient:
    monkeypatch.setattr(server, "local_store", MemoryStore())
    monkeypatch.setattr(server.postgres_store, "dsn", "")
    monkeypatch.setattr(server.adapter, "enabled", False)
    token = os.getenv("AGENT_INTERNAL_SERVICE_TOKEN", "").strip()
    headers = {"X-Internal-Service-Token": token} if token else None
    return TestClient(server.app, headers=headers)


def test_create_and_search_via_local_backend(monkeypatch):
    client = make_client(monkeypatch)
    created = client.post("/v1/memories", json={"scope": "session", "content": "候选人偏好远程办公"}).json()
    assert created["code"] == 200
    assert created["data"]["id"].startswith("mem_")

    found = client.get("/v1/memories/search", params={"q": "远程", "scope": "session"}).json()
    assert found["code"] == 200
    assert len(found["data"]) == 1


def test_list_and_clear_long_term_memories(monkeypatch):
    client = make_client(monkeypatch)
    headers = {"X-Tenant-Id": "tenant-a", "X-Operator-Id": "user-a"}
    created = client.post(
        "/v1/memories",
        json={
            "scope": "long_term",
            "kind": "long_term",
            "category": "constraint",
            "source": "manual",
            "content": "排除外包岗位",
        },
        headers=headers,
    ).json()["data"]

    assert created["category"] == "constraint"
    assert created["source"] == "manual"
    listed = client.get("/v1/memories", params={"scope": "long_term"}, headers=headers).json()["data"]
    assert [item["id"] for item in listed] == [created["id"]]
    assert client.get("/v1/memories", headers={"X-Operator-Id": "user-b"}).json()["data"] == []

    cleared = client.delete("/v1/memories", params={"scope": "long_term"}, headers=headers).json()
    assert cleared["data"]["deleted"] == 1
    assert client.get("/v1/memories", headers=headers).json()["data"] == []


def test_update_memory_changes_content(monkeypatch):
    client = make_client(monkeypatch)
    memory_id = client.post("/v1/memories", json={"content": "目标城市上海"}).json()["data"]["id"]

    updated = client.put(f"/v1/memories/{memory_id}", json={"content": "目标城市杭州"}).json()
    assert updated["code"] == 200
    assert updated["data"]["content"] == "目标城市杭州"
    assert updated["data"]["updated_at"] is not None

    found = client.get("/v1/memories/search", params={"q": "杭州"}).json()
    assert len(found["data"]) == 1


def test_update_missing_memory_returns_error_code(monkeypatch):
    client = make_client(monkeypatch)
    result = client.put("/v1/memories/mem_missing", json={"content": "x"}).json()
    assert result["code"] == 1
    assert "not found" in result["message"]


def test_delete_memory(monkeypatch):
    client = make_client(monkeypatch)
    memory_id = client.post("/v1/memories", json={"content": "临时记录"}).json()["data"]["id"]

    deleted = client.delete(f"/v1/memories/{memory_id}").json()
    assert deleted["code"] == 200
    assert deleted["data"]["deleted"] is True
    assert client.delete(f"/v1/memories/{memory_id}").json()["code"] == 1


def test_rollback_restores_previous_content(monkeypatch):
    client = make_client(monkeypatch)
    memory_id = client.post("/v1/memories", json={"content": "目标城市上海"}).json()["data"]["id"]
    client.put(f"/v1/memories/{memory_id}", json={"content": "目标城市杭州"})

    rolled = client.post(f"/v1/memories/{memory_id}/rollback").json()
    assert rolled["code"] == 200
    assert rolled["data"]["content"] == "目标城市上海"

    # 已无更早版本可回滚。
    assert client.post(f"/v1/memories/{memory_id}/rollback").json()["code"] == 1


def test_rollback_without_history_returns_error(monkeypatch):
    client = make_client(monkeypatch)
    memory_id = client.post("/v1/memories", json={"content": "仅一次写入"}).json()["data"]["id"]
    assert client.post(f"/v1/memories/{memory_id}/rollback").json()["code"] == 1


def test_create_persists_operator_and_kind(monkeypatch):
    client = make_client(monkeypatch)
    created = client.post(
        "/v1/memories",
        json={"content": "稳定偏好：远程优先", "kind": "long_term"},
        headers={"X-Operator-Id": "user-42"},
    ).json()
    assert created["code"] == 200
    assert created["data"]["operator_id"] == "user-42"
    assert created["data"]["kind"] == "long_term"


def test_body_operator_id_cannot_impersonate_memory_owner(monkeypatch):
    client = make_client(monkeypatch)

    created = client.post(
        "/v1/memories",
        json={"content": "尝试冒充用户", "operator_id": "victim-user"},
    ).json()

    assert created["code"] == 200
    assert created["data"]["operator_id"] == "anonymous"


def test_memory_owner_isolation_blocks_cross_user_access(monkeypatch):
    client = make_client(monkeypatch)
    owner_headers = {"X-Tenant-Id": "tenant-a", "X-Operator-Id": "user-a"}
    attacker_headers = {"X-Tenant-Id": "tenant-a", "X-Operator-Id": "user-b"}
    other_tenant_headers = {"X-Tenant-Id": "tenant-b", "X-Operator-Id": "user-a"}
    memory_id = client.post(
        "/v1/memories", json={"scope": "session", "content": "用户 A 私有偏好"}, headers=owner_headers
    ).json()["data"]["id"]

    assert client.get("/v1/memories/search", params={"q": "私有"}, headers=owner_headers).json()["data"]
    assert client.get("/v1/memories/search", params={"q": "私有"}, headers=attacker_headers).json()["data"] == []
    assert client.get("/v1/memories/search", params={"q": "私有"}, headers=other_tenant_headers).json()["data"] == []
    assert (
        client.put(f"/v1/memories/{memory_id}", json={"content": "越权修改"}, headers=attacker_headers).json()["code"]
        == 1
    )
    assert client.post(f"/v1/memories/{memory_id}/rollback", headers=attacker_headers).json()["code"] == 1
    assert client.delete(f"/v1/memories/{memory_id}", headers=attacker_headers).json()["code"] == 1
    assert client.delete(f"/v1/memories/{memory_id}", headers=owner_headers).json()["code"] == 200


def test_invalid_kind_falls_back_to_task(monkeypatch):
    client = make_client(monkeypatch)
    created = client.post("/v1/memories", json={"content": "x", "kind": "bogus"}).json()
    assert created["data"]["kind"] == "task"


def test_expired_memory_excluded_and_purged(monkeypatch):
    client = make_client(monkeypatch)
    memory_id = client.post("/v1/memories", json={"content": "短期记忆", "ttl_seconds": 60}).json()["data"]["id"]

    store = server.local_store
    item = next(it for it in store.items if it.id == memory_id)
    item.expires_at = "2000-01-01T00:00:00+00:00"

    found = client.get("/v1/memories/search", params={"q": "短期"}).json()
    assert found["data"] == []

    client.post("/v1/memories", json={"content": "短期记忆2", "ttl_seconds": 60})
    purged = client.post("/v1/memories/purge-expired").json()
    assert purged["code"] == 200
    assert purged["data"]["purged"] == 0
