"""HTTP API 模型。"""

from __future__ import annotations

from pydantic import BaseModel, Field


class NetworkPolicySchema(BaseModel):
    allowedDomains: list[str] = Field(default_factory=list)
    deniedDomains: list[str] = Field(default_factory=list)
    allowUnixSockets: list[str] = Field(default_factory=list)
    allowAllUnixSockets: bool = False
    allowLocalBinding: bool = False


class FilesystemPolicySchema(BaseModel):
    denyRead: list[str] = Field(default_factory=list)
    allowRead: list[str] = Field(default_factory=list)
    allowWrite: list[str] = Field(default_factory=list)
    denyWrite: list[str] = Field(default_factory=list)


class SandboxPolicySchema(BaseModel):
    network: NetworkPolicySchema = Field(default_factory=NetworkPolicySchema)
    filesystem: FilesystemPolicySchema = Field(default_factory=FilesystemPolicySchema)
    ignoreViolations: dict[str, list[str]] = Field(default_factory=dict)
    enableWeakerNestedSandbox: bool = False
    enableWeakerNetworkIsolation: bool = False
    mandatoryDenySearchDepth: int | None = None


class ExecutionOptionsSchema(BaseModel):
    cwd: str | None = None
    env: dict[str, str] | None = None
    timeout: float | None = None
    check: bool = True


class CommandRequest(BaseModel):
    argv: list[str] | None = None
    command: str | None = None
    policy: SandboxPolicySchema | None = None
    options: ExecutionOptionsSchema = Field(default_factory=ExecutionOptionsSchema)


class CliRequest(BaseModel):
    executable: str
    args: list[str] = Field(default_factory=list)
    policy: SandboxPolicySchema | None = None
    options: ExecutionOptionsSchema = Field(default_factory=ExecutionOptionsSchema)


class ShellRequest(BaseModel):
    command: str
    shell: str = "/bin/sh"
    policy: SandboxPolicySchema | None = None
    options: ExecutionOptionsSchema = Field(default_factory=ExecutionOptionsSchema)


class PythonCodeRequest(BaseModel):
    code: str
    args: list[str] = Field(default_factory=list)
    python_bin: str | None = None
    policy: SandboxPolicySchema | None = None
    options: ExecutionOptionsSchema = Field(default_factory=ExecutionOptionsSchema)


class CodeFileRequest(BaseModel):
    code: str
    suffix: str = ".py"
    interpreter: str | list[str] | None = None
    args: list[str] = Field(default_factory=list)
    policy: SandboxPolicySchema | None = None
    options: ExecutionOptionsSchema = Field(default_factory=ExecutionOptionsSchema)


class SandboxResponse(BaseModel):
    ok: bool
    returncode: int
    stdout: str
    stderr: str
    args: list[str]
