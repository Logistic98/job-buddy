"""登录态降级标记的回归测试。

复现并锁定问题：本地 Cookie 仍在、但真实搜索/详情/画像已经判定登录态失效时，
status() 不能继续误报 logged_in，否则前端会自动重试并反复访问 Boss。
"""

from __future__ import annotations

from app.tools.boss_browser.core.boss_cli_engine import PRIMARY_COOKIE, BossCliEngine
from app.tools.boss_browser.core.settings import Settings


class _FakeCredential:
    cookies = {PRIMARY_COOKIE: "x", "__zp_stoken__": "s", "wbg": "w", "zp_at": "z"}
    has_required_cookies = True
    missing_required_cookies = []


def _engine(tmp_path) -> BossCliEngine:
    settings = Settings()
    engine = BossCliEngine(settings)
    engine._get_credential = lambda: _FakeCredential()  # noqa: SLF001
    return engine


def test_status_logged_in_when_cookie_present(tmp_path):
    engine = _engine(tmp_path)
    status = engine._status_sync()  # noqa: SLF001
    assert status["authenticated"] is True
    assert status["status"] == "logged_in"


def test_auth_redirect_degrades_status_without_network(tmp_path):
    engine = _engine(tmp_path)
    result = engine._auth_redirect("https://www.zhipin.com/web/user/")  # noqa: SLF001
    assert result["login_redirect"] is True
    status = engine._status_sync()  # noqa: SLF001
    assert status["authenticated"] is False
    assert status["status"] == "auth_required"


def test_successful_fetch_clears_degraded(tmp_path):
    engine = _engine(tmp_path)
    engine._auth_redirect("https://www.zhipin.com/web/user/")  # noqa: SLF001
    assert engine._status_sync()["status"] == "auth_required"  # noqa: SLF001
    classified = engine._classify_payload({"jobList": []}, "url")  # noqa: SLF001
    assert classified["login_redirect"] is False
    assert engine._status_sync()["status"] == "logged_in"  # noqa: SLF001
