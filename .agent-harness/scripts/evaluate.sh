#!/usr/bin/env bash
# Run deterministic evaluation checks. This is separate from unit tests so future
# development can distinguish "tests passed" from "behavioral evals passed".
#
# Usage:
#   evaluate.sh                 # run all evals
#   evaluate.sh agent-backend   # run backend contract evals + eval service tests
#   evaluate.sh agent-eval      # run eval service tests only
#   evaluate.sh agent-intent    # run intent regression tests
#   evaluate.sh agent-tool      # run tool execution contract evals
#   evaluate.sh agent-memory    # run retrieval ranking + memory lifecycle evals
#   evaluate.sh agent-sandbox   # run sandbox isolation boundary evals

set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
cd "$REPO_ROOT"

export MAVEN_OPTS="${MAVEN_OPTS:-} -Dfile.encoding=UTF-8 -Dsun.jnu.encoding=UTF-8"

TARGET="${1:-all}"

log() { printf "[eval] %s\n" "$*"; }
fail() { printf "[eval] FAIL: %s\n" "$*" >&2; exit 1; }
need_cmd() { command -v "$1" >/dev/null 2>&1 || fail "$2 requires '$1' in PATH"; }
run_python_tests() {
  env -u JOB_BUDDY_RUNTIME_USE_LLM_PLANNER \
    AGENT_INTERNAL_SERVICE_TOKEN= \
    JOB_BUDDY_ENVIRONMENT=development \
    uv run python -m pytest "$@"
}

run_agent_eval() {
  if [[ ! -d agent-eval ]]; then
    log "agent-eval directory missing, skipping"
    return
  fi
  log "agent-eval: pytest grader"
  pushd agent-eval >/dev/null
  need_cmd uv "agent-eval"
  uv sync --frozen --extra dev --quiet || fail "agent-eval: uv sync --frozen --extra dev failed"
  run_python_tests -q || fail "agent-eval: pytest failed"
  uv run python - <<'PY' || exit 1
from app.grader import grade_run, grade_trace
trace = [{"nodeId": node} for node in ["A", "D1", "E", "F", "Z", "AH"]]
result = grade_trace(trace)
assert result["passed"] is True, result
assert result["score"] == 1.0, result
bad_run = {
    "status": "success",
    "answer": "Runtime 已完成任务理解，但未返回可展示回答。",
    "directive": {"domain": "job", "intent": "job.recommend", "router": "llm", "confidence": 0.95, "next_action": "call_get_recommend_jobs"},
    "trace_events": [{"event": event} for event in ["run_start", "understand_goal", "task_understanding", "capability_route", "finalize", "run_end"]],
    "tool_events": [{"id": "job_search", "status": "success", "summary": "读取岗位 Fixture"}],
}
quality = grade_run(bad_run, {"intent": "job.recommend", "domain": "job"})
assert quality["passed"] is False, quality
assert any(issue["code"] == "no_fixture_or_mock_claims" for issue in quality["issues"]), quality
print("[eval] agent-eval trace and quality gates passed")
PY
  uv run python scripts/run_engine_eval.py --self-check || fail "agent-eval: engine eval self-check failed"
  uv run python - <<'PY' || exit 1
from pathlib import Path
import yaml
for path in sorted(Path("cases").glob("*.yaml")):
    spec = yaml.safe_load(path.read_text(encoding="utf-8"))
    assert isinstance(spec, dict) and spec.get("version"), f"invalid eval suite: {path}"
    assert isinstance(spec.get("cases"), list) and spec["cases"], f"empty eval suite: {path}"
    ids = [case.get("id") for case in spec["cases"]]
    assert all(ids) and len(ids) == len(set(ids)), f"invalid or duplicate case ids: {path}"
    print(f"[eval] loaded {path}: cases={len(ids)}")
PY
  popd >/dev/null
}

run_backend_eval() {
  log "agent-backend: core trace and task-understanding contract tests"
  pushd agent-backend >/dev/null
  if [[ -x ./mvnw ]]; then
    ./mvnw -q -Dtest='*AgentFlowTraceContractTest,*IntentRoutingContractTest' test || fail "agent-backend: trace/task-understanding eval failed"
  else
    need_cmd mvn "agent-backend"
    mvn -q -Dtest='*AgentFlowTraceContractTest,*IntentRoutingContractTest' test || fail "agent-backend: trace/task-understanding eval failed"
  fi
  popd >/dev/null
  run_agent_eval
}

