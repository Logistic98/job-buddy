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

run_agent_eval() {
  if [[ ! -d agent-eval ]]; then
    log "agent-eval directory missing, skipping"
    return
  fi
  log "agent-eval: pytest grader"
  pushd agent-eval >/dev/null
  need_cmd uv "agent-eval"
  uv sync --extra dev --quiet || fail "agent-eval: uv sync --extra dev failed"
  uv run python -m pytest -q || fail "agent-eval: pytest failed"
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
  uv sync --extra dev --quiet || fail "agent-runtime: uv sync --extra dev failed"
  env -u JOB_BUDDY_RUNTIME_USE_LLM_PLANNER uv run python -m pytest -q \
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
  uv sync --extra dev --quiet || fail "agent-intent: uv sync --extra dev failed"
  env -u JOB_BUDDY_RUNTIME_USE_LLM_PLANNER uv run python -m pytest -q || fail "agent-intent: regression failed"
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
  uv sync --extra dev --quiet || fail "agent-tool: uv sync --extra dev failed"
  uv run python -m pytest -q tests/test_tools.py || fail "agent-tool: execution contract eval failed"
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
  uv sync --extra dev --quiet || fail "agent-memory: uv sync --extra dev failed"
  uv run python -m pytest -q tests/test_relevance.py tests/test_memory_api.py || fail "agent-memory: retrieval/lifecycle eval failed"
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
  uv sync --extra dev --quiet || fail "agent-sandbox: uv sync --extra dev failed"
  uv run python -m pytest -q tests/test_server.py || fail "agent-sandbox: isolation boundary eval failed"
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
    log "no behavioral evals defined for $TARGET yet, skipping"
    ;;
  *)
    fail "unknown eval target: $TARGET"
    ;;
esac

log "all evals passed"
