"""真实引擎的异常归类与有界重试；仅替换上游 HTTP 客户端。"""

import asyncio
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock

import pytest

from app.tools.boss_browser.core.boss_cli_engine import BossCliEngine
from app.tools.boss_browser.core.settings import Settings


@pytest.fixture
def engine(monkeypatch):
    instance = BossCliEngine(Settings())
    instance._memory_credential = SimpleNamespace(
        cookies={"wt2": "synthetic", "zp_at": "synthetic", "wbg": "synthetic", "__zp_stoken__": "synthetic"}
    )
    client = MagicMock()
    client.__enter__.return_value = client
    monkeypatch.setattr(instance, "_client_cls", Mock(return_value=client))
    monkeypatch.setattr(instance, "_refresh_after_auth_failure", Mock(return_value=False))
    return instance, client


def invoke(engine, operation):
    if operation == "search":
        return asyncio.run(engine.search("Python"))
    if operation == "detail":
        return asyncio.run(engine.detail(security_id="job"))
    return asyncio.run(engine.favorite_jobs())


@pytest.mark.parametrize(
    ("operation", "method"), [("search", "search_jobs"), ("detail", "get_job_detail"), ("favorite", "_get")]
)
@pytest.mark.parametrize("failure", ["expired", "rate", "api", "network"])
def test_failures_keep_distinct_recovery_signals(engine, operation, method, failure):
    instance, client = engine
    error = {
        "expired": instance._SessionExpiredError(),
        "rate": instance._RateLimitError(),
        "api": instance._BossApiError("unavailable"),
        "network": OSError("offline"),
    }[failure]
    getattr(client, method).side_effect = error

    result = invoke(instance, operation)

    assert result["payload"] is None
    assert result["login_redirect"] == (failure == "expired")
    assert bool(result.get("rate_limited")) == (failure == "rate")
    assert result["risk_marker"] is None
    assert getattr(client, method).call_count == 1


@pytest.mark.parametrize(
    ("operation", "method"), [("search", "search_jobs"), ("detail", "get_job_detail"), ("favorite", "_get")]
)
@pytest.mark.parametrize("api_error", [False, True])
def test_authentication_retry_is_limited_to_one(engine, operation, method, api_error):
    instance, client = engine
    error = instance._BossApiError("session expired") if api_error else instance._SessionExpiredError()
    getattr(client, method).side_effect = error
    instance._refresh_after_auth_failure.return_value = True

    result = invoke(instance, operation)

    assert result["login_redirect"] is True
    assert getattr(client, method).call_count == 2
    instance._refresh_after_auth_failure.assert_called_once()


@pytest.mark.parametrize("failure", ["expired", "rate", "api", "network", "none"])
def test_profile_stops_after_failed_fetch_and_preserves_partial_capture(engine, failure):
    instance, client = engine
    client.get_resume_baseinfo.return_value = {"name": "synthetic"}
    client.get_resume_expect.return_value = None
    client.get_resume_status.return_value = {"status": "available"}
    if failure != "none":
        client.get_resume_expect.side_effect = {
            "expired": instance._SessionExpiredError(),
            "rate": instance._RateLimitError(),
            "api": instance._BossApiError("captcha"),
            "network": OSError("offline"),
        }[failure]

    result = asyncio.run(instance.profile())

    assert len(result["captures"]) == (0 if failure == "expired" else 2 if failure == "none" else 1)
    assert client.get_resume_status.call_count == int(failure == "none")
    if failure == "api":
        assert "captcha" in result["risk_marker"]


@pytest.mark.parametrize("operation", ["search", "detail", "favorite", "profile"])
def test_missing_credential_never_accesses_http(engine, operation):
    instance, client = engine
    instance.clear_credential()
    result = asyncio.run(instance.profile()) if operation == "profile" else invoke(instance, operation)
    assert result["login_redirect"] is True
    instance._client_cls.assert_not_called()


@pytest.mark.parametrize("raw", ["not json", "[]", "{}", '{"cookies":[]}'])
def test_malformed_injection_does_not_destroy_existing_credential(engine, raw):
    instance, _ = engine
    original = instance.credential_json()
    instance.load_credential_json(raw)
    assert instance.credential_json() == original


