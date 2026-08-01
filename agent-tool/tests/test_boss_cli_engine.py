"""boss-cli 引擎回归测试。"""

from __future__ import annotations

import base64
import io
from pathlib import Path

import httpx
import yaml
from PIL import Image

from app.tools.boss_browser.core.boss_cli_engine import PRIMARY_COOKIE, BossCliEngine
from app.tools.boss_browser.core.settings import Settings, get_settings


class _FakeCredential:
    def __init__(self, cookies: dict[str, str], has_required: bool = True) -> None:
        self.cookies = cookies
        self.has_required_cookies = has_required
        self.missing_required_cookies = [] if has_required else ["__zp_stoken__"]


class _FakeClient:
    captured: dict = {}
    captured_config: dict = {}

    def __init__(self, credential, timeout=30.0, request_delay=1.0, max_retries=3):
        self.credential = credential
        self.timeout = timeout
        self.request_delay = request_delay
        self.max_retries = max_retries
        self.__class__.captured_config = {
            "timeout": timeout,
            "request_delay": request_delay,
            "max_retries": max_retries,
        }

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return None

    def search_jobs(self, **kwargs):
        self.__class__.captured = kwargs
        return {"jobList": [{"securityId": "sec-1", "jobName": "Java"}]}

    def _get(self, url: str, params=None, action: str = ""):
        self.__class__.captured = {"url": url, "params": params, "action": action}
        return {
            "cardList": [{"securityId": "fav-1", "jobName": "大模型应用开发岗"}],
            "hasMore": True,
            "totalCount": 12,
        }

    def get_job_detail(self, security_id: str, lid: str = ""):
        self.__class__.captured = {"security_id": security_id, "lid": lid}
        return {"jobInfo": {"securityId": security_id, "postDescription": "JD"}}


class _FakeQrClient:
    def __init__(self) -> None:
        self.cookies = {"qr": "session"}

    def __enter__(self):
        return self

    def __exit__(self, *args):
        return None


class _FakeQrStartResponse:
    def raise_for_status(self):
        return None

    def json(self):
        return {"code": 0, "zpData": {"qrId": "bosszp-test-qr-id"}}


class _FakeQrStartClient(_FakeQrClient):
    def post(self, _url):
        return _FakeQrStartResponse()

    def get(self, *_args, **_kwargs):
        raise AssertionError("二维码图片必须本地编码，不应请求 getqrcode 图片接口")


def _engine(tmp_path) -> BossCliEngine:
    settings = Settings()
    return BossCliEngine(settings)


def test_default_search_page_guard_allows_configurable_backend_depth():
    config_path = Path(__file__).resolve().parents[1] / "app/tools/boss_browser/config/config.yaml"
    config = yaml.safe_load(config_path.read_text(encoding="utf-8"))

    assert Settings().boss_cli.max_search_page == 30
    assert config["boss_cli"]["max_search_page"] == 30


def test_search_page_guard_clamps_environment_override(monkeypatch):
    monkeypatch.setenv("BOSS_CLI_MAX_SEARCH_PAGE", "99")
    get_settings.cache_clear()

    assert get_settings().boss_cli.max_search_page == 30

    get_settings.cache_clear()


def test_engine_keeps_credentials_in_memory_without_creating_files(tmp_path):
    engine = _engine(tmp_path)

    assert engine.credential_json() is None
    assert list(tmp_path.iterdir()) == []
    assert engine._status_payload(False, [])["credential_store"] == "memory"  # noqa: SLF001


def test_database_injection_does_not_overwrite_refreshed_token_for_same_identity(tmp_path):
    engine = _engine(tmp_path)
    fresh = _FakeCredential({PRIMARY_COOKIE: "identity", "zp_at": "account", "__zp_stoken__": "fresh", "wbg": "w"})
    engine._memory_credential = fresh  # noqa: SLF001

    engine.load_credential_json(
        '{"cookies":{"%s":"identity","zp_at":"account","__zp_stoken__":"expired","wbg":"w"}}' % PRIMARY_COOKIE
    )

    assert engine._memory_credential is fresh  # noqa: SLF001
    assert engine._memory_credential.cookies["__zp_stoken__"] == "fresh"  # noqa: SLF001


