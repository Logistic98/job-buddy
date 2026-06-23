"""工具入口事件循环复用回归测试。

锁定问题：Boss 工具服务是进程内单例，其 RateLimiter 与取数引擎持有 asyncio.Lock，
首次使用时会绑定事件循环。若工具入口每次请求都用 asyncio.run() 新建并关闭事件循环，
第二次请求会在新循环里复用绑定旧（已关闭）循环的锁，触发 "no running event loop"。
工具入口改为提交到唯一常驻后台事件循环后，多次调用必须稳定成功。
"""

from __future__ import annotations

import asyncio

import pytest

from app.tools.boss_browser import tool as boss_tool


class _LockHolder:
    """模拟单例服务：持有一个 asyncio.Lock，跨多次调用复用。

    真实场景里 RateLimiter.acquire 在持锁期间会 sleep 3-10s 的拟人抖动，并发请求
    必然在锁上排队竞争（contended），等待者的 future 会绑定到当时的事件循环。
    """

    def __init__(self) -> None:
        self._lock = asyncio.Lock()

    async def contend(self) -> str:
        # 制造一次锁竞争：先占锁，再起一个等待者协程争用同一把锁。
        await self._lock.acquire()
        async def _waiter() -> str:
            async with self._lock:
                return "ok"
        task = asyncio.ensure_future(_waiter())
        await asyncio.sleep(0)
        self._lock.release()
        return await task


def test_asyncio_run_per_call_breaks_shared_lock():
    """复现原始故障：竞争过的 asyncio.Lock 跨两个 asyncio.run 创建的循环复用必然失败。"""
    holder = _LockHolder()

    assert asyncio.run(holder.contend()) == "ok"
    with pytest.raises(RuntimeError, match="different event loop"):
        asyncio.run(holder.contend())


def test_run_on_loop_reuses_single_loop_for_shared_lock():
    """常驻后台循环：同一个锁多次竞争都成功，且每次跑在同一个事件循环上。"""
    holder = _LockHolder()

    async def _capture() -> int:
        await holder.contend()
        return id(asyncio.get_running_loop())

    loop_ids = {boss_tool._run_on_loop(_capture()) for _ in range(3)}
    assert len(loop_ids) == 1