def test_qr_state_is_copied_and_can_be_cleared(engine):
    instance, _ = engine
    state = {"qr_id": "synthetic", "cookies": {"name": "value"}}
    instance.import_qr_state(state)
    state["cookies"]["name"] = "changed"
    exported = instance.export_qr_state()
    assert exported["cookies"]["name"] == "value"
    exported["cookies"].clear()
    assert instance.export_qr_state()["cookies"] == {"name": "value"}
    instance.clear_qr_state()
    assert instance.export_qr_state() == {}
    with pytest.raises(ValueError):
        instance.import_qr_state({})


def test_engine_dependency_and_lifecycle_boundaries(monkeypatch):
    import sys

    from app.tools.boss_browser.core.boss_cli_engine import BossCliUnavailable

    with monkeypatch.context() as patch:
        patch.setitem(sys.modules, "boss_cli", None)
        with pytest.raises(BossCliUnavailable, match="未安装"):
            BossCliEngine(Settings())
    instance = BossCliEngine(Settings())
    asyncio.run(instance.assert_browser_ready())
    assert asyncio.run(instance.aclose()) is None
    instance._auth = None
    with pytest.raises(BossCliUnavailable, match="初始化"):
        asyncio.run(instance.assert_browser_ready())


@pytest.mark.parametrize("mode", ["missing", "unverified", "verified", "error"])
def test_status_verification_is_explicit_and_fail_closed(engine, monkeypatch, mode):
    instance, _ = engine
    instance._settings.boss_cli.status_verify = mode != "unverified"
    verify = Mock(
        return_value={
            "authenticated": mode == "verified",
            "search_authenticated": True,
            "recommend_authenticated": True,
        }
    )
    monkeypatch.setattr(instance._auth, "verify_credential_details", verify)
    if mode == "missing":
        monkeypatch.setattr(instance, "_get_credential", Mock(side_effect=RuntimeError("unavailable")))
    elif mode == "error":
        verify.side_effect = RuntimeError("verification failed")
    result = asyncio.run(instance.status())
    assert result["authenticated"] == (mode in {"verified", "unverified"})
    if mode in {"missing", "unverified"}:
        verify.assert_not_called()


def test_status_failed_verification_marks_credential_degraded(engine, monkeypatch):
    instance, _ = engine
    instance._settings.boss_cli.status_verify = True
    monkeypatch.setattr(instance._auth, "verify_credential_details", lambda _: {"authenticated": False})
    assert instance._status_sync()["authenticated"] is False
    assert instance._auth_degraded is True


def test_refresh_auth_import_success_and_existing_credential_recovery(engine, monkeypatch):
    instance, _ = engine
    credential = instance._memory_credential
    monkeypatch.setattr(instance, "_import_browser_credential", lambda: credential)
    result = asyncio.run(instance.refresh_auth())
    assert result["refreshed"] is True
    monkeypatch.setattr(instance, "_import_browser_credential", lambda: None)
    instance._auth_degraded = True
    assert instance._refresh_auth_sync()["authenticated"] is True
    assert instance._auth_degraded is False


def test_incomplete_credential_is_not_used_for_requests(engine):
    instance, _ = engine
    instance._memory_credential = SimpleNamespace(cookies={"wt2": "identity"})
    assert instance._credential_or_none() is None


def test_city_and_filter_resolution_fallbacks(engine, monkeypatch):
    import boss_cli.client

    instance, _ = engine
    assert instance._resolve_city_code("101") == "101"
    monkeypatch.setattr(boss_cli.client, "resolve_city", lambda _: "resolved")
    assert instance._resolve_city_code("Unknown Test City") == "resolved"
    monkeypatch.setattr(boss_cli.client, "resolve_city", Mock(side_effect=ValueError("unknown")))
    assert instance._resolve_city_code("Unknown Test City") == "100010000"
    assert instance._resolve_filter(" ", {}) is None
    assert instance._resolve_filter("102", {"label": "102"}) == "102"
    assert instance._resolve_filter("unknown", {}) is None
    assert instance._extract_query_param("http://[invalid", "lid") == ""


@pytest.mark.parametrize("body", [{"code": 1, "message": "upstream failure"}, {"code": 0, "zpData": {}}])
def test_qr_start_rejects_upstream_failure_or_missing_identifier(engine, monkeypatch, body):
    instance, _ = engine
    client = MagicMock()
    client.__enter__.return_value = client
    client.post.return_value.json.return_value = body
    monkeypatch.setattr(instance, "_qr_client", lambda **kwargs: client)
    with pytest.raises(RuntimeError, match="二维码会话失败"):
        asyncio.run(instance.start_qr_login())


