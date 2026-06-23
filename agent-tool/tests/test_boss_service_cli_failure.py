"""boss-cli 本地基础设施故障不得污染风控状态的回归测试。"""

from __future__ import annotations

import asyncio

import pytest

from app.tools.boss_browser.core.boss_cli_engine import BossCliUnavailable
from app.tools.boss_browser.core.service import BossService
from app.tools.boss_browser.core.settings import Settings


class _FakeSession:
    async def assert_browser_ready(self) -> None:
        raise BossCliUnavailable("boss-cli 未正确初始化")

    async def search(self, *args, **kwargs):  # pragma: no cover - 不应被调用
        raise AssertionError("boss-cli 不可用时不应进入真实搜索")

    async def detail(self, *args, **kwargs):  # pragma: no cover - 不应被调用
        raise AssertionError("boss-cli 不可用时不应进入真实详情")


def _service(tmp_path) -> BossService:
    settings = Settings()
    settings.rate_limit.state_file = str(tmp_path / "rate.json")
    service = BossService(settings)
    service._session = _FakeSession()  # noqa: SLF001
    return service


def test_search_cli_unavailable_not_counted_as_failure(tmp_path):
    service = _service(tmp_path)

    with pytest.raises(BossCliUnavailable):
        asyncio.run(service.search("Java", "上海"))

    snap = service.rate_snapshot()
    assert snap["consecutive_failures"] == 0
    assert snap["search_used_hour"] == 0
    assert snap["search_used_day"] == 0


def test_detail_cli_unavailable_not_counted_as_failure(tmp_path):
    service = _service(tmp_path)

    with pytest.raises(BossCliUnavailable):
        asyncio.run(service.detail(security_id="abc"))

    snap = service.rate_snapshot()
    assert snap["consecutive_failures"] == 0
    assert snap["detail_used_hour"] == 0
    assert snap["detail_used_day"] == 0
