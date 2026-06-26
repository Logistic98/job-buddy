from app.tools.boss_browser.tool import run_boss_browser


def test_boss_browser_rate_operation_returns_envelope():
    result = run_boss_browser({"operation": "rate", "payload": {}}, trace_id="boss_rate_test")

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
