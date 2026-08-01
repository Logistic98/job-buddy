#!/usr/bin/env bash
# 构建真实 Linux Sandbox 镜像并验证运行时与隔离边界。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE_TAG="${SANDBOX_SMOKE_IMAGE_TAG:-job-buddy-sandbox-smoke:verification}"
CONTAINER_NAME="job-buddy-sandbox-smoke-$$"
INTERNAL_TOKEN="job-buddy-sandbox-smoke-token"
CPU_LIMIT="${SANDBOX_SMOKE_CPU_LIMIT:-1}"

cleanup() {
  local status=$?
  trap - EXIT
  if [[ "$status" -ne 0 ]]; then
    docker logs --tail 200 "$CONTAINER_NAME" >&2 2>/dev/null || true
  fi
  docker rm -f "$CONTAINER_NAME" >/dev/null 2>&1 || true
  if [[ "${SANDBOX_SMOKE_KEEP_IMAGE:-0}" != "1" ]]; then
    docker image rm "$IMAGE_TAG" >/dev/null 2>&1 || true
  fi
  exit "$status"
}
trap cleanup EXIT

command -v docker >/dev/null 2>&1 || {
  printf '%s\n' "docker is required for Sandbox container smoke verification" >&2
  exit 1
}
docker info >/dev/null 2>&1 || {
  printf '%s\n' "docker daemon is unavailable" >&2
  exit 1
}

docker build --progress=plain -t "$IMAGE_TAG" "$ROOT_DIR/agent-sandbox"
docker run --detach \
  --name "$CONTAINER_NAME" \
  --init \
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --security-opt seccomp=unconfined \
  --security-opt apparmor=unconfined \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=268435456 \
  --tmpfs /run/job-buddy-sandbox-deps:rw,exec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=268435456 \
  --tmpfs /var/cache/job-buddy-sandbox-uv:rw,noexec,nosuid,nodev,uid=10001,gid=10001,mode=0700,size=134217728 \
  --pids-limit 256 \
  --memory 1g \
  --cpus "$CPU_LIMIT" \
  -e HOST=0.0.0.0 \
  -e JOB_BUDDY_ENVIRONMENT=development \
  -e AGENT_INTERNAL_SERVICE_TOKEN="$INTERNAL_TOKEN" \
  -e AGENT_SANDBOX_ENABLE_WEAKER_NESTED_SANDBOX=true \
  "$IMAGE_TAG" >/dev/null

container_user="$(docker inspect "$CONTAINER_NAME" --format '{{.Config.User}}')"
if [[ -z "$container_user" || "$container_user" == "root" || "$container_user" == "0" ]]; then
  printf 'Sandbox image must use a non-root user, got: %s\n' "$container_user" >&2
  exit 1
fi

docker exec -i "$CONTAINER_NAME" python - <<'PY'
import json
import os
import pathlib
import time
import urllib.error
import urllib.request

BASE_URL = "http://127.0.0.1:8061"
TOKEN = os.environ["AGENT_INTERNAL_SERVICE_TOKEN"]


def request(path, payload=None, timeout=20):
    data = None if payload is None else json.dumps(payload).encode()
    headers = {} if payload is None else {"Content-Type": "application/json"}
    if payload is not None:
        headers["X-Internal-Service-Token"] = TOKEN
    req = urllib.request.Request(BASE_URL + path, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=timeout) as response:
            return response.status, json.load(response)
    except urllib.error.HTTPError as exc:
        return exc.code, json.load(exc)


deadline = time.monotonic() + 45
last_ready = None
while time.monotonic() < deadline:
    try:
        status, body = request("/ready")
        last_ready = (status, body)
        if status == 200:
            break
    except Exception as exc:
        last_ready = repr(exc)
    time.sleep(0.5)
else:
    raise AssertionError(f"Sandbox readiness did not pass: {last_ready}")

baseline_pids = int(pathlib.Path("/sys/fs/cgroup/pids.current").read_text().strip())

