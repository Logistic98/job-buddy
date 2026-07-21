import uuid

from fastapi import FastAPI
from loguru import logger
from pydantic import BaseModel

from .grader import grade_capability_inventory, grade_latency, grade_run, grade_trace
from .internal_auth import install_internal_auth
from .judge import judge_enabled, judge_run

app = FastAPI(title="agent-eval", version="1.0.0")
install_internal_auth(app)


def _graded(op: str, fn) -> dict:
    """统一评分入口：生成 request_id、结构化日志，并把异常归一为标准信封。"""
    request_id = uuid.uuid4().hex[:12]
    bound = logger.bind(service="agent-eval", request_id=request_id, op=op)
    try:
        data = fn()
    except Exception as exc:  # noqa: BLE001 统一兜底，避免裸 500 traceback 流向调用方
        bound.exception(f"评估执行异常 error={exc}")
        return {"code": 500, "message": "评估执行失败，请检查输入或稍后重试", "data": {}}
    bound.info(f"评估完成 passed={data.get('passed')} score={data.get('score')}")
    return {"code": 200, "message": "success", "data": data}


class TraceEvalRequest(BaseModel):
    trace: list[dict]


class RunEvalRequest(BaseModel):
    run: dict
    expected: dict | None = None


class CapabilityInventoryEvalRequest(BaseModel):
    profile: dict


class LatencyEvalRequest(BaseModel):
    metrics: dict
    budget: dict | None = None


@app.get("/health")
def health() -> dict:
    return {
        "code": 200,
        "message": "success",
        "data": {"status": "UP", "service": "agent-eval", "judge_enabled": judge_enabled()},
    }


@app.post("/v1/eval/trace")
def eval_trace(request: TraceEvalRequest) -> dict:
    return _graded("trace", lambda: grade_trace(request.trace))


@app.post("/v1/eval/run")
def eval_run(request: RunEvalRequest) -> dict:
    return _graded("run", lambda: grade_run(request.run, request.expected or {}))


@app.post("/v1/eval/capabilities")
def eval_capabilities(request: CapabilityInventoryEvalRequest) -> dict:
    return _graded("capabilities", lambda: grade_capability_inventory(request.profile))


@app.post("/v1/eval/latency")
def eval_latency(request: LatencyEvalRequest) -> dict:
    return _graded("latency", lambda: grade_latency(request.metrics, request.budget or {}))


@app.post("/v1/eval/judge")
def eval_judge(request: RunEvalRequest) -> dict:
    """LLM Judge 开放质量评审。未配置或调用失败时返回 5xx 业务码，调用方不得将其当作通过。"""
    request_id = uuid.uuid4().hex[:12]
    bound = logger.bind(service="agent-eval", request_id=request_id, op="judge")
    result = judge_run(request.run, request.expected or {})
    ok = bool(result.get("enabled")) and bool(result.get("ok"))
    message = "success" if ok else str(result.get("reason") or "judge unavailable")
    bound.info(f"裁判评估完成 enabled={result.get('enabled')} ok={result.get('ok')}")
    return {"code": 200 if ok else 503, "message": message, "data": result}
