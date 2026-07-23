"""One-shot headless browser helper for refreshing Boss web security cookies."""

from __future__ import annotations

import tempfile
import time
from typing import Any

from app.tools.boss_browser.core.settings import Settings

_LEAN_VISIT_TIMEOUT_MS = 4000
_LEAN_SETTLE_MS = 600
_BROWSER_ATTEMPTS = 2
_TARGET_CLOSED_MARKERS = (
    "target page, context or browser has been closed",
    "target closed",
    "browser has been closed",
)


class HeadlessCookieCompleter:
    def __init__(self, settings: Settings, constants: Any) -> None:
        self._settings = settings
        self._constants = constants

    def complete(self, cookies: dict[str, str], *, lean: bool = False) -> dict[str, str]:
        try:
            from playwright.sync_api import sync_playwright
        except ImportError as exc:
            raise RuntimeError(
                "缺少 Playwright，无法补齐 Boss 关键 Cookie。请在 agent-tool 执行 "
                "uv sync 后运行 uv run playwright install chromium。"
            ) from exc

        base_url = str(self._constants.BASE_URL).rstrip("/")
        headers = dict(getattr(self._constants, "HEADERS", {}) or {})
        combined = dict(cookies)
        timeout_ms = int(self._settings.boss_cli.headless_cookie_timeout_ms)
        if lean:
            timeout_ms = min(timeout_ms, _LEAN_VISIT_TIMEOUT_MS)

        with (
            tempfile.TemporaryDirectory(prefix="job-buddy-boss-browser-") as user_data_dir,
            sync_playwright() as playwright,
        ):
            last_closed_error: Exception | None = None
            for attempt in range(_BROWSER_ATTEMPTS):
                try:
                    return self._complete_once(
                        playwright,
                        user_data_dir,
                        base_url,
                        headers,
                        combined,
                        timeout_ms=timeout_ms,
                        lean=lean,
                    )
                except Exception as exc:  # noqa: BLE001
                    if not self._is_target_closed(exc):
                        raise
                    last_closed_error = exc
                    if attempt + 1 >= _BROWSER_ATTEMPTS:
                        break
            raise RuntimeError("Boss 临时浏览器会话提前关闭，未能刷新安全令牌，请稍后重试。") from last_closed_error
        return combined

    def _complete_once(
        self,
        playwright: Any,
        user_data_dir: str,
        base_url: str,
        headers: dict[str, Any],
        combined: dict[str, str],
        *,
        timeout_ms: int,
        lean: bool,
    ) -> dict[str, str]:
        context = playwright.chromium.launch_persistent_context(
            user_data_dir,
            headless=True,
            user_agent=headers.get("User-Agent"),
            locale="zh-CN",
            viewport={"width": 1365, "height": 900},
            args=[
                "--password-store=basic",
                "--use-mock-keychain",
                "--disable-background-networking",
                "--disable-blink-features=AutomationControlled",
                "--disable-default-apps",
                "--disable-extensions",
                "--disable-sync",
                "--no-first-run",
            ],
        )
        context.add_init_script("Object.defineProperty(navigator, 'webdriver', {get: () => undefined});")
        try:
            context.clear_cookies()
            seed = [
                {
                    "name": name,
                    "value": value,
                    "domain": ".zhipin.com",
                    "path": "/",
                    "secure": True,
                    "sameSite": "Lax",
                }
                for name, value in combined.items()
                if value is not None
            ]
            if seed:
                context.add_cookies(seed)
            page = context.pages[0] if context.pages else context.new_page()

            def collect() -> None:
                for item in context.cookies(base_url):
                    name = item.get("name")
                    value = item.get("value")
                    if name and value:
                        combined[name] = value

            settle_ms = _LEAN_SETTLE_MS if lean else 1000

            def visit(url: str, wait_until: str = "domcontentloaded") -> None:
                try:
                    page.goto(url, wait_until=wait_until, timeout=timeout_ms)
                except Exception as exc:  # noqa: BLE001
                    if self._is_target_closed(exc):
                        raise
                # Playwright 的 page.wait_for_timeout 依赖页面仍存活。Boss 页面在导航期间
                # 主动关闭或替换页面时，这个等待会把可恢复的临时页面事件误报成登录失效。
                # 使用进程内短暂停顿后从 context 回收 Cookie，不再依赖旧 page 的生命周期。
                time.sleep(settle_ms / 1000)
                collect()

            visit(f"{base_url}/")
            if "__zp_stoken__" not in combined:
                # 首页并不保证执行岗位页的安全脚本。持久身份 Cookie 仍有效时，
                # 再访问一次已登录岗位页即可静默重生临时令牌；lean 模式继续
                # 使用收紧的超时与 domcontentloaded，避免交互链路长时间阻塞。
                wait_until = "domcontentloaded" if lean else "networkidle"
                visit(f"{base_url}/web/geek/job-recommend", wait_until)
            if lean:
                return combined
            if "__zp_stoken__" not in combined:
                visit(f"{base_url}/web/user/?ka=header-login")
            return combined
        finally:
            try:
                context.close()
            except Exception as exc:  # noqa: BLE001
                if not self._is_target_closed(exc):
                    raise

    @staticmethod
    def _is_target_closed(exc: Exception) -> bool:
        lowered = str(exc).lower()
        return any(marker in lowered for marker in _TARGET_CLOSED_MARKERS)
