"""验证 Boss 编排的配额、错误归类和分页契约，不访问外部平台。"""

import asyncio
from unittest.mock import AsyncMock, Mock

import pytest

from app.tools.boss_browser.core.boss_cli_engine import BossCliUnavailable, BossCliUpstreamRateLimited
from app.tools.boss_browser.core.service import AuthRequiredError, BossService, RiskControlError
from app.tools.boss_browser.core.settings import Settings


@pytest.fixture
def service():
    settings = Settings()
    settings.rate_limit.redis_url = ""
    instance = BossService(settings, owner_key="test-owner", qr_codec=Mock())
    instance._session = Mock()
    instance._session.assert_browser_ready = AsyncMock()
    instance._session.status = AsyncMock(return_value={"authenticated": True})
    instance._limiter = Mock()
    instance._limiter.acquire = AsyncMock()
    instance._limiter.snapshot.return_value = {"consecutive_failures": 0}
    return instance


def invoke(service, action):
    return asyncio.run(getattr(service, action)("Python") if action == "search" else getattr(service, action)())


@pytest.mark.parametrize("action", ["search", "detail", "profile", "favorite_jobs"])
@pytest.mark.parametrize("unavailable", [False, True])
def test_upstream_failure_count_excludes_local_infrastructure(service, action, unavailable):
    error = BossCliUnavailable("local") if unavailable else OSError("upstream")
    setattr(service._session, action, AsyncMock(side_effect=error))

    with pytest.raises(type(error), match=str(error)):
        invoke(service, action)

    assert service._limiter.record_failure.call_count == (0 if unavailable else 1)
    service._limiter.record_success.assert_not_called()


@pytest.mark.parametrize("action", ["search", "detail", "profile", "favorite_jobs"])
@pytest.mark.parametrize(
    ("result", "error", "cooldown"),
    [
        ({"rate_limited": True, "error_message": "rate limited"}, BossCliUpstreamRateLimited, False),
        ({"risk_marker": "captcha"}, RiskControlError, True),
    ],
)
def test_upstream_risk_stops_action(service, action, result, error, cooldown):
    setattr(service._session, action, AsyncMock(return_value=result))

    with pytest.raises(error):
        invoke(service, action)

    service._limiter.record_failure.assert_called_once()
    assert service._limiter.trip_risk_cooldown.call_count == int(cooldown)
    service._limiter.record_success.assert_not_called()


@pytest.mark.parametrize("action", ["search", "detail", "favorite_jobs"])
def test_login_redirect_does_not_consume_failure_budget(service, action):
    setattr(service._session, action, AsyncMock(return_value={"login_redirect": True}))

    with pytest.raises(AuthRequiredError):
        invoke(service, action)

    service._limiter.record_failure.assert_not_called()
    service._limiter.record_success.assert_not_called()


@pytest.mark.parametrize("action", ["search", "detail", "favorite_jobs"])
def test_temporary_token_failure_retains_error_and_counts_failure(service, action):
    setattr(
        service._session,
        action,
        AsyncMock(return_value={"temporary_auth_refresh_failed": True, "error_message": "refresh failed"}),
    )

    with pytest.raises(RuntimeError, match="refresh failed"):
        invoke(service, action)

    service._limiter.record_failure.assert_called_once()


@pytest.mark.parametrize("action", ["search", "detail", "profile", "favorite_jobs"])
@pytest.mark.parametrize("authenticated", [False, True])
def test_missing_payload_distinguishes_authentication_from_upstream_failure(service, action, authenticated):
    setattr(service._session, action, AsyncMock(return_value={}))
    service._session.status.return_value = {"authenticated": authenticated}

    with pytest.raises(RuntimeError if authenticated else AuthRequiredError):
        invoke(service, action)

    assert service._limiter.record_failure.call_count == int(authenticated)
    service._limiter.record_success.assert_not_called()


@pytest.mark.parametrize("action", ["search", "favorite_jobs"])
def test_local_rejection_does_not_increment_upstream_failures(service, action):
    setattr(service._session, action, AsyncMock(return_value={"local_rejected": True}))

    with pytest.raises(RuntimeError):
        invoke(service, action)

    service._limiter.record_failure.assert_not_called()


def test_empty_search_does_not_reset_backstop(service):
    service._session.search = AsyncMock(return_value={"payload": {"jobList": []}})

    assert invoke(service, "search") == []
    service._limiter.record_success.assert_not_called()
    service._limiter.record_failure.assert_not_called()


@pytest.mark.parametrize(("total", "pages"), [(10, 10), ("invalid", 2), (-1, 2)])
def test_favorites_respects_page_cap_and_normalizes_total(service, total, pages):
    service._settings.boss_cli.max_favorite_list_page = 2
    service._session.favorite_jobs = AsyncMock(
        return_value={
            "payload": {"zpData": {"cardList": [{"jobName": "Python"}], "totalCount": total, "hasMore": True}}
        }
    )

    result = asyncio.run(service.favorite_jobs(page=2))

    assert result["page"] == 2
    assert result["hasMore"] is False
    assert result["totalPages"] == max(3, pages)
    assert result["jobs"][0]["jobName"] == "Python"
    service._limiter.record_success.assert_called_once()


