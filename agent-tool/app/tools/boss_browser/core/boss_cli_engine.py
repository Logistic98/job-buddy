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
import time
import urllib.parse
from pathlib import Path
from typing import Any, Optional

import httpx
from loguru import logger

from app.tools.boss_browser.core.settings import Settings

PRIMARY_COOKIE = "wt2"
STOKEN_COOKIE = "__zp_stoken__"
REQUIRED_COOKIE_NAMES = {PRIMARY_COOKIE, STOKEN_COOKIE, "wbg", "zp_at"}

# Boss 风控/安全相关上游码。boss-cli 会把部分码包装成 BossApiError，这里继续做
# 本地归类，确保不会被当成普通空结果。
_RISK_CODES = {32, 36, 121, 122}
_AUTH_EXPIRED_CODES = {37}

_QR_LOGIN_TTL_SECONDS = 240
_QR_POLL_TIMEOUT_SECONDS = 6.0
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
        self._data_dir = Path(settings.boss_cli.data_dir)
        self._data_dir.mkdir(parents=True, exist_ok=True)
        self._credential_file = self._data_dir / "credential.json"
        self._lock = asyncio.Lock()
        self._auth_degraded = False
        self._last_browser_refresh_at = 0.0
        self._qr_state: dict[str, Any] = {}

        try:
            self._configure_boss_cli_storage()
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
        except ModuleNotFoundError as exc:
            raise _missing_dependency_error(exc) from exc

    # ── boss-cli 运行环境 ─────────────────────────────────────────────

    def _configure_boss_cli_storage(self) -> None:
        """把 boss-cli 凭证文件重定向到项目共享目录，而不改 HOME。

        boss-cli 默认使用 Path.home() / ".config/boss-cli/credential.json"。直接改 HOME
        会破坏 browser-cookie3 从用户真实浏览器目录读取 Cookie 的能力，因此这里只修改
        boss_cli.constants / boss_cli.auth 已导出的 CONFIG_DIR 与 CREDENTIAL_FILE。
        """
        try:
            from boss_cli import constants as boss_constants
        except ModuleNotFoundError as exc:
            raise _missing_dependency_error(exc) from exc

        boss_constants.CONFIG_DIR = self._data_dir
        boss_constants.CREDENTIAL_FILE = self._credential_file

        # auth 模块如果已经被其他测试或调用导入，也要同步覆盖其模块级常量。
        try:
            from boss_cli import auth as boss_auth

            boss_auth.CONFIG_DIR = self._data_dir
            boss_auth.CREDENTIAL_FILE = self._credential_file
        except ModuleNotFoundError as exc:
            raise _missing_dependency_error(exc) from exc

    async def assert_browser_ready(self) -> None:
        """兼容旧服务层命名：这里只校验 boss-cli 依赖可用，不触达 Boss。"""
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
            "credential_file": str(self._credential_file),
            "reason": reason,
        }

    def _get_credential(self) -> Any | None:
        """按项目配置加载 boss-cli 凭证，兼容不同 kabi-boss-cli 版本的认证 API。"""
        cred = self._auth.load_credential()
        if cred:
            return cred

        # 部分早期/本地版本曾提供 load_from_env；kabi-boss-cli 0.3.6 已移除该方法。
        # 这里仅在方法存在时调用，避免岗位搜索因 AttributeError 直接失败。
        load_from_env = getattr(self._auth, "load_from_env", None)
        if callable(load_from_env):
            cred = load_from_env()
            if cred:
                self._auth.save_credential(cred)
                return cred

        if not self._settings.boss_cli.auto_import_browser_cookies:
            return None

        return self._import_browser_credential()

    def _import_browser_credential(self) -> Any | None:
        cookie_source = (self._settings.boss_cli.cookie_source or "").strip() or None
        extracted = self._auth.extract_browser_credential(cookie_source=cookie_source)
        cred = extracted[0] if isinstance(extracted, tuple) else extracted
        if cred:
            self._auth.save_credential(cred)
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
            return self._auth.load_credential()
        except Exception:  # noqa: BLE001
            return None

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
        # 避免一次失效请求触发多轮浏览器 Cookie 读取，既慢又可能反复弹系统授权。
        if now - self._last_browser_refresh_at < 60:
            return False
        self._last_browser_refresh_at = now
        logger.warning("Boss 搜索登录态降级，尝试从本机浏览器刷新 Cookie 后重试一次。")
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
                return self._auth_redirect(url, "Boss 登录态失效，且未能从本机浏览器刷新可用 Cookie。请确认 Chrome 已登录 Boss，并允许读取 Chrome Safe Storage，或重新扫码登录。")

        return self._search_with_credential(cred, url, query, city, page, extra, allow_refresh=True)

    def _search_with_credential(self, cred: Any, url: str, query: str, city: str, page: int, extra: dict, *, allow_refresh: bool) -> dict[str, Any]:
        try:
            with self._client_cls(
                cred,
                timeout=self._settings.boss_cli.timeout_s,
                request_delay=self._settings.boss_cli.request_delay_s,
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
                    job_type=self._resolve_filter(extra.get("job_type") or extra.get("jobType"), self._constants.JOB_TYPE_CODES),
                )
        except self._SessionExpiredError:
            if allow_refresh and self._refresh_after_auth_failure():
                refreshed = self._credential_or_none()
                if refreshed:
                    return self._search_with_credential(refreshed, url, query, city, page, extra, allow_refresh=False)
            return self._auth_redirect(url, "Boss 登录态失效，自动刷新浏览器 Cookie 后仍不可用。请确认 Chrome 已登录 Boss，并允许读取 Chrome Safe Storage，或重新扫码登录。")
        except self._RateLimitError as exc:
            return self._rate_limited_result(url, exc)
        except self._BossApiError as exc:
            classified = self._classify_exception(url, exc)
            if allow_refresh and classified.get("login_redirect") and self._refresh_after_auth_failure():
                refreshed = self._credential_or_none()
                if refreshed:
                    return self._search_with_credential(refreshed, url, query, city, page, extra, allow_refresh=False)
            return classified
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

        cred = self._credential_or_none()
        if not cred:
            return self._auth_redirect(url or endpoint)

        try:
            with self._client_cls(
                cred,
                timeout=self._settings.boss_cli.timeout_s,
                request_delay=self._settings.boss_cli.request_delay_s,
            ) as client:
                raw = client.get_job_detail(security_id=security_id, lid=lid)
        except self._SessionExpiredError:
            return self._auth_redirect(url or endpoint)
        except self._RateLimitError as exc:
            return self._rate_limited_result(url or endpoint, exc)
        except self._BossApiError as exc:
            return self._classify_exception(url or endpoint, exc)
        except Exception as exc:  # noqa: BLE001
            return self._error_result(url or endpoint, exc)

        return self._classify_payload(raw, url or endpoint)

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
            self._auth.save_credential(credential)
            self._auth_degraded = not self._credential_has_required_cookies(credential)

        base = self._status_sync()
        if base.get("authenticated"):
            base["status"] = "logged_in"
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
            resp = client.get(self._constants.QR_SCAN_LOGIN_URL, params={"qrId": qr_id}, timeout=_QR_POLL_TIMEOUT_SECONDS)
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

    def _run_headless_cookie_completion(self, cookies: dict[str, str]) -> dict[str, str]:
        try:
            from playwright.sync_api import sync_playwright
        except ImportError as exc:
            raise RuntimeError(
                "缺少 Playwright，无法补齐 Boss 关键 Cookie。请在 agent-tool 执行 "
                "uv sync 后运行 uv run playwright install chromium。"
            ) from exc

        base_url = str(self._constants.BASE_URL).rstrip("/")
        headers = dict(getattr(self._constants, "HEADERS", {}) or {})
        combined = dict(cookies)
        user_data_dir = self._data_dir / "headless-browser"
        user_data_dir.mkdir(parents=True, exist_ok=True)
        timeout_ms = int(self._settings.boss_cli.headless_cookie_timeout_ms)

        with sync_playwright() as playwright:
            context = playwright.chromium.launch_persistent_context(
                str(user_data_dir),
                headless=True,
                user_agent=headers.get("User-Agent"),
                locale="zh-CN",
                viewport={"width": 1365, "height": 900},
                args=[
                    "--password-store=basic",
                    "--use-mock-keychain",
                    "--disable-background-networking",
                    "--disable-blink-features=AutomationControlled",
                    "--disable-default-apps",
                    "--disable-extensions",
                    "--disable-sync",
                    "--no-first-run",
                ],
            )
            context.add_init_script(
                "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"
            )
            try:
                # 持久化用户目录可能残留过期 __zp_stoken__，先清空再注入本次 dispatch Cookie。
                context.clear_cookies()
                seed = [
                    {
                        "name": name,
                        "value": value,
                        "domain": ".zhipin.com",
                        "path": "/",
                        "secure": True,
                        "sameSite": "Lax",
                    }
                    for name, value in combined.items()
                    if value is not None
                ]
                if seed:
                    context.add_cookies(seed)
                page = context.pages[0] if context.pages else context.new_page()

                def collect() -> None:
                    for item in context.cookies(base_url):
                        name = item.get("name")
                        value = item.get("value")
                        if name and value:
                            combined[name] = value

                def visit(url: str, wait_until: str = "domcontentloaded") -> None:
                    try:
                        page.goto(url, wait_until=wait_until, timeout=timeout_ms)
                    except Exception:  # noqa: BLE001
                        pass
                    page.wait_for_timeout(1000)
                    collect()

                visit(f"{base_url}/")
                if STOKEN_COOKIE not in combined:
                    visit(f"{base_url}/web/geek/job-recommend", "networkidle")
                if STOKEN_COOKIE not in combined:
                    visit(f"{base_url}/web/user/?ka=header-login")
            finally:
                context.close()
        return combined

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
