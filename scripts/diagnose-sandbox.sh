#!/usr/bin/env bash
# 只读诊断服务器上的 agent-sandbox 容器、真实 srt readiness 与宿主机安全限制。

set -uo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ENV_FILE="${SANDBOX_DIAG_ENV_FILE:-$ROOT_DIR/.env.server}"
COMPOSE_FILE="${SANDBOX_DIAG_COMPOSE_FILE:-$ROOT_DIR/docker-compose.yml}"
CONTAINER_ID="${SANDBOX_DIAG_CONTAINER:-}"
SERVICE_NAME="agent-sandbox"

section() {
  printf '\n[%s]\n' "$1"
}

print_value() {
  printf '%-24s %s\n' "$1" "$2"
}

command_exists() {
  command -v "$1" >/dev/null 2>&1
}

section "诊断上下文"
print_value "time" "$(date -Iseconds 2>/dev/null || date)"
print_value "host" "$(hostname 2>/dev/null || printf 'unknown')"
print_value "repository" "$ROOT_DIR"
print_value "compose_file" "$COMPOSE_FILE"
print_value "env_file" "$ENV_FILE"

if ! command_exists docker; then
  printf '结论：未找到 docker 命令，无法继续诊断。\n' >&2
  exit 1
fi

if ! docker info >/dev/null 2>&1; then
  printf '结论：Docker daemon 不可访问，请确认当前用户权限和 Docker 服务状态。\n' >&2
  exit 1
fi

section "Docker 主机"
docker version --format 'client={{.Client.Version}} server={{.Server.Version}}' 2>/dev/null || docker version
docker info --format \
  'cpus={{.NCPU}} memory={{.MemTotal}} driver={{.Driver}} cgroup_driver={{.CgroupDriver}} cgroup_version={{.CgroupVersion}} os={{.OperatingSystem}} kernel={{.KernelVersion}}' \
  2>/dev/null || true

if [[ -z "$CONTAINER_ID" && -f "$COMPOSE_FILE" ]]; then
  compose_args=(docker compose)
  if [[ -f "$ENV_FILE" ]]; then
    compose_args+=(--env-file "$ENV_FILE")
  fi
  compose_args+=(-f "$COMPOSE_FILE")
  CONTAINER_ID="$("${compose_args[@]}" ps -q "$SERVICE_NAME" 2>/dev/null | head -n 1)"
fi

if [[ -z "$CONTAINER_ID" ]]; then
  CONTAINER_ID="$(
    docker ps -a \
      --filter "label=com.docker.compose.service=$SERVICE_NAME" \
      --format '{{.ID}}' 2>/dev/null | head -n 1
  )"
fi

if [[ -z "$CONTAINER_ID" ]]; then
  section "候选容器"
  docker ps -a --format 'table {{.ID}}\t{{.Names}}\t{{.Status}}' \
    --filter 'name=agent-sandbox' 2>/dev/null || true
  printf '结论：未找到 agent-sandbox 容器。可通过 SANDBOX_DIAG_CONTAINER 指定容器名称或 ID。\n' >&2
  exit 1
fi

if ! docker inspect "$CONTAINER_ID" >/dev/null 2>&1; then
  printf '结论：无法 inspect 容器 %s。\n' "$CONTAINER_ID" >&2
  exit 1
fi

CONTAINER_NAME="$(docker inspect "$CONTAINER_ID" --format '{{.Name}}')"
CONTAINER_NAME="${CONTAINER_NAME#/}"

section "容器状态"
print_value "container" "$CONTAINER_NAME"
docker inspect "$CONTAINER_ID" --format \
  'image={{.Config.Image}} status={{.State.Status}} running={{.State.Running}} restarting={{.State.Restarting}} health={{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}} exit={{.State.ExitCode}} oom_killed={{.State.OOMKilled}} error={{printf "%q" .State.Error}}'
docker inspect "$CONTAINER_ID" --format \
  'user={{.Config.User}} apparmor={{printf "%q" .AppArmorProfile}} readonly_rootfs={{.HostConfig.ReadonlyRootfs}} pids_limit={{.HostConfig.PidsLimit}} nano_cpus={{.HostConfig.NanoCpus}} memory_limit={{.HostConfig.Memory}} security_opt={{json .HostConfig.SecurityOpt}}'
docker inspect "$CONTAINER_ID" --format \
  'health_test={{json .Config.Healthcheck.Test}} interval_ns={{.Config.Healthcheck.Interval}} timeout_ns={{.Config.Healthcheck.Timeout}} start_period_ns={{.Config.Healthcheck.StartPeriod}} retries={{.Config.Healthcheck.Retries}}'

