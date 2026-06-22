#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TDAI_DIR="${TDAI_MEMORY_DIR:-$ROOT_DIR/.external/TencentDB-Agent-Memory}"
LOG_DIR="$ROOT_DIR/.run/logs"
PID_DIR="$ROOT_DIR/.run/pids"
mkdir -p "$LOG_DIR" "$PID_DIR" "$(dirname "$TDAI_DIR")"

if ! command -v docker >/dev/null 2>&1; then
  echo "[tdai-memory] docker not found. TencentDB Agent Memory Hermes gateway requires Docker for this startup script." >&2
  exit 1
fi

if [[ ! -d "$TDAI_DIR/.git" ]]; then
  echo "[tdai-memory] cloning into $TDAI_DIR"
  git clone https://github.com/Tencent/TencentDB-Agent-Memory.git "$TDAI_DIR"
fi

cd "$TDAI_DIR/docker/opensource"
IMAGE="${TDAI_MEMORY_IMAGE:-tdai-hermes-memory}"
NAME="${TDAI_MEMORY_CONTAINER:-tdai-hermes-memory}"
PORT="${TDAI_MEMORY_PORT:-8420}"

echo "[tdai-memory] building image $IMAGE"
docker build -f Dockerfile.hermes -t "$IMAGE" .

if docker ps -a --format '{{.Names}}' | grep -qx "$NAME"; then
  docker rm -f "$NAME" >/dev/null 2>&1 || true
fi

echo "[tdai-memory] starting container $NAME on port $PORT"
docker run -d \
  --name "$NAME" \
  --restart unless-stopped \
  -p "$PORT:8420" \
  -e MODEL_API_KEY="${MODEL_API_KEY:-}" \
  -e MODEL_BASE_URL="${MODEL_BASE_URL:-}" \
  -e MODEL_NAME="${MODEL_NAME:-}" \
  -e MODEL_PROVIDER="${MODEL_PROVIDER:-custom}" \
  -e TDAI_GATEWAY_API_KEY="${TDAI_GATEWAY_API_KEY:-}" \
  -v tdai_memory_data:/opt/data \
  "$IMAGE" >/tmp/tdai-memory.cid

docker inspect -f '{{.State.Pid}}' "$NAME" > "$PID_DIR/tdai-memory.pid" || true
echo "[tdai-memory] health: http://localhost:$PORT/health"
