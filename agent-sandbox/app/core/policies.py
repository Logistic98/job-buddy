"""沙箱策略工厂。"""

from __future__ import annotations

from pathlib import Path

from .config import FilesystemConfig, NetworkConfig, SandboxRuntimeConfig, default_config, workspace_only_config


class SandboxPolicies:
    """常用沙箱策略。"""

    @staticmethod
    def no_network_readonly(*, deny_sensitive_reads: bool = True) -> SandboxRuntimeConfig:
        deny_read = ["~/.ssh", "~/.aws", "~/.config/gcloud", "~/.kube"] if deny_sensitive_reads else []
        return SandboxRuntimeConfig(
            network=NetworkConfig(allowedDomains=[], deniedDomains=[]),
            filesystem=FilesystemConfig(
                denyRead=deny_read,
                allowRead=[],
                allowWrite=[],
                denyWrite=[".env", "secrets/"],
            ),
        )

    @staticmethod
    def workspace_readonly(workspace: str | Path) -> SandboxRuntimeConfig:
        return workspace_only_config(workspace, allow_write=False)

    @staticmethod
    def workspace_readwrite(workspace: str | Path) -> SandboxRuntimeConfig:
        return workspace_only_config(workspace, allow_write=True)

    @staticmethod
    def allow_domains(
        domains: list[str],
        *,
        writable_paths: list[str] | None = None,
        denied_domains: list[str] | None = None,
    ) -> SandboxRuntimeConfig:
        config = default_config(allow_write=writable_paths or [])
        config.network.allowedDomains = list(domains)
        config.network.deniedDomains = list(denied_domains or [])
        return config

    @staticmethod
    def custom(
        *,
        allowed_domains: list[str] | None = None,
        denied_domains: list[str] | None = None,
        deny_read: list[str] | None = None,
        allow_read: list[str] | None = None,
        allow_write: list[str] | None = None,
        deny_write: list[str] | None = None,
    ) -> SandboxRuntimeConfig:
        return SandboxRuntimeConfig(
            network=NetworkConfig(
                allowedDomains=allowed_domains or [],
                deniedDomains=denied_domains or [],
            ),
            filesystem=FilesystemConfig(
                denyRead=deny_read or [],
                allowRead=allow_read or [],
                allowWrite=allow_write or [],
                denyWrite=deny_write or [],
            ),
        )
