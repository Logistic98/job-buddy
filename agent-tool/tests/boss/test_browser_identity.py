"""Boss 浏览器平台标识回归测试。"""

from app.tools.boss_browser.core.boss_cli_engine import BossCliEngine
from app.tools.boss_browser.core.browser_identity import platform_browser_headers
from app.tools.boss_browser.core.settings import Settings

_MAC_HEADERS = {
    "User-Agent": (
        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
        "AppleWebKit/537.36 (KHTML, like Gecko) Chrome/145.0.0.0 Safari/537.36"
    ),
    "sec-ch-ua-platform": '"macOS"',
}


def test_linux_runtime_uses_linux_browser_identity_without_mutating_source():
    normalized = platform_browser_headers(_MAC_HEADERS, system="Linux", machine="x86_64")

    assert "(X11; Linux x86_64)" in normalized["User-Agent"]
    assert normalized["sec-ch-ua-platform"] == '"Linux"'
    assert "Macintosh" in _MAC_HEADERS["User-Agent"]
    assert _MAC_HEADERS["sec-ch-ua-platform"] == '"macOS"'


def test_macos_runtime_preserves_mac_browser_identity():
    normalized = platform_browser_headers(_MAC_HEADERS, system="Darwin", machine="arm64")

    assert normalized["User-Agent"] == _MAC_HEADERS["User-Agent"]
    assert normalized["sec-ch-ua-platform"] == '"macOS"'


def test_engine_updates_shared_boss_client_headers_for_linux(monkeypatch):
    from boss_cli import client as boss_client
    from boss_cli import constants as boss_constants

    original = dict(boss_constants.HEADERS)
    monkeypatch.setattr("app.tools.boss_browser.core.browser_identity.platform.system", lambda: "Linux")
    monkeypatch.setattr("app.tools.boss_browser.core.browser_identity.platform.machine", lambda: "x86_64")

    try:
        BossCliEngine(Settings())

        assert boss_client.HEADERS is boss_constants.HEADERS
        assert "(X11; Linux x86_64)" in boss_client.HEADERS["User-Agent"]
        assert boss_client.HEADERS["sec-ch-ua-platform"] == '"Linux"'
    finally:
        boss_constants.HEADERS.clear()
        boss_constants.HEADERS.update(original)
