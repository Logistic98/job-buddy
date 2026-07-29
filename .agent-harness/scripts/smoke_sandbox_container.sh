#!/usr/bin/env bash
# 构建真实 Linux Sandbox 镜像并验证运行时与隔离边界。

set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
IMAGE_TAG="${SANDBOX_SMOKE_IMAGE_TAG:-job-buddy-sandbox-smoke:verification}"
CONTAINER_NAME="job-buddy-sandbox-smoke-$$"
INTERNAL_TOKEN="job-buddy-sandbox-smoke-token"

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
  --cap-drop ALL \
  --security-opt no-new-privileges \
  --security-opt seccomp=unconfined \
  --security-opt apparmor=unconfined \
  --read-only \
  --tmpfs /tmp:rw,noexec,nosuid,nodev,size=268435456 \
  --pids-limit 256 \
  --memory 1g \
  --cpus 2 \
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
import time
import urllib.error
import urllib.request

BASE_URL = "http://127.0.0.1:8061"
TOKEN = os.environ["AGENT_INTERNAL_SERVICE_TOKEN"]


def request(path, payload=None):
    data = None if payload is None else json.dumps(payload).encode()
    headers = {} if payload is None else {"Content-Type": "application/json"}
    if payload is not None:
        headers["X-Internal-Service-Token"] = TOKEN
    req = urllib.request.Request(BASE_URL + path, data=data, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=20) as response:
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

print("Sandbox container smoke verification passed")
PY
