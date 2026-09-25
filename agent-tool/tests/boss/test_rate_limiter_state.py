import asyncio
import json
from unittest.mock import AsyncMock, Mock

import pytest

from app.tools.boss_browser.core import rate_limiter as module
from app.tools.boss_browser.core import settings
from app.tools.boss_browser.core.rate_limiter import RateLimiter, RateLimitError, RiskCooldownError
from app.tools.boss_browser.core.settings import RateLimitConfig


def test_redis_state_restores_only_live_windows_and_cooldown(monkeypatch):
    now = 100000
    monkeypatch.setattr(module.time, "time", lambda: now)
    redis = Mock()
    redis.get.return_value = json.dumps(
        {
            "cooldown_until": now + 60,
            "consecutive_failures": 2,
            "windows": {"search": {"hour": [now - 4000, now - 1], "day": [1, now - 1]}},
        }
    )
    monkeypatch.setattr(module.Redis, "from_url", Mock(return_value=redis))
    limiter = RateLimiter(RateLimitConfig(), redis_url="redis://redis.invalid", namespace="test")
    snapshot = limiter.snapshot()
    assert snapshot["search_used_hour"] == snapshot["search_used_day"] == 1
    assert snapshot["cooldown_remaining_seconds"] == 60
    with pytest.raises(RiskCooldownError):
        asyncio.run(limiter.acquire("search"))
    limiter.clear_cooldown()
    limiter.reset_backstop()
    saved = json.loads(redis.set.call_args.args[1])
    assert saved["cooldown_until"] == saved["consecutive_failures"] == 0
    assert redis.set.call_args.kwargs["ex"] == 172800


@pytest.mark.parametrize("mode", ["connect", "empty", "read", "write"])
def test_redis_failures_keep_process_local_protection(monkeypatch, mode):
    redis = Mock()
    redis.get.return_value = None
    if mode == "connect":
        redis.ping.side_effect = ConnectionError("offline")
    elif mode == "read":
        redis.get.side_effect = ConnectionError("offline")
    elif mode == "write":
        redis.set.side_effect = ConnectionError("offline")
    monkeypatch.setattr(module.Redis, "from_url", Mock(return_value=redis))
    limiter = RateLimiter(
        RateLimitConfig(action_delay_min_ms=0, action_delay_max_ms=0), redis_url="redis://redis.invalid"
    )
    limiter.record_failure()
    assert limiter.snapshot()["consecutive_failures"] == 1
    limiter.record_success()
    assert limiter.snapshot()["consecutive_failures"] == 0


@pytest.mark.parametrize("limit", ["search_per_hour", "search_per_day"])
def test_search_quotas_block_next_acquisition(monkeypatch, limit):
    config = RateLimitConfig(search_per_hour=0, search_per_day=0, action_delay_min_ms=0, action_delay_max_ms=0)
    setattr(config, limit, 1)
    limiter = RateLimiter(config)
    asyncio.run(limiter.acquire("search"))
    with pytest.raises(RateLimitError):
        asyncio.run(limiter.acquire("search"))


def test_delay_and_expired_windows_use_clock_without_real_wait(monkeypatch):
    monkeypatch.setattr(module.time, "time", lambda: 100000)
    sleep = AsyncMock()
    monkeypatch.setattr(module.asyncio, "sleep", sleep)
    limiter = RateLimiter(RateLimitConfig(action_delay_min_ms=1000, action_delay_max_ms=1000))
    limiter._last_action_at = 100000
    limiter._windows["search"].record(1)
    asyncio.run(limiter.acquire("search"))
    sleep.assert_awaited_once_with(1)
    assert limiter.snapshot()["search_used_hour"] == limiter.snapshot()["search_used_day"] == 1


def test_settings_missing_yaml_and_environment_parsing(monkeypatch, tmp_path):
    monkeypatch.setenv("AGENT_TOOL_BOSS_CONFIG", str(tmp_path / "missing.yaml"))
    assert settings._load_yaml() == {}
    monkeypatch.setenv("TEST_CONFIG_INT", "bad")
    assert settings._env_int("TEST_CONFIG_INT", 5) == 5
    monkeypatch.setenv("TEST_CONFIG_INT", "7")
    assert settings._env_int("TEST_CONFIG_INT", 5) == 7
    monkeypatch.setenv("TEST_CONFIG_BOOL", "off")
    assert settings._env_bool("TEST_CONFIG_BOOL", True) is False
    monkeypatch.setenv("TEST_CONFIG_FLOAT", "bad")
    assert settings._env_float("TEST_CONFIG_FLOAT", 1.5) == 1.5
    monkeypatch.setenv("TEST_CONFIG_FLOAT", "2.5")
    assert settings._env_float("TEST_CONFIG_FLOAT", 1.5) == 2.5
    monkeypatch.setenv("SPRING_REDIS_HOST", "redis.invalid")
    monkeypatch.setenv("SPRING_REDIS_PORT", "6380")
    monkeypatch.setenv("SPRING_REDIS_DATABASE", "2")
    monkeypatch.setenv("SPRING_REDIS_PASSWORD", "synthetic:/password")
    assert settings._default_redis_url() == "redis://:synthetic%3A%2Fpassword@redis.invalid:6380/2"


def test_risk_signal_enters_cooldown_and_blocks_acquisition(monkeypatch):
    monkeypatch.setattr(module.time, "time", lambda: 100000)
    limiter = RateLimiter(RateLimitConfig(cooldown_minutes_on_risk=2))
    limiter.trip_risk_cooldown("verification required")
    assert limiter.snapshot()["cooldown_remaining_seconds"] == 120
    with pytest.raises(RiskCooldownError):
        asyncio.run(limiter.acquire("detail"))
