
from pathlib import Path

import pytest

from app.core.common.settings import reload_settings


@pytest.fixture(autouse=True)
def restore_default_settings():
    yield
    default_config = Path(__file__).resolve().parents[1] / "config" / "config.yaml"
    reload_settings(str(default_config))


def test_config_loads_env_placeholders(tmp_path, monkeypatch):
    config_path = tmp_path / "config.yaml"
    config_path.write_text(
        """
llm_service:
  provider: "${JOB_BUDDY_LLM_PROVIDER:deepseek_api}"
  base_url: "${JOB_BUDDY_LLM_BASE_URL:http://default.local/v1}"
  api_key: "${JOB_BUDDY_LLM_API_KEY:}"
  timeout_seconds: "${JOB_BUDDY_LLM_TIMEOUT_SECONDS:60}"
runtime:
  use_llm_planner: "${JOB_BUDDY_RUNTIME_USE_LLM_PLANNER:false}"
""",
        encoding="utf-8",
    )
    monkeypatch.setenv("JOB_BUDDY_LLM_BASE_URL", "https://example.com/v1/chat/completions")
    monkeypatch.setenv("JOB_BUDDY_LLM_API_KEY", "test-secret")
    monkeypatch.setenv("JOB_BUDDY_LLM_TIMEOUT_SECONDS", "15")
    monkeypatch.setenv("JOB_BUDDY_LLM_PROVIDER", "chatgpt_pro")
    monkeypatch.setenv("JOB_BUDDY_RUNTIME_USE_LLM_PLANNER", "false")

    loaded = reload_settings(str(config_path))

    assert loaded.config.llm_service.provider == "chatgpt_pro"
    assert loaded.model_base_url == "https://example.com/v1/chat/completions"
    assert loaded.model_api_key == "test-secret"
    assert loaded.model_timeout_seconds == 15
    assert loaded.config.runtime.use_llm_planner is False


def test_config_reload_switches_file(tmp_path):
    first = Path(tmp_path / "first.yaml")
    second = Path(tmp_path / "second.yaml")
    first.write_text("runtime:\n  app_name: first-runtime\n", encoding="utf-8")
    second.write_text("runtime:\n  app_name: second-runtime\n", encoding="utf-8")

    assert reload_settings(str(first)).app_name == "first-runtime"
    assert reload_settings(str(second)).app_name == "second-runtime"
