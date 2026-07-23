"""拟人化限速、配额与风控冷却。

设计目标：让对 Boss 的请求频率与节奏尽量贴近真人，并在出现风控信号时
全局停手，避免账号被封。所有动作（搜索/收藏列表/详情）在执行前都要先 acquire。
"""

from __future__ import annotations

import asyncio
import hashlib
import json
import random
import time
from collections import deque
from dataclasses import dataclass, field
from typing import Optional

from loguru import logger
from redis import Redis

from app.tools.boss_browser.core.settings import RateLimitConfig


class RateLimitError(Exception):
    """触发配额上限。"""


class RiskCooldownError(Exception):
    """处于风控冷却期。"""


class BackstopError(Exception):
    """连续失败触顶，需人工介入。"""


@dataclass
class _Window:
    hour: deque = field(default_factory=deque)
    day: deque = field(default_factory=deque)

    def prune(self, now: float) -> None:
        while self.hour and now - self.hour[0] > 3600:
            self.hour.popleft()
        while self.day and now - self.day[0] > 86400:
            self.day.popleft()

    def record(self, now: float) -> None:
        self.hour.append(now)
        self.day.append(now)


class RateLimiter:
    def __init__(
        self,
        config: RateLimitConfig,
        state_path: Optional[str] = None,
        redis_url: Optional[str] = None,
        namespace: str = "shared",
    ) -> None:
        self._config = config
        self._windows: dict[str, _Window] = {
            "search": _Window(),
            "favorite_list": _Window(),
            "detail": _Window(),
        }
        self._lock = asyncio.Lock()
        self._cooldown_until: float = 0.0
        self._consecutive_failures: int = 0
        self._last_action_at: float = 0.0
        # 风控冷却、连续失败和配额窗口必须跨进程保存，但不得写本地文件。
        # Redis 不可用时仅退化为进程内保护，并明确记录告警。
        namespace_digest = hashlib.sha256(namespace.encode("utf-8")).hexdigest()
        self._redis_key = f"job-buddy:boss:rate-state:{namespace_digest}"
        self._redis: Redis | None = None
        if redis_url:
            try:
                self._redis = Redis.from_url(redis_url, decode_responses=True, socket_timeout=3)
                self._redis.ping()
            except Exception as exc:
                self._redis = None
                logger.warning(f"Boss Redis 限速状态不可用，退化为进程内保护：{exc}")
        self._load_state()

    # ---- 持久化 ----
    def _load_state(self) -> None:
        if self._redis is None:
            return
        try:
            encoded = self._redis.get(self._redis_key)
            if not encoded:
                return
            raw = json.loads(encoded)
        except Exception as exc:
            logger.warning(f"限速状态加载失败，按全新状态启动：{exc}")
            return
        now = time.time()
        self._cooldown_until = float(raw.get("cooldown_until", 0.0) or 0.0)
        self._consecutive_failures = int(raw.get("consecutive_failures", 0) or 0)
        for action in self._windows:
            data = (raw.get("windows") or {}).get(action) or {}
            window = self._windows[action]
            window.hour = deque(ts for ts in data.get("hour", []) if now - ts <= 3600)
            window.day = deque(ts for ts in data.get("day", []) if now - ts <= 86400)
        remaining = int(self._cooldown_until - now)
        if remaining > 0:
            logger.warning(f"从持久化状态恢复风控冷却，剩余约 {remaining} 秒。")

    def _persist_state(self) -> None:
        if self._redis is None:
            return
        try:
            payload = {
                "cooldown_until": self._cooldown_until,
                "consecutive_failures": self._consecutive_failures,
                "windows": {
                    action: {
                        "hour": list(window.hour),
                        "day": list(window.day),
                    }
                    for action, window in self._windows.items()
                },
            }
            self._redis.set(self._redis_key, json.dumps(payload), ex=172800)
        except Exception as exc:
            logger.warning(f"Boss Redis 限速状态保存失败：{exc}")

    def _limits(self, action: str) -> tuple[int, int]:
        if action == "search":
            return self._config.search_per_hour, self._config.search_per_day
        if action == "favorite_list":
            return self._config.favorite_list_per_hour, self._config.favorite_list_per_day
        return self._config.detail_per_hour, self._config.detail_per_day

    async def acquire(self, action: str) -> None:
        """在执行一次 Boss 动作前调用：校验冷却/配额，并施加拟人抖动延迟。"""
        async with self._lock:
            now = time.time()
            if self._consecutive_failures >= self._config.consecutive_failure_backstop:
                raise BackstopError(f"连续失败 {self._consecutive_failures} 次，已硬停，请人工检查 Boss 账号与登录态。")
            if now < self._cooldown_until:
                remaining = int(self._cooldown_until - now) + 1
                raise RiskCooldownError(f"处于风控冷却期，约 {remaining} 秒后可重试。")

            window = self._windows[action]
            window.prune(now)
            per_hour, per_day = self._limits(action)
            if per_hour > 0 and len(window.hour) >= per_hour:
                raise RateLimitError(f"{action} 已达每小时上限（{per_hour}），请稍后再试。")
            if per_day > 0 and len(window.day) >= per_day:
                raise RateLimitError(f"{action} 已达每日上限（{per_day}），请明日再试。")

            delay = self._pending_delay(now)
            if delay > 0:
                await asyncio.sleep(delay)
                now = time.time()

            window.record(now)
            self._last_action_at = now
            self._persist_state()

    def _pending_delay(self, now: float) -> float:
        target_gap = random.uniform(
            self._config.action_delay_min_ms / 1000.0,
            self._config.action_delay_max_ms / 1000.0,
        )
        elapsed = now - self._last_action_at
        return max(0.0, target_gap - elapsed)

    def record_success(self) -> None:
        self._consecutive_failures = 0
        self._persist_state()

    def record_failure(self) -> None:
        self._consecutive_failures += 1
        logger.warning(f"Boss 动作失败计数：{self._consecutive_failures}")
        self._persist_state()

    def trip_risk_cooldown(self, reason: str) -> None:
        seconds = self._config.cooldown_minutes_on_risk * 60
        self._cooldown_until = time.time() + seconds
        logger.error(f"命中风控信号，全局冷却 {self._config.cooldown_minutes_on_risk} 分钟：{reason}")
        self._persist_state()

    def clear_cooldown(self) -> None:
        self._cooldown_until = 0.0
        self._persist_state()

    def reset_backstop(self) -> None:
        self._consecutive_failures = 0
        self._persist_state()

    def snapshot(self) -> dict:
        now = time.time()
        for window in self._windows.values():
            window.prune(now)
        return {
            "search_used_hour": len(self._windows["search"].hour),
            "search_limit_hour": self._config.search_per_hour,
            "search_used_day": len(self._windows["search"].day),
            "search_limit_day": self._config.search_per_day,
            "favorite_list_used_hour": len(self._windows["favorite_list"].hour),
            "favorite_list_limit_hour": self._config.favorite_list_per_hour,
            "favorite_list_used_day": len(self._windows["favorite_list"].day),
            "favorite_list_limit_day": self._config.favorite_list_per_day,
            "detail_used_hour": len(self._windows["detail"].hour),
            "detail_limit_hour": self._config.detail_per_hour,
            "detail_used_day": len(self._windows["detail"].day),
            "detail_limit_day": self._config.detail_per_day,
            "cooldown_active": now < self._cooldown_until,
            "cooldown_remaining_seconds": max(0, int(self._cooldown_until - now)),
            "consecutive_failures": self._consecutive_failures,
        }