def test_favorites_rejects_page_above_cap_before_access(service):
    service._settings.boss_cli.max_favorite_list_page = 2
    with pytest.raises(ValueError):
        asyncio.run(service.favorite_jobs(page=3))
    service._session.assert_browser_ready.assert_not_awaited()
    service._limiter.acquire.assert_not_awaited()


@pytest.mark.parametrize(
    ("payload", "expected"), [(None, 7), ({"data": {"result": {"total": 3}}}, 3), ({"other": 2}, 7)]
)
def test_nested_payload_metadata_falls_back_only_when_missing(payload, expected):
    assert BossService._payload_value(payload, "total", 7) == expected


def test_qr_start_requires_session_before_access(service):
    with pytest.raises(ValueError):
        asyncio.run(service.qr_start(""))
    service._session.start_qr_login.assert_not_called()


def test_qr_start_binds_owner_and_expiration(service):
    service._session.start_qr_login = AsyncMock(return_value={"status": "qr_pending"})
    state = {"qr_id": "qr", "expires_at": 1234}
    service._session.export_qr_state.return_value = state
    service._qr_codec.encode.return_value = "signed-token"

    result = asyncio.run(service.qr_start("session"))

    assert result == {
        "status": "qr_pending",
        "session_id": "session",
        "session_token": "signed-token",
        "expires_at": 1234,
    }
    service._qr_codec.encode.assert_called_once_with(
        owner_key="test-owner", session_id="session", state=state, expires_at=1234
    )


@pytest.mark.parametrize("status", ["qr_pending", "logged_in", "auth_required", "qr_expired"])
def test_qr_poll_renews_only_pending_sessions(service, status):
    state = {"qr_id": "qr", "expires_at": 1234}
    service._qr_codec.decode.return_value = state
    service._session.export_qr_state.return_value = state
    service._session.poll_qr_login = AsyncMock(return_value={"status": status, "authenticated": status == "logged_in"})
    service._qr_codec.encode.return_value = "renewed"

    result = asyncio.run(service.qr_status("session", "token"))

    service._session.import_qr_state.assert_called_once_with(state)
    assert ("session_token" in result) == (status == "qr_pending")
    assert service._limiter.reset_backstop.call_count == int(status == "logged_in")
    assert result["session_id"] == "session"


def test_qr_snapshot_never_polls_upstream(service):
    service._session.qr_snapshot.return_value = {"status": "qr_pending"}
    result = asyncio.run(service.qr_status("session", "token", wait_for_update=False))
    assert result == {"status": "qr_pending", "session_id": "session"}
    service._session.poll_qr_login.assert_not_called()


def test_qr_cancel_checks_token_before_clearing(service):
    service._qr_codec.decode.side_effect = ValueError("invalid token")
    with pytest.raises(ValueError, match="invalid token"):
        asyncio.run(service.qr_cancel("session", "token"))
    service._session.clear_qr_state.assert_not_called()
    service._qr_codec.decode.side_effect = None
    assert asyncio.run(service.qr_cancel("session", "token")) == {"status": "cancelled", "session_id": "session"}
    service._session.clear_qr_state.assert_called_once()


@pytest.mark.parametrize("authenticated", [True, False])
def test_refresh_auth_resets_backstop_only_after_verified_login(service, authenticated):
    service._session.refresh_auth = AsyncMock(return_value={"authenticated": authenticated})
    assert asyncio.run(service.refresh_auth()) == {"authenticated": authenticated}
    assert service._limiter.reset_backstop.call_count == int(authenticated)
    assert service._limiter.clear_cooldown.call_count == int(authenticated)


def test_detail_success_normalizes_job_description(service):
    service._session.detail = AsyncMock(
        return_value={"payload": {"jobInfo": {"jobName": "Python", "postDescription": "Build APIs"}}}
    )
    result = asyncio.run(service.detail(security_id="job"))
    assert result["jobName"] == "Python"
    assert result["jobDescription"] == "Build APIs"
    service._limiter.record_success.assert_called_once()


def test_service_exports_credentials_without_disk_access(service):
    service._session.credential_json.return_value = "synthetic"
    assert service.credential_json() == "synthetic"
    service._session.credential_json.assert_called_once()


def test_authenticated_backstop_stays_stopped(service):
    from app.tools.boss_browser.core.rate_limiter import BackstopError

    service._limiter.acquire.side_effect = BackstopError("stopped")
    with pytest.raises(BackstopError, match="stopped"):
        asyncio.run(service._acquire("search"))


def test_successful_profile_assembles_api_sections(service):
    service._session.profile = AsyncMock(
        return_value={"captures": [("/resume/baseinfo", {"data": {"name": "synthetic"}})]}
    )
    assert asyncio.run(service.profile())["basicInfo"] == {"name": "synthetic"}
    service._limiter.record_success.assert_called_once()
