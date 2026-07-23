"""基于 jackwener/boss-cli 的 Boss 取数引擎。

本模块不再启动、连接或依赖 Chrome CDP，也不再使用 boss-agent-cli / patchright。
真实取数统一复用 kabi-boss-cli 的认证、Cookie 提取、API Client、重试与拟人化延迟能力。

风控原则：
- 不并发访问 Boss，所有真实访问通过单一异步锁串行化。
- 默认只取第一页，翻页必须通过配置显式放开。
- status 默认只做本地凭证检查，不主动请求 Boss；真实取数失败后再把登录态标记为降级。
- 命中限速、验证码、安全验证、账号异常等信号时向上层报告并停止继续访问。
"""

from __future__ import annotations

import asyncio
import base64
import json
import time
import urllib.parse
from typing import Any, Optional

import httpx
from loguru import logger

from app.tools.boss_browser.core.headless_cookie_completer import HeadlessCookieCompleter
from app.tools.boss_browser.core.settings import Settings

PRIMARY_COOKIE = "wt2"
STOKEN_COOKIE = "__zp_stoken__"
REQUIRED_COOKIE_NAMES = {PRIMARY_COOKIE, STOKEN_COOKIE, "wbg", "zp_at"}
# 持久登录身份 Cookie：扫码/浏览器登录后长期有效，进程重启与多次搜索间不失效。
# __zp_stoken__ 是网页反爬动态下发的临时令牌，重复搜索时易被上游判失效；只要身份
# Cookie 仍在，就说明用户仍处于登录态，可在不重新扫码的前提下静默重生令牌。
LOGIN_IDENTITY_COOKIES = {PRIMARY_COOKIE, "zp_at"}

# 交互翻页热路径上令牌重生的收紧参数：单页超时与加载后静置时间都比扫码补齐更短，
# 避免冷启动后再多页等待把"换一批"拖到几十秒。

# Boss 风控/安全相关上游码。boss-cli 会把部分码包装成 BossApiError，这里继续做
# 本地归类，确保不会被当成普通空结果。
_RISK_CODES = {32, 36, 121, 122}
_AUTH_EXPIRED_CODES = {37}

_QR_LOGIN_TTL_SECONDS = 240
# 单次扫码状态检查保持短长轮询；前端严格等待上一轮完成后再发下一轮，
# 兼顾扫码反馈速度与无并发堆积的风控边界。
_QR_POLL_TIMEOUT_SECONDS = 3.0
_QR_WARMUP_TIMEOUT_SECONDS = 15.0


class BossCliUnavailable(RuntimeError):
    """boss-cli 运行环境不可用。"""


class BossCliUpstreamRateLimited(RuntimeError):
    """Boss 上游返回限速信号。"""


def _missing_dependency_error(exc: Exception) -> BossCliUnavailable:
    return BossCliUnavailable(
        "未安装 kabi-boss-cli。请在 agent-tool 中执行 uv sync --extra dev，"
        "或确认 pyproject.toml/uv.lock 已包含 kabi-boss-cli。"
    )