section "容器资源快照"
docker stats --no-stream --format \
  'name={{.Name}} cpu={{.CPUPerc}} memory={{.MemUsage}} memory_percent={{.MemPerc}} pids={{.PIDs}}' \
  "$CONTAINER_ID" 2>&1 || true
docker exec "$CONTAINER_ID" sh -c '
  printf "cpu.max="; cat /sys/fs/cgroup/cpu.max 2>/dev/null || printf "unavailable\n"
  printf "memory.max="; cat /sys/fs/cgroup/memory.max 2>/dev/null || printf "unavailable\n"
  printf "pids.current="; cat /sys/fs/cgroup/pids.current 2>/dev/null || printf "unavailable\n"
  printf "pids.max="; cat /sys/fs/cgroup/pids.max 2>/dev/null || printf "unavailable\n"
  cat /proc/pressure/cpu 2>/dev/null || true
' 2>&1 || true

section "Sandbox 非敏感运行参数"
docker exec -i "$CONTAINER_ID" python - <<'PY' 2>&1 || true
import os

keys = (
    "HOST",
    "PORT",
    "JOB_BUDDY_ENVIRONMENT",
    "AGENT_SANDBOX_SRT_BIN",
    "AGENT_SANDBOX_WORKSPACE_DIR",
    "AGENT_SANDBOX_MAX_CONCURRENCY",
    "AGENT_SANDBOX_MAX_OUTPUT_CHARS",
    "AGENT_SANDBOX_ENABLE_WEAKER_NESTED_SANDBOX",
    "AGENT_SANDBOX_READINESS_CACHE_SECONDS",
    "AGENT_SANDBOX_READINESS_TIMEOUT_SECONDS",
)
for key in keys:
    print(f"{key}={os.getenv(key, '<unset>')}")
PY

section "镜像内工具"
docker exec "$CONTAINER_ID" sh -c '
  id
  printf "srt="; command -v srt || true
  printf "python="; command -v python || true
  printf "node="; command -v node || true
  printf "bwrap="; command -v bwrap || true
  srt --version 2>&1 || true
' 2>&1 || true

section "HTTP 存活与就绪"
docker exec -i "$CONTAINER_ID" python - <<'PY' 2>&1 || true
import urllib.error
import urllib.request

for path in ("/health", "/ready"):
    request = urllib.request.Request("http://127.0.0.1:8061" + path)
    try:
        with urllib.request.urlopen(request, timeout=35) as response:
            print(f"{path} status={response.status} body={response.read().decode(errors='replace')}")
    except urllib.error.HTTPError as exc:
        print(f"{path} status={exc.code} body={exc.read().decode(errors='replace')}")
    except Exception as exc:
        print(f"{path} error={type(exc).__name__}: {exc}")
PY

section "真实 srt 探测"
SRT_PROBE_OUTPUT="$(
  docker exec -i "$CONTAINER_ID" python - <<'PY' 2>&1
import shutil
import tempfile
import traceback
from pathlib import Path

from app.server.app import _effective_config
from app.sdk import SandboxClient

workspace = Path(tempfile.mkdtemp(prefix="sandbox-diagnose-")).resolve()
try:
    client = SandboxClient(
        _effective_config(None, workspace),
        cwd=workspace,
        default_timeout=30,
    )
    result = client.command(
        ["/bin/sh", "-c", "printf '%s\\n' sandbox-ready"],
        timeout=30,
        check=False,
    )
    print(f"probe_returncode={result.returncode}")
    print("probe_stdout_begin")
    print(result.stdout, end="" if result.stdout.endswith("\n") else "\n")
    print("probe_stdout_end")
    print("probe_stderr_begin")
    print(result.stderr, end="" if result.stderr.endswith("\n") else "\n")
    print("probe_stderr_end")
except Exception as exc:
    print(f"probe_exception={type(exc).__name__}: {exc}")
    traceback.print_exc()
finally:
    shutil.rmtree(workspace, ignore_errors=True)
PY
)"
SRT_PROBE_STATUS=$?
printf '%s\n' "$SRT_PROBE_OUTPUT"
print_value "docker_exec_status" "$SRT_PROBE_STATUS"

section "Docker 健康检查历史"
docker inspect "$CONTAINER_ID" --format \
  '{{if .State.Health}}{{range .State.Health.Log}}{{println .Start "exit=" .ExitCode}}{{println .Output}}{{end}}{{else}}no healthcheck history{{end}}' \
  2>&1 | tail -n 120

