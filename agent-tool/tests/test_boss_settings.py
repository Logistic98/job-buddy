from pathlib import Path

import yaml

from app.tools.boss_browser.core.settings import RateLimitConfig, Settings, _apply_env_overrides


def test_rate_limit_model_defaults_match_yaml_baseline():
    rate_limit = RateLimitConfig()
    config_path = Path(__file__).resolve().parents[1] / "app" / "tools" / "boss_browser" / "config" / "config.yaml"
    yaml_settings = Settings.model_validate(yaml.safe_load(config_path.read_text(encoding="utf-8")))

    assert rate_limit.search_per_hour == yaml_settings.rate_limit.search_per_hour == 60
    assert rate_limit.search_per_day == yaml_settings.rate_limit.search_per_day == 120
    assert rate_limit.detail_per_hour == yaml_settings.rate_limit.detail_per_hour == 0
    assert rate_limit.detail_per_day == yaml_settings.rate_limit.detail_per_day == 0


def test_search_daily_rate_limit_supports_environment_override_but_detail_stays_unlimited(monkeypatch):
    monkeypatch.setenv("BOSS_CLI_SEARCH_PER_DAY", "90")
    monkeypatch.setenv("BOSS_CLI_DETAIL_PER_DAY", "45")

    settings = _apply_env_overrides(Settings())

    assert settings.rate_limit.search_per_day == 90
    assert settings.rate_limit.detail_per_day == 0
