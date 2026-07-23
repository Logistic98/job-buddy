"""硬停与扫码登录恢复契约测试。

- 需要登录不计入硬停；
- 已达硬停阈值但未登录时仍返回 AuthRequiredError，引导用户恢复登录；
- 确认登录态有效后立即清零硬停计数。
"""

from __future__ import annotations

import asyncio

import pytest

from app.tools.boss_browser.core.service import AuthRequiredError, BossService
from app.tools.boss_browser.core.settings import Settings


class _FakeSession:
    def __init__(self, *, authenticated: bool) -> None:
        self.authenticated = authenticated

    async def assert_browser_ready(self) -> None:
        return None

    async def status(self):
        return {"authenticated": self.authenticated, "status": "logged_in" if self.authenticated else "auth_required"}

    async def search(self, *args, **kwargs):
        # 未登录时引擎层返回登录重定向，登录后返回真实结果。
        if self.authenticated:
            return {"payload": {"jobList": [{"jobName": "Java"}]}, "login_redirect": False, "risk_marker": None}
        return {"payload": None, "login_redirect": True, "risk_marker": None}


def _service(tmp_path, *, authenticated: bool) -> BossService:
    settings = Settings()
    settings.rate_limit.state_file = str(tmp_path / "rate.json")
    service = BossService(settings)
    service._session = _FakeSession(authenticated=authenticated)  # noqa: SLF001
    return service


def test_auth_required_not_counted_as_backstop_failure(tmp_path):
    service = _service(tmp_path, authenticated=False)

    for _ in range(8):
        with pytest.raises(AuthRequiredError):
            asyncio.run(service.search("Java", "上海"))

    # 需要登录不应累计硬停计数，否则会把扫码入口锁死。
    assert service.rate_snapshot()["consecutive_failures"] == 0


def test_backstop_unauthenticated_routes_to_login(tmp_path):
    service = _service(tmp_path, authenticated=False)
    # 构造已达到连续失败阈值的硬停状态。
    backstop = service._settings.rate_limit.consecutive_failure_backstop  # noqa: SLF001
    for _ in range(backstop):
        service.limiter.record_failure()

    # 已硬停且未登录：应转为引导扫码（可恢复），而不是死锁报硬停。
    with pytest.raises(AuthRequiredError):
        asyncio.run(service.search("Java", "上海"))


def test_status_authenticated_resets_backstop(tmp_path):
    service = _service(tmp_path, authenticated=True)
    backstop = service._settings.rate_limit.consecutive_failure_backstop  # noqa: SLF001
    for _ in range(backstop):
        service.limiter.record_failure()
    assert service.rate_snapshot()["consecutive_failures"] == backstop

    status = asyncio.run(service.status())
    assert status["authenticated"] is True
    # 确认登录态有效后硬停计数清零，后续搜索可正常放行。
    assert service.rate_snapshot()["consecutive_failures"] == 0


def test_search_success_after_login(tmp_path):
    service = _service(tmp_path, authenticated=True)
    jobs = asyncio.run(service.search("Java", "上海"))
    assert jobs and jobs[0].get("jobName") == "Java"
    assert service.rate_snapshot()["consecutive_failures"] == 0


def test_temporary_token_refresh_failure_does_not_request_qr_login(tmp_path):
    service = _service(tmp_path, authenticated=False)

    async def _temporary_failure(*_args, **_kwargs):
        return {
            "payload": None,
            "login_redirect": False,
            "risk_marker": None,
            "temporary_auth_refresh_failed": True,
            "error_message": "Boss 临时安全令牌刷新失败，请稍后重试；现有凭据已保留。",
        }

    service._session.search = _temporary_failure  # noqa: SLF001

    with pytest.raises(RuntimeError, match="临时安全令牌刷新失败"):
        asyncio.run(service.search("Java", "上海"))

    assert service.rate_snapshot()["consecutive_failures"] == 1
