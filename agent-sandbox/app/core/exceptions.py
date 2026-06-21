"""沙箱 Runtime SDK 异常层级。"""

from __future__ import annotations


class SandboxRuntimeError(RuntimeError):
    """沙箱 Runtime 基础异常。"""


class SandboxCommandNotFoundError(SandboxRuntimeError):
    """未找到 srt 可执行文件。"""


class SandboxProcessError(SandboxRuntimeError):
    """沙箱进程非零退出。"""

    def __init__(self, message: str, *, returncode: int, stdout: str = "", stderr: str = "") -> None:
        super().__init__(message)
        self.returncode = returncode
        self.stdout = stdout
        self.stderr = stderr
