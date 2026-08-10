"""构造与当前运行平台一致的 Boss 浏览器请求标识。"""

from __future__ import annotations

import platform
import re
from collections.abc import Mapping

_USER_AGENT_PLATFORM_PATTERN = re.compile(r"\([^)]*\)")


def platform_browser_headers(
    source: Mapping[str, str],
    *,
    system: str | None = None,
    machine: str | None = None,
) -> dict[str, str]:
    """返回与当前操作系统一致的浏览器请求头，不修改调用方数据。

    boss-cli 的默认浏览器标识面向 macOS。Linux 容器若直接复用该标识，页面脚本会同时
    观察到 macOS User-Agent 和 Linux navigator.platform，导致临时安全令牌无法生成。
    这里只校正操作系统标识，不伪造登录态、Cookie 或安全验证结果。
    """

    headers = {str(name): str(value) for name, value in source.items()}
    user_agent = headers.get("User-Agent", "").strip()
    runtime_system = (system or platform.system()).strip().lower()
    runtime_machine = (machine or platform.machine()).strip().lower()

    if runtime_system == "linux":
        architecture = "x86_64" if runtime_machine in {"", "amd64", "x86_64"} else runtime_machine
        headers["User-Agent"] = _replace_user_agent_platform(
            user_agent,
            f"(X11; Linux {architecture})",
        )
        headers["sec-ch-ua-platform"] = '"Linux"'
    elif runtime_system == "windows":
        headers["User-Agent"] = _replace_user_agent_platform(
            user_agent,
            "(Windows NT 10.0; Win64; x64)",
        )
        headers["sec-ch-ua-platform"] = '"Windows"'
    elif runtime_system == "darwin":
        headers["sec-ch-ua-platform"] = '"macOS"'

    return headers


def _replace_user_agent_platform(user_agent: str, replacement: str) -> str:
    if not user_agent:
        return user_agent
    return _USER_AGENT_PLATFORM_PATTERN.sub(replacement, user_agent, count=1)