def test_database_injection_replaces_memory_credential_for_new_identity(tmp_path):
    engine = _engine(tmp_path)
    engine._memory_credential = _FakeCredential(  # noqa: SLF001
        {PRIMARY_COOKIE: "old-identity", "zp_at": "old-account", "__zp_stoken__": "old", "wbg": "w"}
    )

    engine.load_credential_json(
        '{"cookies":{"%s":"new-identity","zp_at":"new-account","__zp_stoken__":"new","wbg":"w"}}' % PRIMARY_COOKIE
    )

    assert engine._memory_credential.cookies[PRIMARY_COOKIE] == "new-identity"  # noqa: SLF001
    assert engine._memory_credential.cookies["__zp_stoken__"] == "new"  # noqa: SLF001


def test_status_logged_in_when_required_cookies_present(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    cred = _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "s", "wbg": "w", "zp_at": "z"})
    monkeypatch.setattr(engine, "_get_credential", lambda: cred)

    status = engine._status_sync()  # noqa: SLF001

    assert status["authenticated"] is True
    assert status["status"] == "logged_in"
    assert PRIMARY_COOKIE in status["cookie_present"]


def test_status_keeps_persistent_identity_when_web_cookie_needs_refresh(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    cred = _FakeCredential({PRIMARY_COOKIE: "identity", "zp_at": "account"}, has_required=False)
    monkeypatch.setattr(engine, "_get_credential", lambda: cred)

    status = engine._status_sync()  # noqa: SLF001

    assert status["authenticated"] is True
    assert status["search_authenticated"] is False
    assert status["recommend_authenticated"] is False
    assert status["status"] == "logged_in"
    assert status["reason"] == "web_cookie_refresh_required"


def test_qr_start_encodes_raw_qr_id_locally(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    client = _FakeQrStartClient()
    monkeypatch.setattr(engine, "_qr_client", lambda cookies=None: client)

    started = engine._qr_start_sync()  # noqa: SLF001

    image = Image.open(io.BytesIO(base64.b64decode(started["image_base64"])))
    assert image.format == "PNG"
    assert started["image_mime"] == "image/png"
    assert engine._qr_state["qr_id"] == "bosszp-test-qr-id"  # noqa: SLF001
    assert engine._qr_state["cookies"] == {"qr": "session"}  # noqa: SLF001


def test_qr_poll_keeps_session_retryable_on_tls_handshake_timeout(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    engine._qr_state = {  # noqa: SLF001
        "status": "confirmed",
        "qr_id": "qr-timeout",
        "cookies": {},
        "expires_at": 9999999999,
        "image_base64": "image",
        "image_mime": "image/png",
        "qr_version": 1,
    }
    monkeypatch.setattr(engine, "_qr_client", lambda cookies=None: _FakeQrClient())
    monkeypatch.setattr(
        engine,
        "_qr_dispatch",
        lambda client, qr_id: (_ for _ in ()).throw(httpx.ConnectTimeout("TLS handshake timed out")),
    )

    result = engine._qr_poll_sync()  # noqa: SLF001

    assert result["status"] == "qr_confirmed"
    assert result["reason"] == "qr_confirmed"
    assert engine._qr_state["status"] == "confirmed"  # noqa: SLF001


def test_qr_scan_and_confirm_treat_connect_timeout_as_waiting(tmp_path):
    engine = _engine(tmp_path)

    class _TimeoutClient:
        def get(self, *_args, **_kwargs):
            raise httpx.ConnectTimeout("TLS handshake timed out")

    client = _TimeoutClient()

    assert engine._qr_scan(client, "qr-timeout") is False  # noqa: SLF001
    assert engine._qr_confirm(client, "qr-timeout") is False  # noqa: SLF001


def test_qr_long_poll_matches_boss_cli_timeout_contract(tmp_path):
    engine = _engine(tmp_path)

    with engine._qr_client() as client:  # noqa: SLF001
        assert client.timeout.read == 35.0


def test_qr_snapshot_returns_image_without_polling_upstream(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    state = {
        "status": "qr_ready",
        "qr_id": "qr-snapshot",
        "cookies": {"qr": "session"},
        "expires_at": 9999999999,
        "image_base64": "snapshot-image",
        "image_mime": "image/png",
        "qr_version": 1,
    }
    monkeypatch.setattr(
        engine,
        "_qr_client",
        lambda cookies=None: (_ for _ in ()).throw(AssertionError("本地快照不得访问 Boss")),
    )

    result = engine.qr_snapshot(state)

    assert result["status"] == "qr_waiting"
    assert result["reason"] == "qr_waiting_scan"
    assert result["image_base64"] == "snapshot-image"


def test_qr_confirm_accepts_successful_empty_response(tmp_path):
    engine = _engine(tmp_path)
    captured = {}

    class _ConfirmedResponse:
        status_code = 200

        @staticmethod
        def raise_for_status():
            return None

    class _ConfirmedClient:
        @staticmethod
        def get(url, *, params, timeout):
            captured.update({"url": url, "params": params, "timeout": timeout})
            return _ConfirmedResponse()

    assert engine._qr_confirm(_ConfirmedClient(), "qr-confirmed") is True  # noqa: SLF001
    assert captured["params"] == {"qrId": "qr-confirmed"}
    assert captured["timeout"] == 35.0


def test_qr_poll_returns_each_intermediate_stage_before_dispatch(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    engine._qr_state = {  # noqa: SLF001
        "status": "qr_ready",
        "qr_id": "qr-1",
        "cookies": {},
        "expires_at": 9999999999,
        "image_base64": "image",
        "image_mime": "image/png",
        "qr_version": 1,
    }
    monkeypatch.setattr(engine, "_qr_client", lambda cookies=None: _FakeQrClient())
    monkeypatch.setattr(engine, "_qr_scan", lambda client, qr_id: True)
    monkeypatch.setattr(
        engine,
        "_qr_confirm",
        lambda client, qr_id: (_ for _ in ()).throw(AssertionError("扫码轮次不应继续等待手机确认")),
    )
    monkeypatch.setattr(
        engine,
        "_qr_dispatch",
        lambda client, qr_id: (_ for _ in ()).throw(AssertionError("扫码轮次不应派发凭据")),
    )

    scanned = engine._qr_poll_sync()  # noqa: SLF001

    assert scanned["status"] == "qr_waiting"
    assert scanned["reason"] == "qr_waiting_confirm"
    assert engine._qr_state["status"] == "scanned"  # noqa: SLF001

    monkeypatch.setattr(engine, "_qr_confirm", lambda client, qr_id: True)
    confirmed = engine._qr_poll_sync()  # noqa: SLF001

    assert confirmed["status"] == "qr_confirmed"
    assert confirmed["reason"] == "qr_confirmed"
    assert engine._qr_state["status"] == "confirmed"  # noqa: SLF001

    credential = _FakeCredential({PRIMARY_COOKIE: "identity", "zp_at": "account", "__zp_stoken__": "token", "wbg": "w"})
    monkeypatch.setattr(engine, "_qr_dispatch", lambda client, qr_id: credential)
    logged_in = engine._qr_poll_sync()  # noqa: SLF001

    assert logged_in["status"] == "logged_in"
    assert logged_in["authenticated"] is True
    assert engine._qr_state["status"] == "logged_in"  # noqa: SLF001


def test_qr_login_persists_identity_when_temporary_web_cookie_completion_is_unavailable(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    engine._qr_state = {  # noqa: SLF001
        "status": "confirmed",
        "qr_id": "qr-identity-only",
        "cookies": {},
        "expires_at": 9999999999,
    }
    credential = _FakeCredential({PRIMARY_COOKIE: "identity", "zp_at": "account"}, has_required=False)
    monkeypatch.setattr(engine, "_qr_client", lambda cookies=None: _FakeQrClient())
    monkeypatch.setattr(engine, "_qr_dispatch", lambda client, qr_id: credential)
    monkeypatch.setattr(engine, "_complete_qr_credential", lambda value: value)

    logged_in = engine._qr_poll_sync()  # noqa: SLF001

    assert logged_in["status"] == "logged_in"
    assert logged_in["authenticated"] is True
    assert logged_in["search_authenticated"] is False
    assert logged_in["reason"] == "web_cookie_refresh_required"
    assert logged_in["credential_json"] == '{"cookies":{"wt2":"identity","zp_at":"account"}}'


def test_get_credential_does_not_auto_import_browser_cookies_by_default(tmp_path):
    engine = _engine(tmp_path)

    class _AuthStub:
        @staticmethod
        def extract_browser_credential(cookie_source=None):
            raise AssertionError("browser cookie extraction should be disabled by default")

    engine._auth = _AuthStub  # noqa: SLF001

    assert engine._get_credential() is None  # noqa: SLF001


def test_get_credential_imports_browser_credential(tmp_path):
    engine = _engine(tmp_path)
    engine._settings.boss_cli.auto_import_browser_cookies = True  # noqa: SLF001
    cred = _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "s", "wbg": "w", "zp_at": "z"})
    saved = []

    class _AuthStub:
        @staticmethod
        def extract_browser_credential(cookie_source=None):
            assert cookie_source is None
            return cred

        @staticmethod
        def save_credential(value):
            saved.append(value)

    engine._auth = _AuthStub  # noqa: SLF001

    assert engine._get_credential() is cred  # noqa: SLF001
    assert saved == []
    assert engine._memory_credential is cred  # noqa: SLF001


def test_auth_redirect_degrades_status_without_network(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    cred = _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "s", "wbg": "w", "zp_at": "z"})
    monkeypatch.setattr(engine, "_get_credential", lambda: cred)

    result = engine._auth_redirect("https://www.zhipin.com/web/user/")  # noqa: SLF001

    assert result["login_redirect"] is True
    status = engine._status_sync()  # noqa: SLF001
    assert status["authenticated"] is False
    assert status["status"] == "auth_required"


def test_favorite_code_7_requires_login_without_temporary_token_refresh(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    cred = _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "s", "wbg": "w", "zp_at": "z"})

    class _BossApiError(RuntimeError):
        def __init__(self, message: str, code: int) -> None:
            super().__init__(message)
            self.code = code

    class _AuthRequiredFavoriteClient(_FakeClient):
        def _get(self, url: str, params=None, action: str = ""):
            raise _BossApiError("感兴趣职位: 当前登录状态已失效 (code=7)", 7)

    engine._BossApiError = _BossApiError  # noqa: SLF001
    engine._client_cls = _AuthRequiredFavoriteClient  # noqa: SLF001
    engine._memory_credential = cred  # noqa: SLF001
    monkeypatch.setattr(
        engine,
        "_refresh_after_auth_failure",
        lambda: (_ for _ in ()).throw(AssertionError("code=7 不应刷新临时安全令牌")),
    )

    result = engine._favorite_jobs_sync(1)  # noqa: SLF001

    assert result["payload"] is None
    assert result["login_redirect"] is True
    assert "temporary_auth_refresh_failed" not in result
    assert "code=7" in result["error_message"]
    assert engine._status_sync()["status"] == "auth_required"  # noqa: SLF001


def test_payload_code_7_requires_login(tmp_path):
    engine = _engine(tmp_path)

    result = engine._classify_payload(  # noqa: SLF001
        {"code": 7, "message": "当前登录状态已失效"},
        "/api/favorites",
    )

    assert result["payload"] is None
    assert result["login_redirect"] is True
    assert result["error_message"] == "当前登录状态已失效"


def test_successful_fetch_clears_degraded(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    cred = _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "s", "wbg": "w", "zp_at": "z"})
    monkeypatch.setattr(engine, "_get_credential", lambda: cred)
    engine._auth_redirect("https://www.zhipin.com/web/user/")  # noqa: SLF001
    assert engine._status_sync()["status"] == "auth_required"  # noqa: SLF001

    classified = engine._classify_payload({"jobList": []}, "url")  # noqa: SLF001

    assert classified["login_redirect"] is False
    assert engine._status_sync()["status"] == "logged_in"  # noqa: SLF001


def test_refresh_reuses_persisted_login_to_regenerate_stoken(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    # PostgreSQL 注入的持久登录 Cookie 仍在、仅 __zp_stoken__ 失效。
    persisted = _FakeCredential({PRIMARY_COOKIE: "x", "wbg": "w", "zp_at": "z"}, has_required=False)
    saved: list = []

    class _Auth:
        @staticmethod
        def Credential(cookies):
            return _FakeCredential(cookies)

        @staticmethod
        def save_credential(value):
            saved.append(value)

    engine._auth = _Auth  # noqa: SLF001
    monkeypatch.setattr(engine, "_get_credential_without_browser_import", lambda: persisted)
    completion_seed: dict = {}

    def _fake_completion(cookies, *, lean=False):
        completion_seed.update(cookies)
        completion_seed["lean"] = lean
        # 重生令牌：headless 访问后回收到完整 Cookie。
        return {**cookies, "__zp_stoken__": "fresh-token"}

    monkeypatch.setattr(engine, "_run_headless_cookie_completion", _fake_completion)
    # 身份 Cookie 仍在时绝不能回退去读浏览器 Cookie。
    monkeypatch.setattr(
        engine,
        "_import_browser_credential",
        lambda: (_ for _ in ()).throw(AssertionError("不应回退浏览器 Cookie 导入")),
    )

    assert engine._refresh_after_auth_failure() is True  # noqa: SLF001
    assert saved == []
    assert engine._memory_credential.cookies.get("__zp_stoken__") == "fresh-token"  # noqa: SLF001
    # 失效令牌应被剔除后再交给 headless 重生，避免带着废令牌空跑。
    assert "__zp_stoken__" not in completion_seed
    # 交互翻页热路径不访问登录页兜底，但仍保留完整的令牌等待窗口。
    assert completion_seed.get("lean") is True


def test_detail_preserves_login_when_failed_refresh_is_throttled(tmp_path):
    engine = _engine(tmp_path)
    persisted = _FakeCredential({PRIMARY_COOKIE: "identity", "__zp_stoken__": "fresh", "wbg": "w", "zp_at": "account"})

    class _SessionExpired(RuntimeError):
        pass

    class _ExpiredDetailClient(_FakeClient):
        def get_job_detail(self, security_id: str, lid: str = ""):
            raise _SessionExpired("temporary token expired")

    engine._client_cls = _ExpiredDetailClient  # noqa: SLF001
    engine._SessionExpiredError = _SessionExpired  # noqa: SLF001
    engine._memory_credential = persisted  # noqa: SLF001
    # 模拟前一次临时浏览器恢复刚刚失败，后一个详情在节流窗口内再次要求恢复。
    engine._last_browser_refresh_at = float("inf")  # noqa: SLF001
    engine._transient_refresh_failure = True  # noqa: SLF001

    result = engine._detail_sync("sec-1", "")  # noqa: SLF001

    assert result["payload"] is None
    assert result["login_redirect"] is False
    assert result["temporary_auth_refresh_failed"] is True
    assert engine._memory_credential is persisted  # noqa: SLF001
    assert engine._status_sync()["status"] == "logged_in"  # noqa: SLF001


def test_successful_refresh_does_not_throttle_next_confirmed_token_expiry(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    persisted = _FakeCredential(
        {PRIMARY_COOKIE: "identity", "__zp_stoken__": "expired", "wbg": "w", "zp_at": "account"}
    )
    engine._memory_credential = persisted  # noqa: SLF001
    completion_calls = 0

    class _Auth:
        @staticmethod
        def Credential(cookies):
            return _FakeCredential(cookies)

    engine._auth = _Auth  # noqa: SLF001

    def _complete(cookies, *, lean=False):
        nonlocal completion_calls
        completion_calls += 1
        assert lean is True
        return {**cookies, "__zp_stoken__": f"fresh-token-{completion_calls}"}

    monkeypatch.setattr(engine, "_run_headless_cookie_completion", _complete)

    assert engine._refresh_after_auth_failure() is True  # noqa: SLF001
    # 第二个真实请求已再次证明令牌失效；即使仍在 60 秒内，也应允许它恢复一次。
    assert engine._refresh_after_auth_failure() is True  # noqa: SLF001
    assert completion_calls == 2
    assert engine._memory_credential.cookies.get("__zp_stoken__") == "fresh-token-2"  # noqa: SLF001


def test_refresh_does_not_fall_back_to_browser_by_default(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    # 身份 Cookie 缺失：默认配置下既不触发 headless 重生，也不读取本机浏览器钥匙串。
    monkeypatch.setattr(engine, "_get_credential_without_browser_import", lambda: None)
    monkeypatch.setattr(
        engine,
        "_run_headless_cookie_completion",
        lambda cookies: (_ for _ in ()).throw(AssertionError("无身份 Cookie 时不应重生令牌")),
    )
    monkeypatch.setattr(
        engine,
        "_import_browser_credential",
        lambda: (_ for _ in ()).throw(AssertionError("默认配置下不应读取浏览器 Cookie")),
    )

    assert engine._refresh_after_auth_failure() is False  # noqa: SLF001


def test_refresh_falls_back_to_browser_when_explicitly_enabled(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    engine._settings.boss_cli.auto_import_browser_cookies = True  # noqa: SLF001
    monkeypatch.setattr(engine, "_get_credential_without_browser_import", lambda: None)
    imported: list = []

    def _fake_import():
        cred = _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "s", "wbg": "w", "zp_at": "z"})
        imported.append(cred)
        return cred

    monkeypatch.setattr(engine, "_import_browser_credential", _fake_import)

    assert engine._refresh_after_auth_failure() is True  # noqa: SLF001
    assert len(imported) == 1


def test_manual_refresh_does_not_read_browser_when_disabled(tmp_path):
    engine = _engine(tmp_path)

    class _AuthWithoutBrowserAccess:
        @staticmethod
        def load_credential():
            return None

        @staticmethod
        def extract_browser_credential(cookie_source=None):
            raise AssertionError("refresh_auth must not access browser cookies when disabled")

    engine._auth = _AuthWithoutBrowserAccess  # noqa: SLF001

    result = engine._refresh_auth_sync()  # noqa: SLF001

    assert result["refreshed"] is False
    assert result["authenticated"] is False


def test_favorite_list_uses_fixed_configured_tag(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    engine._client_cls = _FakeClient  # noqa: SLF001
    engine._settings.boss_cli.favorite_list_tag = 4  # noqa: SLF001
    cred = _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "s", "wbg": "w", "zp_at": "z"})
    monkeypatch.setattr(engine, "_credential_or_none", lambda: cred)

    result = engine._favorite_jobs_sync(1)  # noqa: SLF001

    assert result["payload"]["cardList"][0]["securityId"] == "fav-1"
    assert _FakeClient.captured["params"] == {"page": 1, "tag": 4, "isActive": "true"}
    assert _FakeClient.captured["action"] == "感兴趣职位"


def test_favorite_list_page_limit_zero_allows_any_manual_page(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    engine._client_cls = _FakeClient  # noqa: SLF001
    engine._settings.boss_cli.max_favorite_list_page = 0  # noqa: SLF001
    cred = _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "s", "wbg": "w", "zp_at": "z"})
    monkeypatch.setattr(engine, "_credential_or_none", lambda: cred)

    result = engine._favorite_jobs_sync(21)  # noqa: SLF001

    assert result["payload"] is not None
    assert _FakeClient.captured["params"]["page"] == 21


def test_favorite_list_page_limit_blocks_without_network(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    engine._settings.boss_cli.max_favorite_list_page = 2  # noqa: SLF001
    monkeypatch.setattr(
        engine,
        "_credential_or_none",
        lambda: (_ for _ in ()).throw(AssertionError("本地拒绝不应读取凭据或访问网络")),
    )

    result = engine._favorite_jobs_sync(3)  # noqa: SLF001

    assert result["payload"] is None
    assert result["local_rejected"] is True
    assert "前 2 页" in result["error_message"]


def test_search_page_limit_blocks_without_network(tmp_path):
    engine = _engine(tmp_path)
    engine._settings.boss_cli.max_search_page = 1  # noqa: SLF001

    result = engine._search_sync("Go", "杭州", 2, {})  # noqa: SLF001

    assert result["payload"] is None
    assert result["local_rejected"] is True
    assert "只允许搜索到第 1 页" in result["error_message"]


def test_search_uses_boss_cli_client_and_filter_mapping(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    engine._client_cls = _FakeClient  # noqa: SLF001
    monkeypatch.setattr(
        engine,
        "_credential_or_none",
        lambda: _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "s", "wbg": "w", "zp_at": "z"}),
    )

    result = engine._search_sync("Go 云原生平台开发", "杭州市", 1, {"salary": "30-50K", "experience": "3-5年"})  # noqa: SLF001

    assert result["payload"]["jobList"][0]["securityId"] == "sec-1"
    assert _FakeClient.captured["query"] == "Go 云原生平台开发"
    assert _FakeClient.captured["city"] == "101210100"
    assert _FakeClient.captured["salary"] == "407"
    assert _FakeClient.captured["experience"] == "103"
    assert _FakeClient.captured_config["timeout"] == 20.0
    assert _FakeClient.captured_config["max_retries"] == 2


def test_search_reuses_shared_login_after_temporary_token_expires(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    initial = _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "expired", "wbg": "w", "zp_at": "z"})
    fresh = _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "fresh", "wbg": "w", "zp_at": "z"})
    attempts = {"count": 0}

    class _SessionExpired(RuntimeError):
        pass

    class _ExpiringSearchClient(_FakeClient):
        def search_jobs(self, **kwargs):
            attempts["count"] += 1
            if attempts["count"] == 1:
                raise _SessionExpired("temporary token expired")
            return super().search_jobs(**kwargs)

    engine._client_cls = _ExpiringSearchClient  # noqa: SLF001
    engine._SessionExpiredError = _SessionExpired  # noqa: SLF001
    credentials = iter((initial, fresh))
    monkeypatch.setattr(engine, "_credential_or_none", lambda: next(credentials))
    monkeypatch.setattr(engine, "_refresh_after_auth_failure", lambda: True)

    result = engine._search_sync("Go", "杭州", 1, {})  # noqa: SLF001

    assert attempts["count"] == 2
    assert result["login_redirect"] is False
    assert result["payload"]["jobList"][0]["securityId"] == "sec-1"


