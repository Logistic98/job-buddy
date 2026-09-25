from pathlib import Path

import pytest

from app.core.common.settings import reload_settings


@pytest.fixture(autouse=True)
def restore_default_settings():
    yield
    default_config = Path(__file__).resolve().parents[2] / "config" / "config.yaml"
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
  interview_generation_concurrency: "${JOB_BUDDY_RUNTIME_INTERVIEW_GENERATION_CONCURRENCY:4}"
""",
        encoding="utf-8",
    )
    monkeypatch.setenv("JOB_BUDDY_LLM_BASE_URL", "https://example.com/v1/chat/completions")
    monkeypatch.setenv("JOB_BUDDY_LLM_API_KEY", "test-secret")
    monkeypatch.setenv("JOB_BUDDY_LLM_TIMEOUT_SECONDS", "15")
    monkeypatch.setenv("JOB_BUDDY_LLM_PROVIDER", "chatgpt_pro")
    monkeypatch.setenv("JOB_BUDDY_RUNTIME_USE_LLM_PLANNER", "false")
    monkeypatch.setenv("JOB_BUDDY_RUNTIME_INTERVIEW_GENERATION_CONCURRENCY", "6")

    loaded = reload_settings(str(config_path))

    assert loaded.config.llm_service.provider == "chatgpt_pro"
    assert loaded.model_base_url == "https://example.com/v1/chat/completions"
    assert loaded.model_api_key == "test-secret"
    assert loaded.model_timeout_seconds == 15
    assert loaded.config.runtime.use_llm_planner is False
    assert loaded.config.runtime.interview_generation_concurrency == 6


def test_config_reload_switches_file(tmp_path):
    first = Path(tmp_path / "first.yaml")
    second = Path(tmp_path / "second.yaml")
    first.write_text("runtime:\n  app_name: first-runtime\n", encoding="utf-8")
    second.write_text("runtime:\n  app_name: second-runtime\n", encoding="utf-8")

    assert reload_settings(str(first)).app_name == "first-runtime"
    assert reload_settings(str(second)).app_name == "second-runtime"


def test_relative_config_resolution_and_runtime_accessors(tmp_path, monkeypatch):
    import importlib

    config = importlib.import_module("app.core.common.settings")
    module = tmp_path / "module"
    module.mkdir()
    (module / "config.yaml").write_text(
        "llm_service:\n  model_name: synthetic-model\nruntime:\n  tool_timeout_seconds: 7\n"
    )
    monkeypatch.chdir(tmp_path)
    monkeypatch.setattr(config, "_module_root", lambda: module)
    loaded = config.RuntimeSettings.load("config.yaml")
    assert loaded.model_name == "synthetic-model"
    assert loaded.tool_timeout_seconds == 7
    assert config._load_yaml("missing.yaml", load_dotenv=False) == {}


def test_dotenv_precedence_quotes_and_unreadable_file(tmp_path, monkeypatch):
    import importlib

    config = importlib.import_module("app.core.common.settings")
    module = tmp_path / "module"
    module.mkdir()
    (tmp_path / ".env").write_text(
        "# comment\ninvalid\n=ignored\nTEST_CONFIG_EXPORTED=file\nTEST_CONFIG_ROOT='root'\nTEST_CONFIG_RAW=plain\n"
    )
    (module / ".env").write_text('TEST_CONFIG_ROOT=module\nTEST_CONFIG_MODULE="module"\n')
    monkeypatch.setattr(config, "_project_root", lambda: tmp_path)
    monkeypatch.setattr(config, "_module_root", lambda: module)
    monkeypatch.setattr(config, "_dotenv_values", {})
    monkeypatch.setenv("TEST_CONFIG_EXPORTED", "environment")
    config._load_dotenv_files()
    assert config._dotenv_values == {
        "TEST_CONFIG_ROOT": "root",
        "TEST_CONFIG_RAW": "plain",
        "TEST_CONFIG_MODULE": "module",
    }
    original_read = Path.read_text

    def read(path, *args, **kwargs):
        if path == tmp_path / ".env":
            raise PermissionError("denied")
        return original_read(path, *args, **kwargs)

    monkeypatch.setattr(Path, "read_text", read)
    monkeypatch.setattr(config, "_dotenv_values", {})
    config._load_dotenv_files()
    assert config._dotenv_values == {"TEST_CONFIG_ROOT": "module", "TEST_CONFIG_MODULE": "module"}
