"""配置加载：YAML 基线 + 环境变量覆盖。敏感信息只走环境变量。"""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Any

import yaml
from pydantic import BaseModel, Field


class ServerConfig(BaseModel):
    host: str = "0.0.0.0"
    port: int = 8040


class BossConfig(BaseModel):
    # Boss 城市名→城市编码（LID）映射。Boss 搜索用数字编码，不认中文名。
    city_codes: dict[str, str] = Field(default_factory=dict)


class RateLimitConfig(BaseModel):
    search_per_hour: int = 15
    search_per_day: int = 120
    detail_per_hour: int = 12
    detail_per_day: int = 120
    action_delay_min_ms: int = 800
    action_delay_max_ms: int = 2000
    cooldown_minutes_on_risk: int = 30
    consecutive_failure_backstop: int = 5
    # 限速/风控状态持久化文件路径；为空时由运行期解析到仓库根 .run 下。
    state_file: str = ""


class RiskConfig(BaseModel):
    page_markers: list[str] = Field(default_factory=list)


class BossCliConfig(BaseModel):
    """jackwener/boss-cli 取数引擎配置。

    data_dir 用于保存 boss-cli 的 credential.json，默认位于仓库根 .run/boss-cli-home。
    不修改 HOME，避免影响 browser-cookie3 从用户真实浏览器目录读取 Cookie。
    status_verify 默认关闭，避免频繁登录态检查触发真实 Boss 请求；真实搜索/详情失败时
    再把登录态标记为降级并引导重新导入 Cookie。
    """

    data_dir: str = ""
    cookie_source: str = ""
    # 默认不自动读取本机浏览器 Cookie，避免 browser-cookie3 在 macOS 触发 Keychain
    # “Chrome Safe Storage” 密码弹窗。需要显式导入时再通过环境变量开启。
    auto_import_browser_cookies: bool = False
    status_verify: bool = False
    # 默认允许搜索前若干页，支撑"换一批"候选池跨页抓取；按页数本地拦截的机制保留。
    max_search_page: int = 5
    request_delay_s: float = 1.2
    timeout_s: float = 30.0
    # 二维码 dispatch 缺少 __zp_stoken__ 等 Web 关键 Cookie 时，用 headless 浏览器
    # 访问 Boss 网页让前端 JS 生成该安全令牌后回收。关闭后扫码登录可能因缺少 stoken
    # 而无法获得可用搜索登录态。
    headless_cookie_completion: bool = True
    headless_cookie_timeout_ms: int = 8000


class Settings(BaseModel):
    app_name: str = "agent_tool_boss_browser"
    server: ServerConfig = Field(default_factory=ServerConfig)
    boss: BossConfig = Field(default_factory=BossConfig)
    boss_cli: BossCliConfig = Field(default_factory=BossCliConfig)
    rate_limit: RateLimitConfig = Field(default_factory=RateLimitConfig)
    risk: RiskConfig = Field(default_factory=RiskConfig)


def _module_dir() -> Path:
    # agent-tool/app/tools/boss_browser
    return Path(__file__).resolve().parents[1]


def _repo_root() -> Path:
    # agent-tool 的上一级即仓库根。
    return Path(__file__).resolve().parents[5]


def _default_rate_state_file() -> str:
    return str(_repo_root() / ".run" / "boss-rate-state.json")


def _default_auth_data_dir() -> str:
    # 与后端约定的共享登录态目录对齐，避免不同启动目录读到不同 cookie。
    return str(_repo_root() / ".run" / "boss-cli-home")


def _resolve_runtime_path(value: str) -> str:
    path = Path(value).expanduser()
    if path.is_absolute():
        return str(path)
    return str(_repo_root() / path)


def _load_yaml() -> dict[str, Any]:
    config_path = Path(
        os.getenv("BOSS_BROWSER_CONFIG", str(_module_dir() / "config" / "config.yaml"))
    )
    if not config_path.exists():
        return {}
    with config_path.open("r", encoding="utf-8") as fh:
        return yaml.safe_load(fh) or {}