class BossCliEngine:
    """kabi-boss-cli 的异步封装。"""

    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._memory_credential: Any | None = None
        self._lock = asyncio.Lock()
        self._auth_degraded = False
        self._last_browser_refresh_at = 0.0
        self._transient_refresh_failure = False
        self._qr_state: dict[str, Any] = {}

        try:
            from boss_cli import auth as boss_auth
            from boss_cli import constants as boss_constants
            from boss_cli.client import BossClient
            from boss_cli.exceptions import BossApiError, ParamError, RateLimitError, SessionExpiredError

            self._auth = boss_auth
            self._constants = boss_constants
            self._client_cls = BossClient
            self._BossApiError = BossApiError
            self._ParamError = ParamError
            self._RateLimitError = RateLimitError
            self._SessionExpiredError = SessionExpiredError
            self._cookie_completer = HeadlessCookieCompleter(settings, boss_constants)
        except ModuleNotFoundError as exc:
            raise _missing_dependency_error(exc) from exc

    # ── boss-cli 运行环境 ─────────────────────────────────────────────

    async def assert_browser_ready(self) -> None:
        """校验 boss-cli 依赖可用，不触达 Boss。"""
        await asyncio.to_thread(self._assert_cli_ready)

    def _assert_cli_ready(self) -> None:
        if self._auth is None or self._client_cls is None:
            raise BossCliUnavailable("boss-cli 未正确初始化。")

    async def aclose(self) -> None:
        return None

    # ── 登录态 ───────────────────────────────────────────────────────

    async def status(self) -> dict[str, Any]:
        return await asyncio.to_thread(self._status_sync)

    async def refresh_auth(self) -> dict[str, Any]:
        return await asyncio.to_thread(self._refresh_auth_sync)

    def _status_sync(self) -> dict[str, Any]:
        try:
            cred = self._get_credential()
        except Exception as exc:  # noqa: BLE001
            logger.warning(f"读取 boss-cli 登录态失败：{exc}")
            cred = None

        if not cred:
            return self._status_payload(False, [], reason="credential_missing")

        cookies = getattr(cred, "cookies", {}) or {}
        cookie_names = sorted(cookies.keys())
        has_required = self._credential_has_required_cookies(cred)
        authenticated = bool(cookies.get(PRIMARY_COOKIE)) and has_required and not self._auth_degraded
        search_authenticated = authenticated
        recommend_authenticated = authenticated
        reason = "auth_degraded" if self._auth_degraded else None

        if authenticated and self._settings.boss_cli.status_verify:
            try:
                health = self._auth.verify_credential_details(cred)
                search_authenticated = bool(health.get("search_authenticated"))
                recommend_authenticated = bool(health.get("recommend_authenticated"))
                authenticated = bool(health.get("authenticated")) and not self._auth_degraded
                reason = health.get("reason")
                if not authenticated:
                    self._auth_degraded = True
            except Exception as exc:  # noqa: BLE001
                logger.warning(f"boss-cli 登录态校验失败：{exc}")
                authenticated = False
                search_authenticated = False
                recommend_authenticated = False
                reason = str(exc)
                self._auth_degraded = True

        return self._status_payload(
            authenticated,
            cookie_names,
            search_authenticated=search_authenticated,
            recommend_authenticated=recommend_authenticated,
            reason=reason,
        )

    @staticmethod
    def _credential_has_required_cookies(cred: Any) -> bool:
        has_required = getattr(cred, "has_required_cookies", None)
        if isinstance(has_required, bool):
            return has_required
        cookies = getattr(cred, "cookies", {}) or {}
        return REQUIRED_COOKIE_NAMES.issubset(set(cookies))

    def _status_payload(
        self,
        authenticated: bool,
        cookie_names: list[str],
        *,
        search_authenticated: Optional[bool] = None,
        recommend_authenticated: Optional[bool] = None,
        reason: Optional[str] = None,
    ) -> dict[str, Any]:
        return {
            "authenticated": authenticated,
            "search_authenticated": authenticated if search_authenticated is None else search_authenticated,
            "recommend_authenticated": authenticated if recommend_authenticated is None else recommend_authenticated,
            "status": "logged_in" if authenticated else "auth_required",
            "final_url": "",
            "risk_marker": None,
            "cookie_present": cookie_names,
            "credential_store": "memory",
            "reason": reason,
        }

    def _get_credential(self) -> Any | None:
        """仅从进程内存或显式环境加载凭证，不读取本地 credential.json。"""
        if self._memory_credential is not None:
            return self._memory_credential

        if not self._settings.boss_cli.auto_import_browser_cookies:
            return None

        return self._import_browser_credential()

    def _import_browser_credential(self) -> Any | None:
        # browser-cookie3 在 macOS 读取 Chrome Cookie 时会访问 Chrome Safe Storage，
        # 从而弹出系统钥匙串授权框。所有调用路径（包括手动 refresh_auth 和认证失败回退）
        # 都必须经过此门禁，默认配置下绝不触达本机浏览器凭据。
        if not self._settings.boss_cli.auto_import_browser_cookies:
            logger.info("Boss 浏览器 Cookie 导入未启用，跳过本机浏览器凭据读取。")
            return None

        cookie_source = (self._settings.boss_cli.cookie_source or "").strip() or None
        cred = self._auth.extract_browser_credential(cookie_source=cookie_source)
        if cred:
            self._save_credential(cred)
            self._auth_degraded = not self._credential_has_required_cookies(cred)
            if not self._auth_degraded:
                self._last_browser_refresh_at = time.time()
        return cred

    def _refresh_auth_sync(self) -> dict[str, Any]:
        cred = self._import_browser_credential()
        refreshed = bool(cred and self._credential_has_required_cookies(cred))
        if refreshed:
            self._auth_degraded = False
            payload = self._status_sync()
            payload["refreshed"] = True
            payload["refresh_source"] = (self._settings.boss_cli.cookie_source or "browser").strip() or "browser"
            return payload

        # 如果浏览器导入失败，但磁盘凭证仍包含完整关键 Cookie，则至少清掉进程内降级锁。
        # 后续真实搜索会再次验证；这样避免一次临时失败让 status 永久显示未登录。
        existing = self._get_credential_without_browser_import()
        if existing and self._credential_has_required_cookies(existing):
            self._auth_degraded = False
        payload = self._status_sync()
        payload["refreshed"] = False
        payload["refresh_source"] = (self._settings.boss_cli.cookie_source or "browser").strip() or "browser"
        return payload

    def _get_credential_without_browser_import(self) -> Any | None:
        try:
            return self._memory_credential
        except Exception:  # noqa: BLE001
            return None

    def load_credential_json(self, credential_json: str | None) -> None:
        """从后端 PostgreSQL auth_state 注入凭证，仅保存在当前进程内存。"""
        text = str(credential_json or "").strip()
        if not text:
            self.clear_credential()
            return
        try:
            payload = json.loads(text)
            cookies = payload.get("cookies") if isinstance(payload, dict) else None
            if not isinstance(cookies, dict) or not cookies:
                return
            normalized = {str(k): str(v) for k, v in cookies.items()}
            current = self._memory_credential
            current_cookies = dict(getattr(current, "cookies", {}) or {}) if current is not None else {}
            same_identity = all(
                current_cookies.get(name) and current_cookies.get(name) == normalized.get(name)
                for name in LOGIN_IDENTITY_COOKIES
            )
            # 详情/搜索可能刚用持久身份 Cookie 静默重生了临时 __zp_stoken__。后端在下一次
            # 请求中可能注入数据库里的过期令牌；同一登录身份下应保留当前有效内存凭证，
            # 避免用过期值覆盖刚刷新的令牌。新账号或已降级凭证仍以数据库注入值为准。
            if (
                current is not None
                and not self._auth_degraded
                and same_identity
                and self._credential_has_required_cookies(current)
            ):
                return
            self._save_credential(self._auth.Credential(cookies=normalized))
            self._transient_refresh_failure = False
        except Exception as exc:  # noqa: BLE001
            logger.warning(f"忽略无效的 Boss 数据库凭证载荷：{exc}")

    def credential_json(self) -> str | None:
        credential = self._memory_credential
        cookies = dict(getattr(credential, "cookies", {}) or {}) if credential is not None else {}
        if not cookies:
            return None
        return json.dumps({"cookies": cookies}, ensure_ascii=False, separators=(",", ":"))

    def _save_credential(self, credential: Any) -> None:
        self._memory_credential = credential

    def clear_credential(self) -> None:
        self._memory_credential = None
        self._auth_degraded = False
        self._transient_refresh_failure = False

    def export_qr_state(self) -> dict[str, Any]:
        """Return a JSON-safe copy for an authenticated, encrypted session token."""
        return json.loads(json.dumps(self._qr_state or {}, ensure_ascii=False))

    def import_qr_state(self, state: dict[str, Any]) -> None:
        if not isinstance(state, dict) or not state.get("qr_id"):
            raise ValueError("Boss 二维码会话状态无效")
        self._qr_state = json.loads(json.dumps(state, ensure_ascii=False))

    def clear_qr_state(self) -> None:
        self._qr_state = {}

    def _credential_or_none(self) -> Any | None:
        cred = self._get_credential()
        if not cred:
            return None
        if not self._credential_has_required_cookies(cred):
            missing = getattr(cred, "missing_required_cookies", []) or []
            logger.warning(f"boss-cli 凭证缺少关键 Cookie：{missing}")
            return None
        return cred

    def _refresh_after_auth_failure(self) -> bool:
        now = time.time()
        # 避免一次失效请求触发多轮刷新，既慢又可能反复弹系统授权。
        if now - self._last_browser_refresh_at < 60:
            return False
        self._transient_refresh_failure = False
        self._last_browser_refresh_at = now
        # 优先复用已保存的持久登录 Cookie 静默重生 __zp_stoken__：用户本就处于登录态，
        # 只是临时令牌失效，没必要弹系统钥匙串读取浏览器 Cookie，更不该强制重新扫码。
        if self._refresh_stoken_from_persisted():
            return True
        if not self._settings.boss_cli.auto_import_browser_cookies:
            logger.info("Boss 搜索登录态降级，但浏览器 Cookie 导入未启用；跳过本机浏览器凭据读取。")
            return False
        logger.warning("Boss 搜索登录态降级，复用已保存登录态重生令牌未成功，回退从本机浏览器刷新 Cookie。")
        try:
            cred = self._import_browser_credential()
            refreshed = bool(cred and self._credential_has_required_cookies(cred))
            if refreshed:
                self._auth_degraded = False
                logger.info("Boss 浏览器 Cookie 刷新成功，准备重试当前请求。")
            else:
                logger.warning("Boss 浏览器 Cookie 刷新失败或缺少关键 Cookie。")
            return refreshed
        except Exception as exc:  # noqa: BLE001
            logger.warning(f"Boss 浏览器 Cookie 刷新异常：{exc}")
            return False

    def _auth_required_message(self) -> str:
        if self._settings.boss_cli.auto_import_browser_cookies:
            return "Boss 登录态失效，浏览器 Cookie 导入未获得可用凭证，请重新扫码登录。"
        return "Boss 登录态失效，请重新扫码登录。浏览器 Cookie 导入默认关闭，不会访问 Chrome Safe Storage。"

    def _has_login_identity(self, cred: Any) -> bool:
        cookies = getattr(cred, "cookies", {}) or {}
        return all(cookies.get(name) for name in LOGIN_IDENTITY_COOKIES)

    def _has_persisted_login_identity(self) -> bool:
        existing = self._get_credential_without_browser_import()
        return bool(existing and self._has_login_identity(existing))

    def _refresh_stoken_from_persisted(self) -> bool:
        """持久登录 Cookie 仍在、仅 __zp_stoken__ 失效时，复用已保存登录态静默重生令牌。

        搜索/详情请求失败常常只是网页反爬令牌过期，而 wt2/zp_at 等身份 Cookie 仍然有效，
        说明用户没有退出登录。这里带着磁盘上已保存的登录 Cookie 用 headless 浏览器访问
        Boss 网页，让前端 JS 重新下发 __zp_stoken__ 后回收并落盘，避免“明明已登录却又弹
        扫码”。补齐失败时返回 False，由上层回退到浏览器 Cookie 导入或最终引导扫码。
        """
        if not self._settings.boss_cli.headless_cookie_completion:
            return False
        existing = self._get_credential_without_browser_import()
        if not existing or not self._has_login_identity(existing):
            return False
        cookies = dict(getattr(existing, "cookies", {}) or {})
        # 移除可能过期的令牌，强制前端 JS 重新生成，避免带着失效令牌空跑一趟。
        cookies.pop(STOKEN_COOKIE, None)
        logger.info("Boss 令牌失效但持久登录 Cookie 仍在，尝试复用已保存登录态重生 __zp_stoken__。")
        try:
            completed = self._run_headless_cookie_completion(cookies, lean=True)
        except Exception as exc:  # noqa: BLE001
            self._transient_refresh_failure = True
            logger.warning(f"Boss 复用登录态重生令牌失败：{exc}")
            return False
        if not completed.get(STOKEN_COOKIE):
            return False
        credential = self._auth.Credential(cookies=completed)
        self._save_credential(credential)
        self._auth_degraded = not self._credential_has_required_cookies(credential)
        if not self._auth_degraded:
            self._transient_refresh_failure = False
            self._last_browser_refresh_at = time.time()
            logger.info("Boss 复用已保存登录态重生 __zp_stoken__ 成功，准备重试当前请求。")
        return not self._auth_degraded

    def _auth_failure_result(self, url: str, message: str | None = None) -> dict[str, Any]:
        if self._transient_refresh_failure and self._has_persisted_login_identity():
            # 一次性 Chromium 提前退出属于本地依赖故障，不是持久身份失效。保留现有
            # 凭据和登录状态，由上层返回可重试错误；绝不能因此启动二维码流程。
            self._auth_degraded = False
            return {
                "payload": None,
                "risk_marker": None,
                "url": url,
                "login_redirect": False,
                "temporary_auth_refresh_failed": True,
                "error_message": "Boss 临时安全令牌刷新失败，请稍后重试；现有凭据已保留。",
            }
        return self._auth_redirect(url, message or self._auth_required_message())

    def _exception_requires_auth(self, exc: Exception) -> bool:
        code = getattr(exc, "code", None)
        return (
            code in _AUTH_EXPIRED_CODES or isinstance(exc, self._SessionExpiredError) or self._looks_like_auth(str(exc))
        )

    # ── Boss 收藏列表 ────────────────────────────────────────────────

    async def favorite_jobs(self, page: int = 1) -> dict[str, Any]:
        async with self._lock:
            return await asyncio.to_thread(self._favorite_jobs_sync, page)

    def _favorite_jobs_sync(self, page: int) -> dict[str, Any]:
        page = max(1, page)
        max_page = self._settings.boss_cli.max_favorite_list_page
        url = self._constants.GEEK_GET_JOB_URL
        if max_page > 0 and page > max_page:
            return {
                "payload": None,
                "risk_marker": None,
                "url": url,
                "login_redirect": False,
                "local_rejected": True,
                "error_message": f"当前安全策略只允许读取 Boss 收藏列表前 {max_page} 页，已拒绝第 {page} 页请求。",
            }

        cred = self._credential_or_none()
        if not cred:
            refreshed = self._refresh_after_auth_failure()
            if refreshed:
                cred = self._credential_or_none()
            if not cred:
                return self._auth_failure_result(url)
        return self._favorite_jobs_with_credential(cred, url, page, allow_refresh=True)

    def _favorite_jobs_with_credential(self, cred: Any, url: str, page: int, *, allow_refresh: bool) -> dict[str, Any]:
        try:
            with self._client_cls(
                cred,
                timeout=self._settings.boss_cli.timeout_s,
                request_delay=self._settings.boss_cli.request_delay_s,
                max_retries=self._settings.boss_cli.max_retries,
            ) as client:
                # boss-cli 暂未暴露“感兴趣/收藏列表”的公开方法，但其统一 _get 仍负责
                # 请求抖动、退避、响应码和 SessionExpired/RateLimit 分类。tag 由服务端配置固定，
                # 不接受前端透传，避免枚举互动列表制造额外请求。
                raw = client._get(  # noqa: SLF001
                    url,
                    params={
                        "page": page,
                        "tag": self._settings.boss_cli.favorite_list_tag,
                        "isActive": "true",
                    },
                    action="感兴趣职位",
                )
        except self._SessionExpiredError:
            if allow_refresh and self._refresh_after_auth_failure():
                refreshed = self._credential_or_none()
                if refreshed:
                    return self._favorite_jobs_with_credential(refreshed, url, page, allow_refresh=False)
            return self._auth_failure_result(url)
        except self._RateLimitError as exc:
            return self._rate_limited_result(url, exc)
        except self._BossApiError as exc:
            if self._exception_requires_auth(exc):
                if allow_refresh and self._refresh_after_auth_failure():
                    refreshed = self._credential_or_none()
                    if refreshed:
                        return self._favorite_jobs_with_credential(refreshed, url, page, allow_refresh=False)
                return self._auth_failure_result(url)
            return self._classify_exception(url, exc)
        except Exception as exc:  # noqa: BLE001
            return self._error_result(url, exc)
        return self._classify_payload(raw, url)

    # ── 搜索 ─────────────────────────────────────────────────────────

    async def search(
        self,
        query: str,
        city: str = "",
        page: int = 1,
        extra: Optional[dict] = None,
    ) -> dict[str, Any]:
        async with self._lock:
            return await asyncio.to_thread(self._search_sync, query, city, page, extra or {})

    def _search_sync(self, query: str, city: str, page: int, extra: dict) -> dict[str, Any]:
        page = max(1, page)
        max_search_page = max(1, self._settings.boss_cli.max_search_page)
        url = self._constants.JOB_SEARCH_URL
        if page > max_search_page:
            return {
                "payload": None,
                "risk_marker": None,
                "url": url,
                "login_redirect": False,
                "local_rejected": True,
                "error_message": f"当前安全策略只允许搜索到第 {max_search_page} 页，已拒绝第 {page} 页请求。",
            }

        cred = self._credential_or_none()
        if not cred:
            refreshed = self._refresh_after_auth_failure()
            if refreshed:
                cred = self._credential_or_none()
            if not cred:
                return self._auth_failure_result(url)

        return self._search_with_credential(cred, url, query, city, page, extra, allow_refresh=True)

    def _search_with_credential(
        self, cred: Any, url: str, query: str, city: str, page: int, extra: dict, *, allow_refresh: bool
    ) -> dict[str, Any]:
        try:
            with self._client_cls(
                cred,
                timeout=self._settings.boss_cli.timeout_s,
                request_delay=self._settings.boss_cli.request_delay_s,
                max_retries=self._settings.boss_cli.max_retries,
            ) as client:
                raw = client.search_jobs(
                    query=query or "",
                    city=self._resolve_city_code(city),
                    page=page,
                    experience=self._resolve_filter(extra.get("experience"), self._constants.EXP_CODES),
                    degree=self._resolve_filter(extra.get("degree"), self._constants.DEGREE_CODES),
                    salary=self._resolve_filter(extra.get("salary"), self._constants.SALARY_CODES),
                    industry=self._resolve_filter(extra.get("industry"), self._constants.INDUSTRY_CODES),
                    scale=self._resolve_filter(extra.get("scale"), self._constants.SCALE_CODES),
                    stage=self._resolve_filter(extra.get("stage"), self._constants.STAGE_CODES),
                    job_type=self._resolve_filter(
                        extra.get("job_type") or extra.get("jobType"), self._constants.JOB_TYPE_CODES
                    ),
                )
        except self._SessionExpiredError:
            if allow_refresh and self._refresh_after_auth_failure():
                refreshed = self._credential_or_none()
                if refreshed:
                    return self._search_with_credential(refreshed, url, query, city, page, extra, allow_refresh=False)
            return self._auth_failure_result(url)
        except self._RateLimitError as exc:
            return self._rate_limited_result(url, exc)
        except self._BossApiError as exc:
            if self._exception_requires_auth(exc):
                if allow_refresh and self._refresh_after_auth_failure():
                    refreshed = self._credential_or_none()
                    if refreshed:
                        return self._search_with_credential(
                            refreshed, url, query, city, page, extra, allow_refresh=False
                        )
                return self._auth_failure_result(url)
            return self._classify_exception(url, exc)
        except Exception as exc:  # noqa: BLE001
            return self._error_result(url, exc)

        return self._classify_payload(raw, url)

    def _resolve_city_code(self, city: str) -> str:
        value = (city or "").strip()
        if not value:
            return "100010000"
        if value.isdigit():
            return value
        city_codes = self._settings.boss.city_codes or {}
        for candidate in self._city_name_candidates(value):
            code = city_codes.get(candidate) or getattr(self._constants, "CITY_CODES", {}).get(candidate)
            if code:
                return code
        try:
            from boss_cli.client import resolve_city

            return resolve_city(value)
        except Exception:  # noqa: BLE001
            return city_codes.get("全国", "100010000")

    @staticmethod
    def _city_name_candidates(value: str) -> list[str]:
        candidates = [value]
        for suffix in ("市", "地区", "盟"):
            if value.endswith(suffix) and len(value) > len(suffix):
                candidates.append(value[: -len(suffix)])
        result: list[str] = []
        seen: set[str] = set()
        for candidate in candidates:
            if candidate and candidate not in seen:
                seen.add(candidate)
                result.append(candidate)
        return result

    @staticmethod
    def _resolve_filter(value: Any, mapping: dict[str, str]) -> str | None:
        if value is None:
            return None
        text = str(value).strip()
        if not text:
            return None
        if text in mapping:
            return mapping[text]
        if text in set(mapping.values()):
            return text
        return None

    # ── 详情 ─────────────────────────────────────────────────────────

    async def detail(self, security_id: str = "", url: str = "") -> dict[str, Any]:
        async with self._lock:
            return await asyncio.to_thread(self._detail_sync, security_id, url)

    def _detail_sync(self, security_id: str, url: str) -> dict[str, Any]:
        endpoint = self._constants.JOB_DETAIL_URL
        security_id = (security_id or "").strip() or self._extract_query_param(url, "securityId")
        lid = self._extract_query_param(url, "lid")
        if not security_id:
            return {
                "payload": None,
                "risk_marker": None,
                "url": url or endpoint,
                "login_redirect": False,
                "local_rejected": True,
                "error_message": "缺少 securityId，无法通过 boss-cli 安全加载岗位详情。",
            }

        detail_url = url or endpoint
        cred = self._credential_or_none()
        if not cred:
            refreshed = self._refresh_after_auth_failure()
            if refreshed:
                cred = self._credential_or_none()
            if not cred:
                return self._auth_failure_result(detail_url)

        return self._detail_with_credential(
            cred,
            detail_url,
            security_id,
            lid,
            allow_refresh=True,
        )

    def _detail_with_credential(
        self,
        cred: Any,
        url: str,
        security_id: str,
        lid: str,
        *,
        allow_refresh: bool,
    ) -> dict[str, Any]:
        try:
            with self._client_cls(
                cred,
                timeout=self._settings.boss_cli.timeout_s,
                request_delay=self._settings.boss_cli.request_delay_s,
                max_retries=self._settings.boss_cli.max_retries,
            ) as client:
                raw = client.get_job_detail(security_id=security_id, lid=lid)
        except self._SessionExpiredError:
            if allow_refresh and self._refresh_after_auth_failure():
                refreshed = self._credential_or_none()
                if refreshed:
                    return self._detail_with_credential(
                        refreshed,
                        url,
                        security_id,
                        lid,
                        allow_refresh=False,
                    )
            return self._auth_failure_result(url)
        except self._RateLimitError as exc:
            return self._rate_limited_result(url, exc)
        except self._BossApiError as exc:
            if self._exception_requires_auth(exc):
                if allow_refresh and self._refresh_after_auth_failure():
                    refreshed = self._credential_or_none()
                    if refreshed:
                        return self._detail_with_credential(
                            refreshed,
                            url,
                            security_id,
                            lid,
                            allow_refresh=False,
                        )
                return self._auth_failure_result(url)
            return self._classify_exception(url, exc)
        except Exception as exc:  # noqa: BLE001
            return self._error_result(url, exc)

        return self._classify_payload(raw, url)

    @staticmethod
    def _extract_query_param(url: str, name: str) -> str:
        if not url:
            return ""
        try:
            parsed = urllib.parse.urlparse(url)
            values = urllib.parse.parse_qs(parsed.query).get(name)
            return values[0].strip() if values else ""
        except Exception:  # noqa: BLE001
            return ""

    # ── 求职画像 ─────────────────────────────────────────────────────

    async def profile(self) -> dict[str, Any]:
        async with self._lock:
            return await asyncio.to_thread(self._profile_sync)

    def _profile_sync(self) -> dict[str, Any]:
        endpoint = self._constants.RESUME_BASEINFO_URL
        cred = self._credential_or_none()
        if not cred:
            return {"captures": [], "risk_marker": None, "url": endpoint, "login_redirect": True}

        captures: list[tuple[str, Any]] = []
        try:
            with self._client_cls(
                cred,
                timeout=self._settings.boss_cli.timeout_s,
                request_delay=self._settings.boss_cli.request_delay_s,
                max_retries=self._settings.boss_cli.max_retries,
            ) as client:
                for endpoint_url, fetch in (
                    (self._constants.RESUME_BASEINFO_URL, client.get_resume_baseinfo),
                    (self._constants.RESUME_EXPECT_URL, client.get_resume_expect),
                    (self._constants.RESUME_STATUS_URL, client.get_resume_status),
                ):
                    payload = fetch()
                    if payload is not None:
                        captures.append((endpoint_url, payload))
        except self._SessionExpiredError:
            return {"captures": [], "risk_marker": None, "url": endpoint, "login_redirect": True}
        except self._RateLimitError as exc:
            return self._rate_limited_result(endpoint, exc) | {"captures": captures}
        except self._BossApiError as exc:
            classified = self._classify_exception(endpoint, exc)
            classified["captures"] = captures
            return classified
        except Exception as exc:  # noqa: BLE001
            classified = self._error_result(endpoint, exc)
            classified["captures"] = captures
            return classified

        if captures:
            self._auth_degraded = False
        return {"captures": captures, "risk_marker": None, "url": endpoint, "login_redirect": False}

    # ── 二维码登录（HTTP QR，不启动独立浏览器）────────────────────────

    async def start_qr_login(self) -> dict[str, Any]:
        async with self._lock:
            return await asyncio.to_thread(self._qr_start_sync)

    def _qr_start_sync(self) -> dict[str, Any]:
        with self._qr_client() as client:
            resp = client.post(self._constants.QR_RANDKEY_URL)
            resp.raise_for_status()
            payload = resp.json()
            if payload.get("code") != 0:
                raise RuntimeError(f"获取 Boss 二维码会话失败：{payload.get('message') or payload}")
            data = payload.get("zpData") or {}
            qr_id = str(data.get("qrId") or "")
            if not qr_id:
                raise RuntimeError("获取 Boss 二维码会话失败：响应缺少 qrId。")

            image_resp = client.get(self._constants.QR_CODE_URL, params={"content": qr_id})
            image_resp.raise_for_status()
            image_bytes = image_resp.content
            now = time.time()
            self._qr_state = {
                "status": "qr_ready",
                "qr_id": qr_id,
                "cookies": dict(client.cookies),
                "created_at": now,
                "expires_at": now + _QR_LOGIN_TTL_SECONDS,
                "image_base64": base64.b64encode(image_bytes).decode("ascii"),
                "image_mime": image_resp.headers.get("content-type") or "image/png",
                "qr_version": 1,
            }

        return {
            "status": "qr_ready",
            "image_base64": self._qr_state["image_base64"],
            "image_mime": self._qr_state["image_mime"],
            "login_url": "boss-cli-http-qr",
            "qr_version": 1,
        }

    async def poll_qr_login(self) -> dict[str, Any]:
        async with self._lock:
            return await asyncio.to_thread(self._qr_poll_sync)

    def _qr_poll_sync(self) -> dict[str, Any]:
        state = dict(self._qr_state or {})
        if not state or not state.get("qr_id"):
            base = self._status_sync()
            base["status"] = "auth_required"
            return base

        if time.time() > float(state.get("expires_at") or 0):
            base = self._status_sync()
            base["status"] = "qr_expired"
            return base

        qr_id = str(state["qr_id"])
        with self._qr_client(cookies=state.get("cookies") or {}) as client:
            scanned = self._qr_scan(client, qr_id)
            self._qr_state["cookies"] = dict(client.cookies)
            if not scanned:
                return self._qr_waiting_payload()

            confirmed = self._qr_confirm(client, qr_id)
            self._qr_state["cookies"] = dict(client.cookies)
            if not confirmed:
                return self._qr_waiting_payload(scanned=True)

            credential = self._qr_dispatch(client, qr_id)
            if not self._credential_has_required_cookies(credential):
                credential = self._complete_qr_credential(credential)
            self._save_credential(credential)
            self._auth_degraded = not self._credential_has_required_cookies(credential)

        base = self._status_sync()
        if base.get("authenticated"):
            base["status"] = "logged_in"
            base["credential_json"] = self.credential_json()
            self._qr_state = {"status": "logged_in"}
        else:
            base["status"] = "auth_required"
            base["reason"] = base.get("reason") or "qr_login_missing_required_cookies"
            base["error"] = (
                "二维码登录已保存部分 Cookie，但缺少 __zp_stoken__ 等关键 Web Cookie。"
                "请先在本机常用浏览器登录 Boss 直聘，再重试登录状态检查以导入浏览器 Cookie。"
            )
            # 扫码已完成但登录态不完整，属于终态。清空 QR 会话，避免后续轮询再次
            # 触发 scan/confirm/dispatch 重复访问 Boss，规避风控。
            self._qr_state = {"status": "auth_required", "reason": base["reason"]}
        return base

    def _qr_client(self, cookies: dict[str, str] | None = None) -> httpx.Client:
        return httpx.Client(
            base_url=self._constants.BASE_URL,
            headers=dict(self._constants.HEADERS),
            cookies=cookies or {},
            follow_redirects=True,
            timeout=httpx.Timeout(30, read=_QR_POLL_TIMEOUT_SECONDS),
        )

    def _qr_scan(self, client: httpx.Client, qr_id: str) -> bool:
        try:
            resp = client.get(self._constants.QR_SCAN_URL, params={"uuid": qr_id}, timeout=_QR_POLL_TIMEOUT_SECONDS)
            resp.raise_for_status()
            return bool(resp.json().get("scaned"))
        except httpx.ReadTimeout:
            return False

    def _qr_confirm(self, client: httpx.Client, qr_id: str) -> bool:
        try:
            resp = client.get(
                self._constants.QR_SCAN_LOGIN_URL, params={"qrId": qr_id}, timeout=_QR_POLL_TIMEOUT_SECONDS
            )
            resp.raise_for_status()
            return resp.json().get("login") is True
        except httpx.ReadTimeout:
            return False

    def _qr_dispatch(self, client: httpx.Client, qr_id: str) -> Any:
        resp = client.get(
            self._constants.QR_DISPATCHER_URL,
            params={"qrId": qr_id, "pk": "header-login"},
            timeout=_QR_POLL_TIMEOUT_SECONDS,
        )
        resp.raise_for_status()
        cookies: dict[str, str] = {}
        for name, value in resp.cookies.items():
            cookies[name] = value
        for name, value in client.cookies.items():
            cookies[name] = value

        try:
            warmup = client.get("/", timeout=_QR_WARMUP_TIMEOUT_SECONDS)
            warmup.raise_for_status()
            for name, value in warmup.cookies.items():
                cookies[name] = value
            for name, value in client.cookies.items():
                cookies[name] = value
        except httpx.HTTPError as exc:
            logger.debug(f"Boss QR warmup 失败：{exc}")

        if not cookies:
            raise RuntimeError("Boss 二维码登录未返回 Cookie。")
        return self._auth.Credential(cookies=cookies)

    def _complete_qr_credential(self, credential: Any) -> Any:
        """二维码 dispatch 后若缺少 __zp_stoken__ 等 Web 关键 Cookie，用 headless 浏览器补齐。

        __zp_stoken__ 是 Boss 网页前端 JS 反爬动态生成的安全令牌，纯 HTTP 的二维码
        dispatch/warmup 无法获得，只有真实浏览器加载 Web 页面执行前端 JS 才会下发。
        这里带着已 dispatch 的 Cookie 用 headless Chromium 访问 Boss Web 页让其生成
        令牌后回收，避免扫码成功却长期拿不到可用搜索登录态。补齐失败时保持原凭证，由
        上层落到 auth_required 终态并提示改用浏览器导入 Cookie，不再无限轮询。
        """
        if not self._settings.boss_cli.headless_cookie_completion:
            logger.warning("Boss 二维码缺少关键 Cookie，但 headless 补齐未启用。")
            return credential
        cookies = dict(getattr(credential, "cookies", {}) or {})
        try:
            completed = self._run_headless_cookie_completion(cookies)
        except Exception as exc:  # noqa: BLE001
            logger.warning(f"Boss headless Cookie 补齐失败：{exc}")
            return credential
        if completed and completed != cookies:
            return self._auth.Credential(cookies=completed)
        return credential

    def _run_headless_cookie_completion(self, cookies: dict[str, str], *, lean: bool = False) -> dict[str, str]:
        """用 headless Chromium 让前端 JS 下发 __zp_stoken__ 后回收 Cookie。

        lean=True 用于交互翻页热路径上的令牌静默重生：先访问首页，令牌仍缺失时再访问
        已登录岗位页；两次访问均使用 domcontentloaded 和收紧的超时，避免冷启动把
        “换一批”拖到几十秒。lean=False 用于扫码登录后的一次性补齐，可继续登录页兜底。
        """
        return self._cookie_completer.complete(cookies, lean=lean)

    def _qr_waiting_payload(self, *, scanned: bool = False) -> dict[str, Any]:
        base = self._status_payload(False, [], reason="qr_waiting_confirm" if scanned else "qr_waiting_scan")
        base["status"] = "qr_waiting"
        if self._qr_state.get("image_base64"):
            base["image_base64"] = self._qr_state.get("image_base64")
            base["image_mime"] = self._qr_state.get("image_mime", "image/png")
            base["qr_version"] = self._qr_state.get("qr_version")
        return base

    # ── 结果分类辅助 ─────────────────────────────────────────────────

    def _classify_payload(self, raw: Any, url: str) -> dict[str, Any]:
        if isinstance(raw, dict):
            code = raw.get("code")
            if code in _RISK_CODES:
                return self._risk_result(url, RuntimeError(raw.get("message") or "账户存在异常行为"))
            if code in _AUTH_EXPIRED_CODES:
                return self._auth_redirect(url)
            if isinstance(code, int) and code != 0:
                return {
                    "payload": None,
                    "risk_marker": None,
                    "url": url,
                    "login_redirect": False,
                    "error_message": raw.get("message") or f"Boss 上游返回异常 code={code}",
                }
        self._auth_degraded = False
        self._transient_refresh_failure = False
        return {"payload": raw, "risk_marker": None, "url": url, "login_redirect": False}

    def _classify_exception(self, url: str, exc: Exception) -> dict[str, Any]:
        code = getattr(exc, "code", None)
        message = str(exc)
        if code in _AUTH_EXPIRED_CODES or isinstance(exc, self._SessionExpiredError) or self._looks_like_auth(message):
            return self._auth_redirect(url)
        if code in _RISK_CODES or self._looks_like_risk(message):
            return self._risk_result(url, exc)
        if isinstance(exc, self._ParamError):
            return self._error_result(url, exc)
        return self._error_result(url, exc)

    @staticmethod
    def _looks_like_auth(message: str) -> bool:
        lowered = message.lower()
        markers = ("auth redirect", "not authenticated", "未登录", "登录态", "__zp_stoken__", "session expired")
        return any(marker in lowered for marker in markers)

    def _looks_like_risk(self, message: str) -> bool:
        lowered = message.lower()
        markers = list(self._settings.risk.page_markers or []) + [
            "安全系统",
            "访问异常",
            "环境异常",
            "操作过于频繁",
            "安全验证",
            "验证码",
            "captcha",
            "security",
        ]
        return any(str(marker).lower() in lowered for marker in markers if marker)

    def _auth_redirect(self, url: str, message: str | None = None) -> dict[str, Any]:
        self._auth_degraded = True
        return {
            "payload": None,
            "risk_marker": None,
            "url": url,
            "login_redirect": True,
            "error_message": message or "Boss 未登录或登录态失效，请扫码登录。",
        }

    @staticmethod
    def _risk_result(url: str, exc: Exception) -> dict[str, Any]:
        return {"payload": None, "risk_marker": f"account_risk:{exc}", "url": url, "login_redirect": False}

    @staticmethod
    def _rate_limited_result(url: str, exc: Exception) -> dict[str, Any]:
        return {
            "payload": None,
            "risk_marker": None,
            "url": url,
            "login_redirect": False,
            "rate_limited": True,
            "error_message": str(exc) or "Boss 上游请求过于频繁，请稍后再试。",
        }

    @staticmethod
    def _error_result(url: str, exc: Exception) -> dict[str, Any]:
        return {
            "payload": None,
            "risk_marker": None,
            "url": url,
            "login_redirect": False,
            "error_message": str(exc),
        }


_engine: Optional[BossCliEngine] = None


def get_engine(settings: Optional[Settings] = None) -> BossCliEngine:
    global _engine
    if _engine is None:
        from app.tools.boss_browser.core.settings import get_settings

        _engine = BossCliEngine(settings or get_settings())
    return _engine
