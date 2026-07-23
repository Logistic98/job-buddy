"""boss-cli 引擎回归测试。"""

from __future__ import annotations

from pathlib import Path

import yaml

from app.tools.boss_browser.core.boss_cli_engine import PRIMARY_COOKIE, BossCliEngine
from app.tools.boss_browser.core.settings import Settings


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


def _engine(tmp_path) -> BossCliEngine:
    settings = Settings()
    return BossCliEngine(settings)


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
    # 交互翻页热路径必须走轻量单次访问，避免冷启动后多页等待拖慢"换一批"。
    assert completion_seed.get("lean") is True


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

    result = engine._search_sync("Java", "上海", 2, {})  # noqa: SLF001

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

    result = engine._search_sync("Java 大模型应用开发", "上海市", 1, {"salary": "30-50K", "experience": "3-5年"})  # noqa: SLF001

    assert result["payload"]["jobList"][0]["securityId"] == "sec-1"
    assert _FakeClient.captured["query"] == "Java 大模型应用开发"
    assert _FakeClient.captured["city"] == "101020100"
    assert _FakeClient.captured["salary"] == "407"
    assert _FakeClient.captured["experience"] == "103"
    assert _FakeClient.captured_config["timeout"] == 20.0
    assert _FakeClient.captured_config["max_retries"] == 2


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

    assert engine._resolve_city_code("上海市") == "101020100"  # noqa: SLF001
    assert engine._resolve_city_code("阿克苏地区") == "101131000"  # noqa: SLF001
