"""Boss headless Cookie 静默补齐回归测试。"""

from __future__ import annotations

from types import SimpleNamespace

import playwright.sync_api

from app.tools.boss_browser.core.headless_cookie_completer import HeadlessCookieCompleter
from app.tools.boss_browser.core.settings import Settings


class _FakePage:
    def __init__(self, context: "_FakeContext", stoken_visit: int, close_on_visit: int = 0) -> None:
        self._context = context
        self._stoken_visit = stoken_visit
        self._close_on_visit = close_on_visit

    def goto(self, url: str, *, wait_until: str, timeout: int) -> None:
        self._context.visits.append((url, wait_until, timeout))
        if len(self._context.visits) == self._close_on_visit:
            raise RuntimeError("Page.goto: Target page, context or browser has been closed")
        if len(self._context.visits) == self._stoken_visit:
            self._context.cookie_jar["__zp_stoken__"] = "fresh-token"


class _FakeContext:
    def __init__(self, stoken_visit: int, close_on_visit: int = 0) -> None:
        self.cookie_jar: dict[str, str] = {}
        self.visits: list[tuple[str, str, int]] = []
        self.pages = [_FakePage(self, stoken_visit, close_on_visit)]
        self.closed = False

    def add_init_script(self, script: str) -> None:
        assert "navigator" in script

    def clear_cookies(self) -> None:
        self.cookie_jar.clear()

    def add_cookies(self, cookies: list[dict[str, object]]) -> None:
        for cookie in cookies:
            self.cookie_jar[str(cookie["name"])] = str(cookie["value"])

    def cookies(self, url: str) -> list[dict[str, str]]:
        assert url == "https://www.zhipin.com"
        return [{"name": name, "value": value} for name, value in self.cookie_jar.items()]

    def new_page(self) -> _FakePage:
        return self.pages[0]

    def close(self) -> None:
        self.closed = True


class _FakePlaywrightManager:
    def __init__(self, contexts: list[_FakeContext]) -> None:
        self._contexts = iter(contexts)
        chromium = SimpleNamespace(launch_persistent_context=lambda *_args, **_kwargs: next(self._contexts))
        self._playwright = SimpleNamespace(chromium=chromium)

    def __enter__(self) -> SimpleNamespace:
        return self._playwright

    def __exit__(self, *_args: object) -> None:
        return None


def _install_fake_playwright(monkeypatch, *, stoken_visit: int) -> _FakeContext:
    context = _FakeContext(stoken_visit)
    monkeypatch.setattr(
        playwright.sync_api,
        "sync_playwright",
        lambda: _FakePlaywrightManager([context]),
    )
    monkeypatch.setattr("app.tools.boss_browser.core.headless_cookie_completer.time.sleep", lambda _seconds: None)
    return context


def _completer() -> HeadlessCookieCompleter:
    constants = SimpleNamespace(
        BASE_URL="https://www.zhipin.com",
        HEADERS={"User-Agent": "job-buddy-test"},
    )
    return HeadlessCookieCompleter(Settings(), constants)


def test_lean_refresh_visits_authenticated_job_page_when_homepage_does_not_issue_stoken(monkeypatch):
    context = _install_fake_playwright(monkeypatch, stoken_visit=2)

    result = _completer().complete({"wt2": "identity", "zp_at": "account"}, lean=True)

    assert result["__zp_stoken__"] == "fresh-token"
    assert [visit[0] for visit in context.visits] == [
        "https://www.zhipin.com/",
        "https://www.zhipin.com/web/geek/job-recommend",
    ]
    assert all(visit[1] == "domcontentloaded" for visit in context.visits)
    assert all(visit[2] == 4000 for visit in context.visits)
    assert context.closed is True


def test_lean_refresh_stops_after_homepage_issues_stoken(monkeypatch):
    context = _install_fake_playwright(monkeypatch, stoken_visit=1)

    result = _completer().complete({"wt2": "identity", "zp_at": "account"}, lean=True)

    assert result["__zp_stoken__"] == "fresh-token"
    assert [visit[0] for visit in context.visits] == ["https://www.zhipin.com/"]
    assert context.closed is True


def test_lean_refresh_retries_with_fresh_browser_when_first_context_closes(monkeypatch):
    first = _FakeContext(stoken_visit=99, close_on_visit=1)
    second = _FakeContext(stoken_visit=1)
    monkeypatch.setattr(
        playwright.sync_api,
        "sync_playwright",
        lambda: _FakePlaywrightManager([first, second]),
    )
    monkeypatch.setattr("app.tools.boss_browser.core.headless_cookie_completer.time.sleep", lambda _seconds: None)

    result = _completer().complete({"wt2": "identity", "zp_at": "account"}, lean=True)

    assert result["__zp_stoken__"] == "fresh-token"
    assert first.closed is True
    assert second.closed is True
