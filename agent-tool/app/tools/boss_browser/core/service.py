"""业务编排：把限速/风控与 boss-cli 数据提取组合起来。

对外暴露 status / qr_start / qr_status / search / detail，
所有会真正访问 Boss 的动作都先经过 RateLimiter，命中风控信号即全局停手。
"""

from __future__ import annotations

from functools import lru_cache
from typing import Any, Optional

from loguru import logger

from app.tools.boss_browser.core.boss_cli_engine import (
    BossCliEngine,
    BossCliUnavailable,
    BossCliUpstreamRateLimited,
)
from app.tools.boss_browser.core.extract import assemble_profile, extract_jobs, normalize_detail
from app.tools.boss_browser.core.qr_session_codec import QrSessionCodec
from app.tools.boss_browser.core.rate_limiter import (
    BackstopError,
    RateLimiter,
)
from app.tools.boss_browser.core.settings import Settings, get_settings


class AuthRequiredError(Exception):
    pass


class RiskControlError(Exception):
    pass


class BossService:
    def __init__(
        self,
        settings: Settings,
        owner_key: str = "legacy",
        qr_codec: QrSessionCodec | None = None,
    ) -> None:
        self._settings = settings
        self._owner_key = owner_key
        self._session = BossCliEngine(settings)
        self._qr_codec = qr_codec or QrSessionCodec()
        self._limiter = RateLimiter(
            settings.rate_limit,
            redis_url=settings.rate_limit.redis_url,
            namespace=owner_key,
        )

    @property
    def limiter(self) -> RateLimiter:
        return self._limiter

    def load_credential_json(self, credential_json: str | None) -> None:
        self._session.load_credential_json(credential_json)

    def credential_json(self) -> str | None:
        return self._session.credential_json()

    async def status(self) -> dict[str, Any]:
        result = await self._session.status()
        if result.get("authenticated"):
            self._limiter.clear_cooldown()
            # 一旦确认登录态有效，连续失败硬停计数即应清零：硬停多由登录态缺失/失效
            # 累积触发，登录恢复后必须放行后续访问，避免登录后仍被既有硬停计数死锁。
            self._limiter.reset_backstop()
        return result

    async def refresh_auth(self) -> dict[str, Any]:
        result = await self._session.refresh_auth()
        if result.get("authenticated"):
            self._limiter.clear_cooldown()
            self._limiter.reset_backstop()
        return result

    async def qr_start(self, session_id: str) -> dict[str, Any]:
        if not session_id:
            raise ValueError("Boss 二维码 session_id 不能为空")
        result = await self._session.start_qr_login()
        state = self._session.export_qr_state()
        expires_at = float(state.get("expires_at") or 0)
        result["session_id"] = session_id
        result["session_token"] = self._qr_codec.encode(
            owner_key=self._owner_key,
            session_id=session_id,
            state=state,
            expires_at=expires_at,
        )
        result["expires_at"] = expires_at
        return result

    async def qr_status(self, session_id: str, session_token: str) -> dict[str, Any]:
        state = self._qr_codec.decode(
            owner_key=self._owner_key,
            session_id=session_id,
            token=session_token,
        )
        self._session.import_qr_state(state)
        result = await self._session.poll_qr_login()
        if result.get("authenticated"):
            self._limiter.clear_cooldown()
            self._limiter.reset_backstop()
        next_state = self._session.export_qr_state()
        if next_state.get("qr_id") and result.get("status") not in {"logged_in", "auth_required", "qr_expired"}:
            result["session_token"] = self._qr_codec.encode(
                owner_key=self._owner_key,
                session_id=session_id,
                state=next_state,
                expires_at=float(next_state.get("expires_at") or 0),
            )
        result["session_id"] = session_id
        return result

    async def qr_cancel(self, session_id: str, session_token: str) -> dict[str, Any]:
        self._qr_codec.decode(
            owner_key=self._owner_key,
            session_id=session_id,
            token=session_token,
        )
        self._session.clear_qr_state()
        return {"status": "cancelled", "session_id": session_id}

    async def _acquire(self, action: str) -> None:
        """获取限速许可；硬停时若实为未登录，则转为引导扫码登录而非死锁。

        连续失败硬停是账号保护，但"需要登录"本身不是风险信号。若因登录态缺失反复
        失败触发硬停，用户将无法再触发扫码登录而彻底卡死。这里在硬停时做一次本地
        登录态判定（status_verify 默认关闭，不会真实访问 Boss）：未登录则抛出
        AuthRequiredError 引导扫码，扫码成功后会重置硬停计数；确有有效登录仍反复
        失败时才维持硬停，交人工排查。
        """
        try:
            await self._limiter.acquire(action)
        except BackstopError as exc:
            auth = await self._session.status()
            if not auth.get("authenticated"):
                raise AuthRequiredError("Boss 未登录或登录态失效，请扫码登录。") from exc
            raise

    async def favorite_jobs(self, page: int = 1) -> dict[str, Any]:
        """按用户明确分页读取 Boss 感兴趣/收藏摘要，不自动翻页或补详情。"""
        page = max(1, page)
        max_page = self._settings.boss_cli.max_favorite_list_page
        if max_page > 0 and page > max_page:
            raise ValueError(f"Boss 收藏列表最多允许人工浏览前 {max_page} 页。")
        await self._session.assert_browser_ready()
        await self._acquire("favorite_list")
        try:
            result = await self._session.favorite_jobs(page=page)
        except BossCliUnavailable:
            raise
        except Exception:
            self._limiter.record_failure()
            raise
        self._handle_upstream_rate_limit(result)
        self._handle_risk(result.get("risk_marker"))
        if result.get("login_redirect"):
            raise AuthRequiredError(result.get("error_message") or "Boss 未登录或登录态失效，请扫码登录。")
        payload = result.get("payload")
        if payload is None:
            if result.get("temporary_auth_refresh_failed"):
                self._limiter.record_failure()
                raise RuntimeError(result.get("error_message") or "Boss 临时安全令牌刷新失败，请稍后重试。")
            auth = await self._session.status()
            if not auth.get("authenticated"):
                raise AuthRequiredError("Boss 未登录或登录态失效，请扫码登录。")
            if not result.get("local_rejected"):
                self._limiter.record_failure()
            raise RuntimeError(result.get("error_message") or "未拿到 Boss 收藏列表数据，请稍后重试。")
        jobs = extract_jobs(payload)
        total_count = self._payload_value(payload, "totalCount", len(jobs))
        try:
            total_count = max(0, int(total_count))
        except (TypeError, ValueError):
            total_count = len(jobs)
        upstream_has_more = bool(self._payload_value(payload, "hasMore", False))
        page_size = max(1, len(jobs))
        upstream_pages = max(page, (total_count + page_size - 1) // page_size)
        if upstream_has_more:
            upstream_pages = max(upstream_pages, page + 1)
        total_pages = max(1, upstream_pages)
        browsable_pages = min(max_page, total_pages) if max_page > 0 else total_pages
        self._limiter.record_success()
        return {
            "jobs": jobs,
            "page": page,
            "hasMore": upstream_has_more and page < browsable_pages,
            "totalCount": total_count,
            "totalPages": total_pages,
            "rate": self.rate_snapshot(),
        }

    async def search(self, query: str, city: str = "", page: int = 1, extra: Optional[dict] = None) -> list[dict]:
        # boss-cli 依赖不可用是本地基础设施故障，未触达 Boss：先预检，避免占用配额或误计风控失败。
        await self._session.assert_browser_ready()
        await self._acquire("search")
        try:
            result = await self._session.search(query=query, city=city, page=page, extra=extra)
        except BossCliUnavailable:
            raise
        except Exception:
            self._limiter.record_failure()
            raise
        self._handle_upstream_rate_limit(result)
        self._handle_risk(result.get("risk_marker"))
        # 页面被重定向到登录页是登录态失效的强信号，直接引导扫码，避免静默返回空。
        # 注意：需要登录不是风险信号，不计入连续失败硬停，否则未登录会反复累计直至
        # 触发硬停，使扫码登录入口被彻底锁死、无法恢复。
        if result.get("login_redirect"):
            raise AuthRequiredError(result.get("error_message") or "Boss 未登录或登录态失效，请扫码登录。")
        payload = result.get("payload")
        jobs = extract_jobs(payload)
        if not jobs:
            if result.get("temporary_auth_refresh_failed"):
                self._limiter.record_failure()
                raise RuntimeError(result.get("error_message") or "Boss 临时安全令牌刷新失败，请稍后重试。")
            # 无结果且未登录时，判定为需要登录（同样不计入硬停）。
            auth = await self._session.status()
            if not auth.get("authenticated"):
                raise AuthRequiredError("Boss 未登录或登录态失效，请扫码登录。")
            if payload is None:
                # 根本没有拿到搜索 payload（payload 为 None）：这不是"真的没有匹配岗位"，
                # 而是取数失败或被安全策略拒绝。绝不能伪装成"0 个候选岗位"静默返回，
                # 否则用户以为无岗位，也掩盖了真正的故障。
                if not result.get("local_rejected"):
                    self._limiter.record_failure()
                raise RuntimeError(
                    result.get("error_message")
                    or "未拿到 Boss 搜索结果数据，可能是上游接口变动或登录态失效，请稍后重试或重新登录。"
                )
            # payload 已拿到但解析为空列表：判定为该条件下真的没有匹配岗位。
            # 出于账号安全不 record_success（避免把可疑访问当成功而清空硬停保护），
            # 直接返回空列表交由上层按"无匹配"提示。
            return jobs
        self._limiter.record_success()
        return jobs

    async def detail(self, security_id: str = "", url: str = "") -> dict[str, Any]:
        await self._session.assert_browser_ready()
        await self._acquire("detail")
        try:
            result = await self._session.detail(security_id=security_id, url=url)
        except BossCliUnavailable:
            raise
        except Exception:
            self._limiter.record_failure()
            raise
        self._handle_upstream_rate_limit(result)
        self._handle_risk(result.get("risk_marker"))
        # 需要登录不计入硬停，避免登录态失效把详情入口也锁死。
        if result.get("login_redirect"):
            raise AuthRequiredError(result.get("error_message") or "Boss 未登录或登录态失效，请扫码登录。")
        payload = result.get("payload")
        if payload is None:
            if result.get("temporary_auth_refresh_failed"):
                self._limiter.record_failure()
                raise RuntimeError(result.get("error_message") or "Boss 临时安全令牌刷新失败，请稍后重试。")
            auth = await self._session.status()
            if not auth.get("authenticated"):
                raise AuthRequiredError("Boss 未登录或登录态失效，请扫码登录。")
            self._limiter.record_failure()
            raise RuntimeError("未拿到岗位详情数据，请稍后重试。")
        self._limiter.record_success()
        return normalize_detail(payload)

    async def profile(self) -> dict[str, Any]:
        await self._session.assert_browser_ready()
        await self._acquire("detail")
        try:
            result = await self._session.profile()
        except BossCliUnavailable:
            raise
        except Exception:
            self._limiter.record_failure()
            raise
        self._handle_upstream_rate_limit(result)
        self._handle_risk(result.get("risk_marker"))
        captures = result.get("captures") or []
        if not captures:
            auth = await self._session.status()
            if not auth.get("authenticated"):
                # 需要登录不计入硬停，引导扫码即可。
                raise AuthRequiredError("Boss 未登录或登录态失效，请扫码登录。")
            self._limiter.record_failure()
            raise RuntimeError("未拿到求职画像数据，请稍后重试。")
        self._limiter.record_success()
        return assemble_profile(captures)

    def _handle_upstream_rate_limit(self, result: dict[str, Any]) -> None:
        if not result.get("rate_limited"):
            return
        self._limiter.record_failure()
        raise BossCliUpstreamRateLimited(result.get("error_message") or "Boss 上游请求过于频繁，请稍后再试。")

    def _handle_risk(self, risk_marker: Optional[str]) -> None:
        if not risk_marker:
            return
        self._limiter.record_failure()
        self._limiter.trip_risk_cooldown(risk_marker)
        logger.error(f"检测到风控信号，已全局停手：{risk_marker}")
        raise RiskControlError(f"检测到 Boss 风控信号（{risk_marker}），已暂停操作以保护账号。")

    @staticmethod
    def _payload_value(payload: Any, key: str, fallback: Any) -> Any:
        if isinstance(payload, dict):
            if key in payload:
                return payload.get(key)
            for container_key in ("zpData", "data", "result"):
                nested = payload.get(container_key)
                if isinstance(nested, dict):
                    value = BossService._payload_value(nested, key, None)
                    if value is not None:
                        return value
        return fallback

    def rate_snapshot(self) -> dict:
        return self._limiter.snapshot()


@lru_cache(maxsize=256)
def get_service(owner_key: str = "legacy") -> BossService:
    return BossService(get_settings(), owner_key=owner_key)