run_runtime_eval() {
  if [[ ! -d agent-runtime ]]; then
    log "agent-runtime directory missing, skipping"
    return
  fi
  log "agent-runtime: task-understanding/profile regression evals"
  pushd agent-runtime >/dev/null
  need_cmd uv "agent-runtime"
  uv sync --frozen --extra dev --quiet || fail "agent-runtime: uv sync --frozen --extra dev failed"
  run_python_tests -q \
    tests/test_job_buddy_router.py \
    tests/test_task_understanding_profile.py \
    tests/test_agent_executor.py || fail "agent-runtime: task-understanding/profile eval failed"
  popd >/dev/null
}

run_intent_eval() {
  if [[ ! -d agent-intent ]]; then
    log "agent-intent directory missing, skipping"
    return
  fi
  log "agent-intent: job-domain, layered-routing, clarification-gate and transcript-review regression evals"
  pushd agent-intent >/dev/null
  need_cmd uv "agent-intent"
  uv sync --frozen --extra dev --quiet || fail "agent-intent: uv sync --frozen --extra dev failed"
  run_python_tests -q || fail "agent-intent: regression failed"
  popd >/dev/null
}

run_tool_eval() {
  if [[ ! -d agent-tool ]]; then
    log "agent-tool directory missing, skipping"
    return
  fi
  log "agent-tool: execution contract evals (8-element registry, confirm gate, error structure)"
  pushd agent-tool >/dev/null
  need_cmd uv "agent-tool"
  uv sync --frozen --extra dev --quiet || fail "agent-tool: uv sync --frozen --extra dev failed"
  run_python_tests -q tests/test_tools.py || fail "agent-tool: execution contract eval failed"
  popd >/dev/null
}

run_memory_eval() {
  if [[ ! -d agent-memory ]]; then
    log "agent-memory directory missing, skipping"
    return
  fi
  log "agent-memory: retrieval ranking and memory lifecycle contract evals"
  pushd agent-memory >/dev/null
  need_cmd uv "agent-memory"
  uv sync --frozen --extra dev --quiet || fail "agent-memory: uv sync --frozen --extra dev failed"
  run_python_tests -q tests/test_relevance.py tests/test_memory_api.py || fail "agent-memory: retrieval/lifecycle eval failed"
  popd >/dev/null
}

run_frontend_eval() {
  if [[ ! -d agent-frontend ]]; then
    log "agent-frontend directory missing, skipping"
    return
  fi
  log "agent-frontend: SSE lifecycle and auth-reset behavior evals"
  pushd agent-frontend >/dev/null
  need_cmd npm "agent-frontend"
  [[ -d node_modules ]] || npm ci --silent || fail "agent-frontend: npm ci failed"
  npm test -- tests/storeChat.test.js tests/storeJob.test.js tests/storeResume.test.js tests/authStore.test.js \
    || fail "agent-frontend: lifecycle behavior eval failed"
  popd >/dev/null
}

run_sandbox_eval() {
  if [[ ! -d agent-sandbox ]]; then
    log "agent-sandbox directory missing, skipping"
    return
  fi
  log "agent-sandbox: isolation boundary contract evals (policy hardening, env isolation)"
  pushd agent-sandbox >/dev/null
  need_cmd uv "agent-sandbox"
  uv sync --frozen --extra dev --quiet || fail "agent-sandbox: uv sync --frozen --extra dev failed"
  run_python_tests -q tests/test_server.py || fail "agent-sandbox: isolation boundary eval failed"
  popd >/dev/null
}

case "$TARGET" in
  all)
    run_runtime_eval
    run_backend_eval
    run_intent_eval
    run_tool_eval
    run_memory_eval
    run_sandbox_eval
    run_frontend_eval
    ;;
  agent-backend|backend)
    run_backend_eval
    ;;
  agent-eval|eval)
    run_agent_eval
    ;;
  agent-runtime|runtime)
    run_runtime_eval
    ;;
  agent-intent|intent)
    run_intent_eval
    ;;
  agent-tool|tool)
    run_tool_eval
    ;;
  agent-memory|memory)
    run_memory_eval
    ;;
  agent-sandbox|sandbox)
    run_sandbox_eval
    ;;
  agent-frontend)
    run_frontend_eval
    ;;
  *)
    fail "unknown eval target: $TARGET"
    ;;
esac

log "all evals passed"