def _env_int(name: str, fallback: int) -> int:
    value = os.getenv(name)
    if value is None or not value.strip():
        return fallback
    try:
        return int(value.strip())
    except ValueError:
        return fallback


def _env_bool(name: str, fallback: bool) -> bool:
    value = os.getenv(name)
    if value is None or not value.strip():
        return fallback
    return value.strip().lower() not in {"0", "false", "no", "off"}


def _env_float(name: str, fallback: float) -> float:
    value = os.getenv(name)
    if value is None or not value.strip():
        return fallback
    try:
        return float(value.strip())
    except ValueError:
        return fallback


def _apply_env_overrides(settings: Settings) -> Settings:
    settings.server.port = _env_int("BOSS_BROWSER_PORT", settings.server.port)

    state_file = os.getenv("BOSS_BROWSER_RATE_STATE_FILE", settings.rate_limit.state_file)
    settings.rate_limit.state_file = state_file.strip() if state_file and state_file.strip() else _default_rate_state_file()

    settings.rate_limit.search_per_hour = _env_int("BOSS_BROWSER_SEARCH_PER_HOUR", settings.rate_limit.search_per_hour)
    settings.rate_limit.detail_per_hour = _env_int("BOSS_BROWSER_DETAIL_PER_HOUR", settings.rate_limit.detail_per_hour)
    settings.rate_limit.action_delay_min_ms = _env_int("BOSS_BROWSER_DELAY_MIN_MS", settings.rate_limit.action_delay_min_ms)
    settings.rate_limit.action_delay_max_ms = _env_int("BOSS_BROWSER_DELAY_MAX_MS", settings.rate_limit.action_delay_max_ms)
    settings.rate_limit.cooldown_minutes_on_risk = _env_int(
        "BOSS_BROWSER_COOLDOWN_MINUTES", settings.rate_limit.cooldown_minutes_on_risk
    )

    data_dir = os.getenv("BOSS_CLI_HOME") or os.getenv("BOSS_BROWSER_AUTH_DIR") or settings.boss_cli.data_dir
    settings.boss_cli.data_dir = _resolve_runtime_path(data_dir.strip()) if data_dir and data_dir.strip() else _default_auth_data_dir()
    settings.boss_cli.cookie_source = os.getenv("BOSS_CLI_COOKIE_SOURCE", settings.boss_cli.cookie_source).strip()
    settings.boss_cli.auto_import_browser_cookies = _env_bool(
        "BOSS_CLI_AUTO_IMPORT_BROWSER_COOKIES", settings.boss_cli.auto_import_browser_cookies
    )
    settings.boss_cli.status_verify = _env_bool("BOSS_CLI_STATUS_VERIFY", settings.boss_cli.status_verify)
    settings.boss_cli.headless_cookie_completion = _env_bool(
        "BOSS_CLI_HEADLESS_COOKIE", settings.boss_cli.headless_cookie_completion
    )
    settings.boss_cli.headless_cookie_timeout_ms = _env_int(
        "BOSS_CLI_HEADLESS_COOKIE_TIMEOUT_MS", settings.boss_cli.headless_cookie_timeout_ms
    )
    settings.boss_cli.max_search_page = _env_int(
        "BOSS_CLI_MAX_SEARCH_PAGE",
        _env_int("BOSS_BROWSER_MAX_SEARCH_PAGE", settings.boss_cli.max_search_page),
    )
    settings.boss_cli.request_delay_s = _env_float("BOSS_CLI_REQUEST_DELAY_SECONDS", settings.boss_cli.request_delay_s)
    settings.boss_cli.timeout_s = _env_float("BOSS_CLI_TIMEOUT_SECONDS", settings.boss_cli.timeout_s)
    return settings


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    data = _load_yaml()
    settings = Settings(**data) if data else Settings()
    return _apply_env_overrides(settings)
