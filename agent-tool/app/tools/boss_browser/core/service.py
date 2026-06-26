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
    get_engine,
)
from app.tools.boss_browser.core.extract import assemble_profile, extract_jobs, normalize_detail
from app.tools.boss_browser.core.rate_limiter import (
    BackstopError,
    RateLimiter,
    RateLimitError,
    RiskCooldownError,
)
from app.tools.boss_browser.core.settings import Settings, get_settings


class AuthRequiredError(Exception):
    pass


class RiskControlError(Exception):
    pass


class BossService:
    def __init__(self, settings: Settings) -> None:
        self._settings = settings
        self._session: BossCliEngine = get_engine(settings)
        self._limiter = RateLimiter(settings.rate_limit, state_path=settings.rate_limit.state_file)

    @property
    def limiter(self) -> RateLimiter:
        return self._limiter

    async def status(self) -> dict[str, Any]:
        result = await self._session.status()
        if result.get("authenticated"):
            self._limiter.clear_cooldown()
            # 一旦确认登录态有效，连续失败硬停计数即应清零：硬停多由登录态缺失/失效
            # 累积触发，登录恢复后必须放行后续访问，避免登录后仍被旧硬停计数死锁。
            self._limiter.reset_backstop()
        return result

    async def refresh_auth(self) -> dict[str, Any]:
        result = await self._session.refresh_auth()
        if result.get("authenticated"):
            self._limiter.clear_cooldown()
            self._limiter.reset_backstop()
        return result

    async def qr_start(self) -> dict[str, Any]:
        return await self._session.start_qr_login()

    async def qr_status(self) -> dict[str, Any]:
        result = await self._session.poll_qr_login()
        if result.get("authenticated"):
            self._limiter.clear_cooldown()
            self._limiter.reset_backstop()
        return result

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
        except BackstopError:
            auth = await self._session.status()
            if not auth.get("authenticated"):
                raise AuthRequiredError("Boss 未登录或登录态失效，请扫码登录。")
            raise

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

    def rate_snapshot(self) -> dict:
        return self._limiter.snapshot()


@lru_cache(maxsize=1)
def get_service() -> BossService:
    return BossService(get_settings())