def test_search_preserves_login_when_temporary_browser_refresh_closes(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    persisted = _FakeCredential(
        {PRIMARY_COOKIE: "identity", "__zp_stoken__": "expired", "wbg": "w", "zp_at": "account"}
    )

    class _SessionExpired(RuntimeError):
        pass

    class _ExpiredSearchClient(_FakeClient):
        def search_jobs(self, **kwargs):
            raise _SessionExpired("temporary token expired")

    engine._client_cls = _ExpiredSearchClient  # noqa: SLF001
    engine._SessionExpiredError = _SessionExpired  # noqa: SLF001
    engine._memory_credential = persisted  # noqa: SLF001
    monkeypatch.setattr(
        engine,
        "_run_headless_cookie_completion",
        lambda *_args, **_kwargs: (_ for _ in ()).throw(
            RuntimeError("Target page, context or browser has been closed")
        ),
    )

    result = engine._search_sync("Go", "杭州", 1, {})  # noqa: SLF001

    assert result["payload"] is None
    assert result["login_redirect"] is False
    assert result["temporary_auth_refresh_failed"] is True
    assert engine._memory_credential is persisted  # noqa: SLF001
    assert engine._auth_degraded is False  # noqa: SLF001


def test_successful_fetch_clears_transient_refresh_failure(tmp_path):
    engine = _engine(tmp_path)
    engine._transient_refresh_failure = True  # noqa: SLF001

    result = engine._classify_payload({"jobList": []}, "url")  # noqa: SLF001

    assert result["login_redirect"] is False
    assert engine._transient_refresh_failure is False  # noqa: SLF001


def test_detail_extracts_security_id_and_lid_from_url(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    engine._client_cls = _FakeClient  # noqa: SLF001
    monkeypatch.setattr(
        engine,
        "_credential_or_none",
        lambda: _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "s", "wbg": "w", "zp_at": "z"}),
    )

    result = engine._detail_sync("", "https://www.zhipin.com/job_detail/abc.html?securityId=sec-1&lid=lid-1")  # noqa: SLF001

    assert result["payload"]["jobInfo"]["securityId"] == "sec-1"
    assert _FakeClient.captured == {"security_id": "sec-1", "lid": "lid-1"}


