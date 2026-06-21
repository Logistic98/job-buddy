"""沙箱 Runtime SDK 请求与响应模型。"""

from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Mapping, Sequence


@dataclass(frozen=True, slots=True)
class ExecutionOptions:
    """沙箱命令执行选项。"""

    cwd: str | Path | None = None
    env: Mapping[str, str] | None = None
    timeout: float | None = None
    check: bool = True


@dataclass(frozen=True, slots=True)
class CommandSpec:
    """标准化命令请求。"""

    argv: Sequence[str] | None = None
    command: str | None = None
    options: ExecutionOptions = field(default_factory=ExecutionOptions)

    def __post_init__(self) -> None:
        if bool(self.argv) == bool(self.command):
            raise ValueError("argv 与 command 必须且只能提供一个")

    @classmethod
    def from_argv(cls, argv: Sequence[str], options: ExecutionOptions | None = None) -> "CommandSpec":
        return cls(argv=list(argv), options=options or ExecutionOptions())

    @classmethod
    def from_string(cls, command: str, options: ExecutionOptions | None = None) -> "CommandSpec":
        return cls(command=command, options=options or ExecutionOptions())


@dataclass(frozen=True, slots=True)
class CodeSpec:
    """临时代码文件执行请求。"""

    code: str
    suffix: str = ".py"
    interpreter: str | Sequence[str] | None = None
    args: Sequence[str] = field(default_factory=list)
    options: ExecutionOptions = field(default_factory=ExecutionOptions)


@dataclass(slots=True)
class SandboxResult:
    """沙箱命令结果。"""

    args: list[str]
    returncode: int
    stdout: str
    stderr: str

    @property
    def ok(self) -> bool:
        return self.returncode == 0