def test_qr_snapshot_requires_identity_and_handles_confirmed_and_expired(engine):
    instance, _ = engine
    assert instance.qr_snapshot({})["status"] == "auth_required"
    assert instance.qr_snapshot({"qr_id": "id", "expires_at": 0})["status"] == "qr_expired"
    assert instance.qr_snapshot({"qr_id": "id", "status": "confirmed", "expires_at": 0})["status"] == "qr_confirmed"
    instance._qr_state = {}
    assert asyncio.run(instance.poll_qr_login())["status"] == "auth_required"
    instance._qr_state = {"qr_id": "id", "expires_at": 0}
    assert asyncio.run(instance.poll_qr_login())["status"] == "qr_expired"


@pytest.mark.parametrize("method", ["_qr_scan", "_qr_confirm"])
def test_qr_poll_transport_timeout_remains_pending(engine, method):
    import httpx

    instance, _ = engine
    client = Mock()
    client.get.side_effect = httpx.ReadTimeout("timeout")
    assert getattr(instance, method)(client, "id") is False
    client.get.assert_called_once()


@pytest.mark.parametrize("warmup", ["ok", "failure", "empty"])
def test_qr_dispatch_merges_cookies_and_tolerates_warmup_failure(engine, warmup):
    import httpx

    instance, _ = engine
    client = Mock()
    dispatch = Mock(cookies={"wt2": "identity"} if warmup != "empty" else {})
    extra = Mock(cookies={"wbg": "web"} if warmup != "empty" else {})
    client.cookies = {"zp_at": "account"} if warmup != "empty" else {}
    client.get.side_effect = [dispatch, httpx.ConnectError("offline") if warmup == "failure" else extra]
    if warmup == "empty":
        with pytest.raises(RuntimeError, match="未返回 Cookie"):
            instance._qr_dispatch(client, "id")
    else:
        result = instance._qr_dispatch(client, "id")
        assert result.cookies["wt2"] == "identity"
        assert result.cookies["zp_at"] == "account"
        assert ("wbg" in result.cookies) == (warmup == "ok")


@pytest.mark.parametrize("mode", ["disabled", "failure", "unchanged", "updated"])
def test_qr_completion_retains_original_on_failure(engine, monkeypatch, mode):
    instance, _ = engine
    credential = instance._memory_credential
    instance._settings.boss_cli.headless_cookie_completion = mode != "disabled"
    complete = Mock(return_value={**credential.cookies, "new": "value"} if mode == "updated" else credential.cookies)
    if mode == "failure":
        complete.side_effect = RuntimeError("browser unavailable")
    monkeypatch.setattr(instance, "_run_headless_cookie_completion", complete)
    result = instance._complete_qr_credential(credential)
    assert (result is credential) == (mode != "updated")
    if mode == "disabled":
        complete.assert_not_called()


@pytest.mark.parametrize("outcome", ["success", "missing", "failure"])
def test_explicit_browser_import_fallback_is_bounded(monkeypatch, outcome):
    instance = BossCliEngine(Settings())
    instance._settings.boss_cli.auto_import_browser_cookies = True
    monkeypatch.setattr(instance, "_refresh_stoken_from_persisted", lambda: False)
    credential = SimpleNamespace(cookies={name: "synthetic" for name in ("wt2", "zp_at", "wbg", "__zp_stoken__")})
    extract = Mock(return_value=credential if outcome == "success" else None)
    if outcome == "failure":
        extract.side_effect = RuntimeError("unavailable")
    monkeypatch.setattr(instance._auth, "extract_browser_credential", extract)
    assert instance._refresh_after_auth_failure() == (outcome == "success")
    extract.assert_called_once()
    assert "浏览器" in instance._auth_required_message()


def test_disabled_headless_refresh_does_not_launch_browser(engine, monkeypatch):
    instance, _ = engine
    instance._settings.boss_cli.headless_cookie_completion = False
    complete = Mock()
    monkeypatch.setattr(instance, "_run_headless_cookie_completion", complete)
    assert instance._refresh_stoken_from_persisted() is False
    complete.assert_not_called()
    instance._settings.boss_cli.headless_cookie_completion = True
    complete.return_value = {"wt2": "identity", "zp_at": "account"}
    assert instance._refresh_stoken_from_persisted() is False