def test_detail_refreshes_missing_temporary_cookie_before_requiring_login(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    engine._client_cls = _FakeClient  # noqa: SLF001
    fresh = _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "fresh", "wbg": "w", "zp_at": "z"})
    credentials = iter((None, fresh))
    monkeypatch.setattr(engine, "_credential_or_none", lambda: next(credentials))
    monkeypatch.setattr(engine, "_refresh_after_auth_failure", lambda: True)

    result = engine._detail_sync("sec-1", "")  # noqa: SLF001

    assert result["login_redirect"] is False
    assert result["payload"]["jobInfo"]["postDescription"] == "JD"


def test_detail_retries_once_after_session_expired(tmp_path, monkeypatch):
    engine = _engine(tmp_path)
    initial = _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "expired", "wbg": "w", "zp_at": "z"})
    fresh = _FakeCredential({PRIMARY_COOKIE: "x", "__zp_stoken__": "fresh", "wbg": "w", "zp_at": "z"})
    attempts = {"count": 0}

    class _SessionExpired(RuntimeError):
        pass

    class _ExpiringDetailClient(_FakeClient):
        def get_job_detail(self, security_id: str, lid: str = ""):
            attempts["count"] += 1
            if attempts["count"] == 1:
                raise _SessionExpired("temporary token expired")
            return super().get_job_detail(security_id, lid)

    engine._client_cls = _ExpiringDetailClient  # noqa: SLF001
    engine._SessionExpiredError = _SessionExpired  # noqa: SLF001
    credentials = iter((initial, fresh))
    monkeypatch.setattr(engine, "_credential_or_none", lambda: next(credentials))
    monkeypatch.setattr(engine, "_refresh_after_auth_failure", lambda: True)

    result = engine._detail_sync("sec-1", "")  # noqa: SLF001

    assert attempts["count"] == 2
    assert result["login_redirect"] is False
    assert result["payload"]["jobInfo"]["postDescription"] == "JD"


