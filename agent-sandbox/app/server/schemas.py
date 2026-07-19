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
    timeout: float = Field(default=30.0, gt=0, le=120.0)
    check: bool = True


class CommandRequest(BaseModel):
    argv: list[str] | None = Field(default=None, max_length=256)
    command: str | None = Field(default=None, max_length=32768)
    policy: SandboxPolicySchema | None = None
    options: ExecutionOptionsSchema = Field(default_factory=ExecutionOptionsSchema)


class CliRequest(BaseModel):
    executable: str = Field(max_length=1024)
    args: list[str] = Field(default_factory=list, max_length=256)
    policy: SandboxPolicySchema | None = None
    options: ExecutionOptionsSchema = Field(default_factory=ExecutionOptionsSchema)


class ShellRequest(BaseModel):
    command: str = Field(max_length=32768)
    shell: str = Field(default="/bin/sh", max_length=1024)
    policy: SandboxPolicySchema | None = None
    options: ExecutionOptionsSchema = Field(default_factory=ExecutionOptionsSchema)


class PythonCodeRequest(BaseModel):
    code: str = Field(max_length=1048576)
    args: list[str] = Field(default_factory=list, max_length=256)
    python_bin: str | None = Field(default=None, max_length=1024)
    policy: SandboxPolicySchema | None = None
    options: ExecutionOptionsSchema = Field(default_factory=ExecutionOptionsSchema)


class CodeFileRequest(BaseModel):
    code: str = Field(max_length=1048576)
    suffix: str = Field(default=".py", max_length=32, pattern=r"^\.[A-Za-z0-9]+$")
    interpreter: str | list[str] | None = None
    args: list[str] = Field(default_factory=list, max_length=256)
    policy: SandboxPolicySchema | None = None
    options: ExecutionOptionsSchema = Field(default_factory=ExecutionOptionsSchema)


class SandboxResponse(BaseModel):
    ok: bool
    returncode: int
    stdout: str
    stderr: str
    args: list[str]