@pytest.mark.parametrize("operation", ["search", "favorite"])
def test_missing_credential_can_be_refreshed_before_request(engine, monkeypatch, operation):
    instance, client = engine
    credential = instance._memory_credential
    monkeypatch.setattr(instance, "_credential_or_none", Mock(side_effect=[None, credential]))
    instance._refresh_after_auth_failure.return_value = True
    client.search_jobs.return_value = {"code": 0}
    client._get.return_value = {"code": 0}
    assert invoke(instance, operation)["payload"] == {"code": 0}


def test_detail_without_identifier_is_rejected_locally(engine):
    instance, client = engine
    assert asyncio.run(instance.detail())["local_rejected"] is True
    client.get_job_detail.assert_not_called()


@pytest.mark.parametrize("phase", ["scanned", "confirmed"])
def test_qr_pending_phases_remain_retryable(engine, monkeypatch, phase):
    import httpx

    instance, _ = engine
    instance._qr_state = {"qr_id": "id", "status": phase, "expires_at": 0}
    client = MagicMock()
    client.__enter__.return_value = client
    client.cookies = {}
    monkeypatch.setattr(instance, "_qr_client", lambda **kwargs: client)
    monkeypatch.setattr(instance, "_qr_confirm", lambda *args: False)
    monkeypatch.setattr(instance, "_qr_dispatch", Mock(side_effect=httpx.ReadTimeout("timeout")))
    result = instance._qr_poll_sync()
    assert result["status"] == ("qr_waiting" if phase == "scanned" else "qr_confirmed")
    assert instance._qr_state["status"] == phase


def test_qr_incomplete_identity_is_terminal(engine, monkeypatch):
    instance, _ = engine
    instance._qr_state = {"qr_id": "id", "status": "confirmed", "expires_at": 0}
    client = MagicMock()
    client.__enter__.return_value = client
    monkeypatch.setattr(instance, "_qr_client", lambda **kwargs: client)
    credential = SimpleNamespace(cookies={"wbg": "web"})
    monkeypatch.setattr(instance, "_qr_dispatch", lambda *args: credential)
    monkeypatch.setattr(instance, "_complete_qr_credential", lambda cred: cred)
    result = instance._qr_poll_sync()
    assert result["status"] == "auth_required"
    assert instance._qr_state["status"] == "auth_required"
    assert "qr_id" not in instance._qr_state


def test_qr_client_has_bounded_long_poll_timeout(engine):
    instance, _ = engine
    with instance._qr_client() as client:
        assert client.timeout.read == 35


def test_headless_completion_passes_cookies_and_mode(engine, monkeypatch):
    instance, _ = engine
    complete = Mock(return_value={"wt2": "identity"})
    monkeypatch.setattr(instance._cookie_completer, "complete", complete)
    assert instance._run_headless_cookie_completion({"wt2": "identity"}, lean=True) == {"wt2": "identity"}
    complete.assert_called_once_with({"wt2": "identity"}, lean=True)


@pytest.mark.parametrize("code", [37, 500])
def test_non_success_payload_cannot_be_reported_as_data(engine, code):
    instance, _ = engine
    result = instance._classify_payload({"code": code}, "url")
    assert result["payload"] is None
    assert result["login_redirect"] == (code == 37)


def test_exception_classification_preserves_auth_and_parameter_failures(engine):
    instance, _ = engine
    assert instance._classify_exception("url", instance._SessionExpiredError())["login_redirect"] is True
    assert instance._classify_exception("url", instance._ParamError("invalid"))["payload"] is None


def test_qr_scan_waiting_keeps_session_and_cookies(engine, monkeypatch):
    import time

    instance, _ = engine
    instance._qr_state = {"qr_id": "id", "status": "qr_ready", "expires_at": time.time() + 60}
    client = MagicMock()
    client.__enter__.return_value = client
    client.cookies = {"seed": "cookie"}
    client.get.return_value.json.return_value = {"scaned": False}
    monkeypatch.setattr(instance, "_qr_client", lambda **kwargs: client)
    assert instance._qr_poll_sync()["status"] == "qr_waiting"
    assert instance._qr_state["cookies"] == {"seed": "cookie"}
    client.get.assert_called_once()


def test_risk_payload_is_classified_and_never_returned_as_data(engine):
    instance, _ = engine
    result = instance._classify_payload({"code": 32, "message": "captcha"}, "url")
    assert result["payload"] is None
    assert result["risk_marker"]