def test_unknown_nonzero_payload_is_not_treated_as_empty_success(tmp_path):
    engine = _engine(tmp_path)

    result = engine._classify_payload({"code": -1, "message": "failed"}, "/api")  # noqa: SLF001

    assert result["payload"] is None
    assert result["login_redirect"] is False
    assert "failed" in result["error_message"]


def test_config_covers_nationwide_boss_cities():
    config_path = Path(__file__).resolve().parents[1] / "app" / "tools" / "boss_browser" / "config" / "config.yaml"
    data = yaml.safe_load(config_path.read_text(encoding="utf-8"))
    codes = data["boss"]["city_codes"]

    assert len(codes) >= 370
    assert codes["全国"] == "100010000"
    assert codes["拉萨"] == "101140100"
    assert codes["阿克苏地区"] == "101131000"
    assert codes["阿坝藏族羌族自治州"] == "101271900"
    assert codes["香港"] == "101320300"
    assert codes["澳门"] == "101330100"


def test_city_resolver_supports_suffix(tmp_path):
    config_path = Path(__file__).resolve().parents[1] / "app" / "tools" / "boss_browser" / "config" / "config.yaml"
    settings = Settings(**yaml.safe_load(config_path.read_text(encoding="utf-8")))
    engine = BossCliEngine(settings)

    assert engine._resolve_city_code("杭州市") == "101210100"  # noqa: SLF001
    assert engine._resolve_city_code("阿克苏地区") == "101131000"  # noqa: SLF001
