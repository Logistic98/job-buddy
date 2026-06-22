#!/usr/bin/env bash
set -euo pipefail

MODULE_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$MODULE_DIR"

ROOT_DIR="$(cd "$MODULE_DIR/.." && pwd)"
if [[ -f "$ROOT_DIR/.env" ]]; then
  set -a
  # shellcheck source=/dev/null
  source "$ROOT_DIR/.env"
  set +a
fi

PORT="${PORT:-8040}"
BOSS_CLI_HOME="${BOSS_CLI_HOME:-$ROOT_DIR/.run/boss-cli-home}"
if [[ "$BOSS_CLI_HOME" != /* ]]; then
  BOSS_CLI_HOME="$ROOT_DIR/$BOSS_CLI_HOME"
fi
echo "[agent-tool] starting FastAPI on port ${PORT}"
echo "[agent-tool] boss-cli home: ${BOSS_CLI_HOME}"

export BOSS_CLI_HOME
uv sync --extra dev
# 二维码登录缺少 __zp_stoken__ 时需用 headless Chromium 补齐 Web 关键 Cookie。
# 仅在缺失浏览器内核时安装，避免每次启动重复下载。
if [[ "${BOSS_CLI_HEADLESS_COOKIE:-true}" != "false" ]]; then
  uv run python -m playwright install chromium >/dev/null 2>&1 || \
    echo "[agent-tool] WARN: playwright chromium install failed; QR login may miss __zp_stoken__"
fi
PORT="$PORT" uv run python -m uvicorn app.server:app --host 0.0.0.0 --port "$PORT"