toolchain_command = r"""
set -eu
python3 -c "import os; assert 'AGENT_INTERNAL_SERVICE_TOKEN' not in os.environ; print('python=42')"
node -e "console.log('javascript=' + (6 * 7))"
printf '%s\n' 'public class Smoke { public static void main(String[] args) { System.out.println("java=" + (6 * 7)); } }' > Smoke.java
javac Smoke.java
java Smoke
"""
status, body = request(
    "/v1/shell",
    {
        "command": toolchain_command,
        "options": {"timeout": 30, "check": True},
    },
)
assert status == 200, body
lines = set(body["stdout"].splitlines())
assert {"python=42", "javascript=42", "java=42"} <= lines, body

status, body = request(
    "/v1/code-file",
    {
        "code": "import numpy as np; print(int(np.sum(np.arange(100))))",
        "suffix": ".py",
        "interpreter": ["python3"],
        "dependencies": ["numpy"],
        "dependency_timeout": 90,
        "options": {"timeout": 25, "check": True},
    },
    timeout=100,
)
assert status == 200, body
assert body["stdout"].strip() == "4950", body
assert not list(pathlib.Path("/tmp").glob("job-buddy-sandbox-work-*/job-buddy-sandbox-code-*")), body
assert list(pathlib.Path("/run/job-buddy-sandbox-deps").iterdir()) == [], body
cache_root = pathlib.Path("/var/cache/job-buddy-sandbox-uv")
assert any(cache_root.iterdir()), body
cache_stats = os.statvfs(cache_root)
assert cache_stats.f_blocks * cache_stats.f_frsize <= 134217728, cache_stats

status, body = request(
    "/v1/code-file",
    {
        "code": "import numpy as np; print(int(np.sum(np.arange(10))))",
        "suffix": ".py",
        "interpreter": ["python3"],
        "dependencies": ["numpy"],
        "dependency_timeout": 90,
        "options": {"timeout": 25, "check": True},
    },
    timeout=100,
)
assert status == 200, body
assert body["stdout"].strip() == "45", body
assert list(pathlib.Path("/run/job-buddy-sandbox-deps").iterdir()) == [], body

blocked_commands = [
    "touch /etc/job-buddy-sandbox-escape",
    """python3 -c "import socket; socket.create_connection(('1.1.1.1', 53), 1)" """,
    """python3 -c "import socket; socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)" """,
]
for command in blocked_commands:
    status, body = request(
        "/v1/shell",
        {
            "command": command,
            "options": {"timeout": 10, "check": True},
        },
    )
    assert status == 422, (command, status, body)

status, body = request(
    "/v1/shell",
    {
        "command": "sleep 30",
        "options": {"timeout": 1, "check": True},
    },
)
assert status == 504, (status, body)
assert "timed out" in str(body).lower(), body

deadline = time.monotonic() + 3
while True:
    zombies = []
    leaked_processes = []
    for entry in pathlib.Path("/proc").iterdir():
        if not entry.name.isdigit():
            continue
        try:
            fields = (entry / "stat").read_text().split()
            command = (entry / "cmdline").read_bytes().replace(b"\0", b" ").decode(errors="replace")
        except (FileNotFoundError, PermissionError, ProcessLookupError):
            continue
        if len(fields) > 2 and fields[2] == "Z":
            zombies.append(entry.name)
        if any(name in command for name in ("srt", "bwrap", "socat", "sleep 30")):
            leaked_processes.append((entry.name, command[:200]))
    current_pids = int(pathlib.Path("/sys/fs/cgroup/pids.current").read_text().strip())
    if (
        not zombies
        and not leaked_processes
        and current_pids <= baseline_pids + 2
    ) or time.monotonic() >= deadline:
        break
    time.sleep(0.05)
assert not zombies, f"Sandbox container leaked zombie processes: {zombies}"
assert not leaked_processes, f"Sandbox container leaked live processes: {leaked_processes}"
assert current_pids <= baseline_pids + 2, (
    f"Sandbox container PID usage did not return to baseline: "
    f"before={baseline_pids} after={current_pids}"
)

print("Sandbox container smoke verification passed")
PY