section "Sandbox 最近日志"
SANDBOX_LOGS="$(docker logs --tail 240 "$CONTAINER_ID" 2>&1 || true)"
printf '%s\n' "$SANDBOX_LOGS" | tail -n 160

section "宿主机 namespace 与 AppArmor"
if [[ -r /proc/sys/kernel/unprivileged_userns_clone ]]; then
  print_value "unprivileged_userns_clone" "$(cat /proc/sys/kernel/unprivileged_userns_clone)"
else
  print_value "unprivileged_userns_clone" "unavailable"
fi
if [[ -r /proc/sys/kernel/apparmor_restrict_unprivileged_userns ]]; then
  print_value "apparmor_restrict_userns" "$(cat /proc/sys/kernel/apparmor_restrict_unprivileged_userns)"
else
  print_value "apparmor_restrict_userns" "unavailable"
fi

KERNEL_CLUES=""
if command_exists journalctl; then
  KERNEL_CLUES="$(
    journalctl -k --since '30 minutes ago' --no-pager 2>/dev/null \
      | grep -Ei 'apparmor|denied|userns|namespace|bwrap|oom|out of memory|killed process' \
      | tail -n 120 || true
  )"
elif command_exists dmesg; then
  KERNEL_CLUES="$(
    dmesg 2>/dev/null \
      | grep -Ei 'apparmor|denied|userns|namespace|bwrap|oom|out of memory|killed process' \
      | tail -n 120 || true
  )"
fi
if [[ -n "$KERNEL_CLUES" ]]; then
  printf '%s\n' "$KERNEL_CLUES"
else
  printf '未读取到相关内核日志；可能没有命中，也可能当前用户无权读取。\n'
fi

section "自动判断"
DIAGNOSIS_SOURCE="$SRT_PROBE_OUTPUT"$'\n'"$SANDBOX_LOGS"$'\n'"$KERNEL_CLUES"
OOM_KILLED="$(docker inspect "$CONTAINER_ID" --format '{{.State.OOMKilled}}' 2>/dev/null || printf 'false')"

if grep -Eq 'probe_returncode=0' <<<"$SRT_PROBE_OUTPUT"; then
  printf '结论：30 秒真实 srt 探测成功。如果 /ready 仍返回 503，优先检查当前 5 秒应用超时与 5 秒 Docker healthcheck 超时，单核主机可能无法稳定在该预算内完成 namespace 初始化。\n'
  exit 0
fi

if [[ "$OOM_KILLED" == "true" ]] || grep -Eiq 'out of memory|oom-kill|killed process' <<<"$DIAGNOSIS_SOURCE"; then
  printf '结论：检测到 OOM 线索。请提高 Sandbox 内存上限或降低并发，并检查宿主机可用内存与 swap。\n'
  exit 2
fi

if grep -Eiq 'apparmor.*denied|operation not permitted|permission denied|userns|user namespace|bwrap.*(fail|error)|namespace.*denied' <<<"$DIAGNOSIS_SOURCE"; then
  printf '结论：检测到 AppArmor、user namespace 或 bubblewrap 权限拦截线索。请确认当前 Sandbox 容器已按 docker-compose.yml 使用 apparmor=unconfined 和 seccomp=unconfined，并在配置更新后强制重建该容器；不要全局关闭宿主机 AppArmor。\n'
  exit 2
fi

if grep -Eiq 'timed out|timeouterror|timeout expired' <<<"$DIAGNOSIS_SOURCE"; then
  printf '结论：真实 srt 探测超时。若资源快照没有 OOM 或内核拒绝，优先提高应用 readiness 与 Docker healthcheck 的超时，并观察单核 CPU pressure。\n'
  exit 2
fi

if grep -Eiq 'no space left|read-only file system|permissionerror' <<<"$DIAGNOSIS_SOURCE"; then
  printf '结论：检测到临时目录、只读文件系统或空间限制线索。请检查 /tmp tmpfs、磁盘、inode 和 Sandbox 用户写权限。\n'
  exit 2
fi

if grep -Eiq 'not found|no such file or directory' <<<"$SRT_PROBE_OUTPUT"; then
  printf '结论：检测到 srt 或其底层工具缺失。请核对镜像中的 srt、bwrap、socat、node 与 Python 安装结果。\n'
  exit 2
fi

printf '结论：已确认真实 srt 探测失败，但现有输出未命中已知模式。请重点查看“真实 srt 探测”的 probe_stderr 和“宿主机 namespace 与 AppArmor”两节。\n'
exit 2
