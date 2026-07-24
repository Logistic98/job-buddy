"""配置加载：YAML 基线 + 环境变量覆盖。敏感信息只走环境变量。"""

from __future__ import annotations

import os
from functools import lru_cache
from pathlib import Path
from typing import Any
from urllib.parse import quote

import yaml
from pydantic import BaseModel, Field


class BossConfig(BaseModel):
    # Boss 城市名→城市编码（LID）映射。Boss 搜索用数字编码，不认中文名。
    city_codes: dict[str, str] = Field(default_factory=dict)


class RateLimitConfig(BaseModel):
    search_per_hour: int = 15
    search_per_day: int = 120
    # 0 表示不设置本地硬配额；仍保留串行抖动和上游风控信号停手。
    favorite_list_per_hour: int = 0
    favorite_list_per_day: int = 0
    detail_per_hour: int = 12
    detail_per_day: int = 60
    action_delay_min_ms: int = 800
    action_delay_max_ms: int = 2000
    cooldown_minutes_on_risk: int = 30
    consecutive_failure_backstop: int = 5
    # 限速/风控状态只存 Redis；未配置时退化为当前进程内存，不写本地文件。
    redis_url: str = ""
    state_file: str = ""


class RiskConfig(BaseModel):
    page_markers: list[str] = Field(default_factory=list)


class BossCliConfig(BaseModel):
    """jackwener/boss-cli 取数引擎配置。

    凭证由后端 PostgreSQL auth_state 通过请求载荷注入，Tool 仅在内存中使用。
    status_verify 默认关闭，避免频繁登录态检查触发真实 Boss 请求；真实搜索/详情失败时
    再把登录态标记为降级并引导重新导入 Cookie。
    """

    cookie_source: str = ""
    # 默认禁止所有路径读取本机浏览器 Cookie，避免 browser-cookie3 在 macOS 触发
    # “Chrome Safe Storage” 密码弹窗。需要导入时必须通过环境变量显式开启。
    auto_import_browser_cookies: bool = False
    status_verify: bool = False
    # 默认允许搜索前若干页，支撑"换一批"候选池跨页抓取；按页数本地拦截的机制保留。
    max_search_page: int = 5
    # Boss“感兴趣/收藏”列表使用 interaction/geekGetJob。tag 属于易漂移协议，
    # 通过环境变量可覆盖；0 表示不设置本地页数上限，仍只允许用户手动翻页。
    favorite_list_tag: int = 4
    max_favorite_list_page: int = 0
    request_delay_s: float = 1.2
    # 交互搜索采用较短单次超时并保留一次重试，避免 boss-cli 默认 3×30 秒阻塞上游链路。
    timeout_s: float = 20.0
    max_retries: int = 2
    # 二维码 dispatch 缺少 __zp_stoken__ 等 Web 关键 Cookie 时，用 headless 浏览器
    # 访问 Boss 网页让前端 JS 生成该安全令牌后回收。关闭后扫码登录可能因缺少 stoken
    # 而无法获得可用搜索登录态。
    headless_cookie_completion: bool = True
    headless_cookie_timeout_ms: int = 8000


class Settings(BaseModel):
    app_name: str = "agent_tool_boss_browser"
    boss: BossConfig = Field(default_factory=BossConfig)
    boss_cli: BossCliConfig = Field(default_factory=BossCliConfig)
    rate_limit: RateLimitConfig = Field(default_factory=RateLimitConfig)
    risk: RiskConfig = Field(default_factory=RiskConfig)


def _module_dir() -> Path:
    # agent-tool/app/tools/boss_browser
    return Path(__file__).resolve().parents[1]


def _load_yaml() -> dict[str, Any]:
    config_path = Path(os.getenv("AGENT_TOOL_BOSS_CONFIG", str(_module_dir() / "config" / "config.yaml")))
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


def _default_redis_url() -> str:
    host = os.getenv("SPRING_REDIS_HOST", "").strip()
    if not host:
        return ""
    port = os.getenv("SPRING_REDIS_PORT", "6379").strip() or "6379"
    database = os.getenv("SPRING_REDIS_DATABASE", "0").strip() or "0"
    password = os.getenv("SPRING_REDIS_PASSWORD", "")
    auth = f":{quote(password, safe='')}@" if password else ""
    return f"redis://{auth}{host}:{port}/{database}"


def _env_float(name: str, fallback: float) -> float:
    value = os.getenv(name)
    if value is None or not value.strip():
        return fallback
    try:
        return float(value.strip())
    except ValueError:
        return fallback


def _apply_env_overrides(settings: Settings) -> Settings:
    settings.rate_limit.redis_url = (
        os.getenv("BOSS_CLI_RATE_REDIS_URL")
        or os.getenv("AGENT_TOOL_REDIS_URL")
        or settings.rate_limit.redis_url
        or _default_redis_url()
    ).strip()
    settings.rate_limit.state_file = ""

    settings.rate_limit.search_per_hour = _env_int("BOSS_CLI_SEARCH_PER_HOUR", settings.rate_limit.search_per_hour)
    settings.rate_limit.search_per_day = _env_int("BOSS_CLI_SEARCH_PER_DAY", settings.rate_limit.search_per_day)
    settings.rate_limit.favorite_list_per_hour = _env_int(
        "BOSS_CLI_FAVORITE_LIST_PER_HOUR", settings.rate_limit.favorite_list_per_hour
    )
    settings.rate_limit.favorite_list_per_day = _env_int(
        "BOSS_CLI_FAVORITE_LIST_PER_DAY", settings.rate_limit.favorite_list_per_day
    )
    settings.rate_limit.detail_per_hour = _env_int("BOSS_CLI_DETAIL_PER_HOUR", settings.rate_limit.detail_per_hour)
    settings.rate_limit.detail_per_day = _env_int("BOSS_CLI_DETAIL_PER_DAY", settings.rate_limit.detail_per_day)
    settings.rate_limit.action_delay_min_ms = _env_int("BOSS_CLI_DELAY_MIN_MS", settings.rate_limit.action_delay_min_ms)
    settings.rate_limit.action_delay_max_ms = _env_int("BOSS_CLI_DELAY_MAX_MS", settings.rate_limit.action_delay_max_ms)
    settings.rate_limit.cooldown_minutes_on_risk = _env_int(
        "BOSS_CLI_COOLDOWN_MINUTES", settings.rate_limit.cooldown_minutes_on_risk
    )

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
    settings.boss_cli.max_search_page = _env_int("BOSS_CLI_MAX_SEARCH_PAGE", settings.boss_cli.max_search_page)
    settings.boss_cli.favorite_list_tag = _env_int("BOSS_CLI_FAVORITE_LIST_TAG", settings.boss_cli.favorite_list_tag)
    settings.boss_cli.max_favorite_list_page = _env_int(
        "BOSS_CLI_MAX_FAVORITE_LIST_PAGE", settings.boss_cli.max_favorite_list_page
    )
    settings.boss_cli.request_delay_s = _env_float("BOSS_CLI_REQUEST_DELAY_SECONDS", settings.boss_cli.request_delay_s)
    settings.boss_cli.timeout_s = _env_float("BOSS_CLI_TIMEOUT_SECONDS", settings.boss_cli.timeout_s)
    settings.boss_cli.max_retries = max(
        1,
        min(3, _env_int("BOSS_CLI_MAX_RETRIES", settings.boss_cli.max_retries)),
    )
    return settings


@lru_cache(maxsize=1)
def get_settings() -> Settings:
    data = _load_yaml()
    settings = Settings(**data) if data else Settings()
    return _apply_env_overrides(settings)
