"""Sandbox Runtime 配置模型。

字段对齐上游 @anthropic-ai/sandbox-runtime 的 ~/.srt-settings.json。
"""

from __future__ import annotations

from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any


@dataclass(slots=True)
class NetworkConfig:
    """网络访问策略；allowedDomains 为空表示关闭网络。"""

    allowedDomains: list[str] = field(default_factory=list)
    deniedDomains: list[str] = field(default_factory=list)
    allowUnixSockets: list[str] = field(default_factory=list)
    allowAllUnixSockets: bool = False
    allowLocalBinding: bool = False


@dataclass(slots=True)
class FilesystemConfig:
    """文件系统读写策略；读先拒绝后放行，写只允许白名单。"""

    denyRead: list[str] = field(default_factory=list)
    allowRead: list[str] = field(default_factory=list)
    allowWrite: list[str] = field(default_factory=list)
    denyWrite: list[str] = field(default_factory=list)


@dataclass(slots=True)
class SandboxRuntimeConfig:
    """sandbox-runtime 顶层配置。"""

    network: NetworkConfig = field(default_factory=NetworkConfig)
    filesystem: FilesystemConfig = field(default_factory=FilesystemConfig)
    ignoreViolations: dict[str, list[str]] = field(default_factory=dict)
    enableWeakerNestedSandbox: bool = False
    enableWeakerNetworkIsolation: bool = False
    mandatoryDenySearchDepth: int | None = None

    def to_dict(self) -> dict[str, Any]:
        data = asdict(self)
        if self.mandatoryDenySearchDepth is None:
            data.pop("mandatoryDenySearchDepth", None)
        return data

    @classmethod
    def from_dict(cls, data: dict[str, Any] | None) -> "SandboxRuntimeConfig":
        payload = dict(data or {})
        network = payload.get("network")
        filesystem = payload.get("filesystem")
        if isinstance(network, dict):
            payload["network"] = NetworkConfig(**network)
        if isinstance(filesystem, dict):
            payload["filesystem"] = FilesystemConfig(**filesystem)
        return cls(**payload)


def default_config(*, allow_write: list[str] | None = None) -> SandboxRuntimeConfig:
    """返回本地执行的安全默认配置。"""

    return SandboxRuntimeConfig(
        network=NetworkConfig(allowedDomains=[], deniedDomains=[]),
        filesystem=FilesystemConfig(
            denyRead=["~/.ssh", "~/.aws", "~/.config/gcloud", "~/.kube"],
            allowRead=[],
            allowWrite=allow_write or [],
            denyWrite=[".env", "secrets/", "config/production.json"],
        ),
    )


def workspace_only_config(workspace: str | Path, *, allow_write: bool = False) -> SandboxRuntimeConfig:
    """仅允许读取指定 workspace。"""

    workspace_path = str(Path(workspace).resolve())
    home_root = str(Path.home())
    return SandboxRuntimeConfig(
        network=NetworkConfig(allowedDomains=[], deniedDomains=[]),
        filesystem=FilesystemConfig(
            denyRead=[home_root],
            allowRead=[workspace_path],
            allowWrite=[workspace_path] if allow_write else [],
            denyWrite=[".env", "secrets/", ".git/"],
        ),
    )
