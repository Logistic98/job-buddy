"""策略工厂必须按显式授权收窄网络和文件权限。"""

import pytest

from app.core.policies import SandboxPolicies


@pytest.mark.parametrize("sensitive", [True, False])
def test_readonly_policy_never_grants_network_or_write_access(sensitive):
    policy = SandboxPolicies.no_network_readonly(deny_sensitive_reads=sensitive)
    assert policy.network.allowedDomains == []
    assert policy.filesystem.allowWrite == []
    assert bool(policy.filesystem.denyRead) is sensitive
    assert ".env" in policy.filesystem.denyWrite


def test_domain_policy_copies_allowlist_and_keeps_denials():
    domains = ["example.invalid"]
    policy = SandboxPolicies.allow_domains(domains, writable_paths=["/tmp/work"], denied_domains=["private.invalid"])
    domains.clear()
    assert policy.network.allowedDomains == ["example.invalid"]
    assert policy.network.deniedDomains == ["private.invalid"]
    assert policy.filesystem.allowWrite == ["/tmp/work"]


def test_custom_policy_preserves_explicit_boundaries():
    policy = SandboxPolicies.custom(
        allowed_domains=["example.invalid"],
        denied_domains=["private.invalid"],
        deny_read=["/secrets"],
        allow_read=["/input"],
        allow_write=["/output"],
        deny_write=["/output/locked"],
    )
    assert policy.filesystem.denyRead == ["/secrets"]
    assert policy.filesystem.allowRead == ["/input"]
    assert policy.filesystem.allowWrite == ["/output"]
    assert policy.filesystem.denyWrite == ["/output/locked"]
    assert policy.network.allowedDomains == ["example.invalid"]
    assert policy.network.deniedDomains == ["private.invalid"]
