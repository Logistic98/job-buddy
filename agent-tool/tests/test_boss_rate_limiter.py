import asyncio

from app.tools.boss_browser.core.rate_limiter import RateLimiter
from app.tools.boss_browser.core.settings import RateLimitConfig


def test_zero_favorite_list_limits_disable_local_quota():
    config = RateLimitConfig(
        favorite_list_per_hour=0,
        favorite_list_per_day=0,
        action_delay_min_ms=0,
        action_delay_max_ms=0,
    )
    limiter = RateLimiter(config)

    async def acquire_many() -> None:
        for _ in range(50):
            await limiter.acquire("favorite_list")

    asyncio.run(acquire_many())

    snapshot = limiter.snapshot()
    assert snapshot["favorite_list_used_hour"] == 50
    assert snapshot["favorite_list_limit_hour"] == 0
    assert snapshot["favorite_list_limit_day"] == 0


def test_detail_never_uses_fixed_quota_even_if_legacy_values_are_configured():
    config = RateLimitConfig(
        detail_per_hour=1,
        detail_per_day=1,
        action_delay_min_ms=0,
        action_delay_max_ms=0,
    )
    limiter = RateLimiter(config)

    async def acquire_many() -> None:
        for _ in range(65):
            await limiter.acquire("detail")

    asyncio.run(acquire_many())

    snapshot = limiter.snapshot()
    assert snapshot["detail_used_hour"] == 65
    assert snapshot["detail_used_day"] == 65
    assert snapshot["detail_limit_hour"] == 0
    assert snapshot["detail_limit_day"] == 0
