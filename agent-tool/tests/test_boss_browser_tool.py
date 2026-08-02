from app.tools.boss_browser import tool as boss_tool
from app.tools.boss_browser.tool import run_boss_browser


def test_boss_browser_rate_operation_returns_envelope():
    result = run_boss_browser(
        {"operation": "rate", "payload": {"_trusted_owner_key": "tenant-a\u0000user-a"}},
        trace_id="boss_rate_test",
    )

    assert result.status == "success"
    assert result.trace_id == "boss_rate_test"
    assert result.data["code"] == 200
    assert result.data["message"] == "success"
    assert "search_used_hour" in result.data["data"]
    assert "search_limit_hour" in result.data["data"]
    assert "cooldown_active" in result.data["data"]


def test_boss_browser_rejects_unknown_operation():
    result = run_boss_browser({"operation": "bad", "payload": {}}, trace_id="boss_bad_test")

    assert result.status == "error"
    assert result.error.code == "invalid_arguments"


def test_boss_browser_rejects_non_object_payload():
    result = run_boss_browser({"operation": "rate", "payload": []}, trace_id="boss_payload_test")

    assert result.status == "error"
    assert result.error.code == "invalid_arguments"


def test_successful_search_returns_changed_credential_for_backend_persistence(monkeypatch):
    class _FakeService:
        def load_credential_json(self, credential_json):
            self.injected = credential_json

        async def search(self, **_kwargs):
            return []

        def credential_json(self):
            return '{"cookies":{"wt2":"identity","__zp_stoken__":"fresh"}}'

    service = _FakeService()
    monkeypatch.setattr(boss_tool, "get_service", lambda _owner_key: service)

    result = run_boss_browser(
        {
            "operation": "search",
            "payload": {
                "_trusted_owner_key": "tenant-a\u0000user-a",
                "credential_json": '{"cookies":{"wt2":"identity","__zp_stoken__":"expired"}}',
                "query": "大模型应用开发",
            },
        },
        trace_id="boss_refresh_persist_test",
    )

    assert result.status == "success"
    assert result.data["data"]["credential_json"] == ('{"cookies":{"wt2":"identity","__zp_stoken__":"fresh"}}')
