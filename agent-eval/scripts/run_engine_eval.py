#!/usr/bin/env python3
"""智能引擎效果/速度/过程联合评估 runner。

直连 agent-runtime 流式入口 /v1/agent/runs/stream，逐事件采集真实时延与 trace，
对每个用例分别评估三类信号并落盘报告，用于性能与质量的迭代回归：

  效果（effect）：意图/领域/动作是否正确、是否拒绝越权、回答是否可用。
  速度（speed） ：首个可见反馈 ttfb、首 token ttft、总时延 done 是否在预算内。
  过程（process）：trace 事件流是否完整有序。

风控：标记 requires_live_boss 的用例默认跳过，需 --allow-boss 才执行，避免评估期间高频请求 Boss。

用法：
  uv run python scripts/run_engine_eval.py \
    --runtime-url http://127.0.0.1:8010 \
    --cases cases/engine-eval-v1.yaml \
    --repeats 3 \
    --out reports

  # 离线自检（不连真实引擎，用内置样例事件流验证评分链路）：
  uv run python scripts/run_engine_eval.py --self-check
"""

from __future__ import annotations

import argparse
import json
import os
import statistics
import sys
import time
from datetime import datetime, timezone
from pathlib import Path
from typing import Any, Iterable

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))

from app.grader import grade_latency, grade_run, grade_trace  # noqa: E402


def _load_cases(path: Path) -> dict:
    import yaml  # 延迟导入，自检模式无需 yaml

    with path.open("r", encoding="utf-8") as fh:
        return yaml.safe_load(fh)


def _now_ms(start: float) -> int:
    return int((time.perf_counter() - start) * 1000)


def _case_payload(case: dict) -> dict:
    """构造与生产任务理解协议一致的请求，允许用例提供多轮消息和结构化会话槽位。"""
    messages = case.get("messages")
    if not isinstance(messages, list) or not messages:
        messages = [{"role": "user", "content": case["input"]}]
    metadata = {
        "eval": True,
        "case_id": case.get("id"),
        # 与生产链路对齐：Java 后端固定注入 job-buddy profile，缺失会回退为 general.chat。
        "profile": case.get("runtime_profile", "job-buddy"),
    }
    if isinstance(case.get("metadata"), dict):
        metadata.update(case["metadata"])
    payload = {"messages": messages, "stream": True, "metadata": metadata}
    if case.get("session_id"):
        payload["session_id"] = case["session_id"]
    return payload


def _runtime_headers() -> dict[str, str]:
    """读取服务间共享令牌；只注入请求头，不进入报告、日志或用例载荷。"""
    token = os.getenv("AGENT_INTERNAL_SERVICE_TOKEN", "").strip()
    return {"X-Internal-Service-Token": token} if token else {}


def _stream_case(runtime_url: str, case: dict, timeout: float) -> dict:
    """发起一次流式请求，返回采集到的事件、时延指标与终态聚合。"""
    import httpx

    payload = _case_payload(case)

    metrics: dict[str, Any] = {"ttfb_ms": None, "ttft_ms": None, "ttfr_ms": None, "done_ms": None}
    events: list[dict] = []
    done_data: dict = {}
    error: str | None = None
    start = time.perf_counter()
    url = runtime_url.rstrip("/") + "/v1/agent/runs/stream"
    try:
        # trust_env=False：绕过 HTTP_PROXY 等代理环境变量，localhost 请求必须直连
        with httpx.Client(timeout=timeout, trust_env=False) as client:
            with client.stream("POST", url, json=payload, headers=_runtime_headers()) as resp:
                resp.raise_for_status()
                event_name = "message"
                for raw in resp.iter_lines():
                    if raw is None:
                        continue
                    line = raw.decode() if isinstance(raw, bytes) else raw
                    if line.startswith("event:"):
                        event_name = line[len("event:") :].strip()
                        continue
                    if not line.startswith("data:"):
                        continue
                    data_str = line[len("data:") :].strip()
                    if data_str == "[DONE]":
                        break
                    elapsed = _now_ms(start)
                    if metrics["ttfb_ms"] is None:
                        metrics["ttfb_ms"] = elapsed
                    try:
                        data = json.loads(data_str)
                    except json.JSONDecodeError:
                        data = {"raw": data_str}
                    events.append({"event": event_name, "elapsed_ms": elapsed, "data": data})
                    if event_name == "token" and metrics["ttft_ms"] is None:
                        metrics["ttft_ms"] = elapsed
                    elif event_name == "reasoning" and metrics["ttfr_ms"] is None:
                        metrics["ttfr_ms"] = elapsed
                    elif event_name == "done":
                        metrics["done_ms"] = elapsed
                        done_data = data
                    elif event_name == "error":
                        error = str(data.get("message") or data)
    except Exception as exc:  # noqa: BLE001 网络/上游异常归一为可记录错误，不中断整批
        error = f"{type(exc).__name__}: {exc}"
    if metrics["done_ms"] is None:
        metrics["done_ms"] = _now_ms(start)
    metrics["server_latency_ms"] = done_data.get("latency_ms")
    return {"metrics": metrics, "events": events, "done": done_data, "error": error}


def _directive_from_trace(trace_events: list[dict]) -> dict:
    # 同名事件可能出现多次（如仅有事件名的占位），优先取带 payload 的那一条。
    candidates = [ev for ev in trace_events if ev.get("event") == "task_understanding"]
    chosen = next((ev for ev in candidates if ev.get("payload")), candidates[0] if candidates else None)
    if chosen is None:
        return {}
    p = chosen.get("payload") or {}
    return {
        "domain": p.get("domain"),
        "intent": p.get("intent"),
        "router": p.get("router"),
        "confidence": p.get("confidence"),
        "next_action": p.get("next_action"),
        "needs_clarification": p.get("needs_clarification"),
    }


def _build_run(sample: dict) -> dict:
    done = sample.get("done") or {}
    trace_events = done.get("trace_events") or []
    directive = _directive_from_trace(trace_events)
    job_cards = done.get("job_cards") or done.get("jobCards") or []
    if not isinstance(job_cards, list):
        job_cards = []
    tool_events = done.get("tool_events") or done.get("toolEvents") or []
    if not isinstance(tool_events, list):
        tool_events = []
    else:
        tool_events = list(tool_events)
    for event in sample.get("events") or []:
        name = event.get("event")
        data = event.get("data")
        if name == "job_cards" and isinstance(data, list):
            job_cards = data
        elif name == "tool_status" and isinstance(data, dict):
            tool_events.append(data)
    return {
        "status": done.get("status"),
        "stop_reason": done.get("stop_reason"),
        "answer": done.get("answer") or "",
        "reasoning": done.get("reasoning") or "",
        "directive": directive,
        "trace_events": trace_events,
        "job_cards": job_cards,
        "tool_events": tool_events,
        "metrics": sample.get("metrics") or {},
    }


def _effect_checks(case: dict, run: dict, sample: dict) -> list[dict]:
    """效果维度：把用例 expected 中的语义断言逐条核对，只评估声明了的键。"""
    exp = case.get("expected") or {}
    directive = run.get("directive") or {}
    answer = run.get("answer") or ""
    status = str(run.get("status") or "").lower()
    stop_reason = str(run.get("stop_reason") or "").lower()
    checks: list[dict] = []

    def add(code: str, ok: bool, detail: Any = None) -> None:
        checks.append({"code": code, "passed": bool(ok), "detail": detail})

    if "intent" in exp:
        add(
            "intent",
            directive.get("intent") == exp["intent"],
            {"actual": directive.get("intent"), "expected": exp["intent"]},
        )
    if "domain" in exp:
        add(
            "domain",
            directive.get("domain") == exp["domain"],
            {"actual": directive.get("domain"), "expected": exp["domain"]},
        )
    if "next_action" in exp:
        add(
            "next_action",
            directive.get("next_action") == exp["next_action"],
            {"actual": directive.get("next_action"), "expected": exp["next_action"]},
        )
    if "forbidden_intent" in exp:
        add(
            "forbidden_intent",
            directive.get("intent") != exp["forbidden_intent"],
            {"actual": directive.get("intent"), "forbidden": exp["forbidden_intent"]},
        )
    if "router_in" in exp:
        add(
            "router_in",
            directive.get("router") in exp["router_in"],
            {"actual": directive.get("router"), "allowed": exp["router_in"]},
        )
    if "expect_status" in exp:
        add(
            "expect_status",
            status == str(exp["expect_status"]).lower(),
            {"actual": status, "expected": exp["expect_status"]},
        )
    if "stop_reason" in exp:
        add(
            "stop_reason",
            stop_reason == str(exp["stop_reason"]).lower(),
            {"actual": stop_reason, "expected": exp["stop_reason"]},
        )
    if exp.get("needs_clarification"):
        add("needs_clarification", bool(directive.get("needs_clarification")) or stop_reason == "need_clarification")
    if "answer_min_chars" in exp:
        add(
            "answer_min_chars",
            len(answer.strip()) >= int(exp["answer_min_chars"]),
            {"chars": len(answer.strip()), "min": exp["answer_min_chars"]},
        )
    if exp.get("expect_rejection"):
        rejected = stop_reason in {"safety_blocked", "rejected"} or any(
            w in answer for w in ["不能", "无法", "不支持", "拒绝", "不会"]
        )
        add("expect_rejection", rejected, {"stop_reason": stop_reason})
    if exp.get("disallow_boss"):
        blob = (answer + json.dumps(sample.get("events"), ensure_ascii=False)).lower()
        add("disallow_boss", "boss" not in blob and "直聘" not in blob)
    return checks


def _score(checks: Iterable[dict]) -> float:
    items = list(checks)
    if not items:
        return 1.0
    return round(sum(1 for c in items if c["passed"]) / len(items), 4)


def _evaluate_sample(case: dict, sample: dict) -> dict:
    run = _build_run(sample)
    effect = _effect_checks(case, run, sample)
    process = grade_trace(run.get("trace_events") or [])
    speed = grade_latency(run.get("metrics") or {}, case.get("latency_budget") or {})
    quality = grade_run(run, _grader_expected(case))
    effect_score = _score(effect)
    passed = (
        sample.get("error") is None
        and effect_score >= 1.0
        and process.get("passed", False)
        and speed.get("passed", True)
        and quality.get("passed", False)
    )
    return {
        "passed": passed,
        "error": sample.get("error"),
        "metrics": run.get("metrics"),
        "effect": {"score": effect_score, "checks": effect},
        "process": {
            "score": process.get("score"),
            "passed": process.get("passed"),
            "missing_events": process.get("missing_events"),
        },
        "speed": {"score": speed.get("score"), "passed": speed.get("passed"), "issues": speed.get("issues")},
        "quality": {"score": quality.get("score"), "passed": quality.get("passed"), "issues": quality.get("issues")},
        "answer_preview": (run.get("answer") or "")[:160],
    }


def _grader_expected(case: dict) -> dict:
    exp = case.get("expected") or {}
    out: dict[str, Any] = {}
    for key in (
        "intent",
        "domain",
        "minimum_recommended_match_score",
        "minimum_qualified_jobs",
        "require_complete_recommendation_scoring",
    ):
        if key in exp:
            out[key] = exp[key]
    if exp.get("requires_evidence"):
        out["requires_evidence"] = True
    if exp.get("disallow_boss"):
        out["disallow_boss"] = True
    if exp.get("expect_llm_usage"):
        out["expect_llm_usage"] = True
    if case.get("latency_budget"):
        out["latency_budget"] = case["latency_budget"]
    out["min_score"] = float(exp.get("min_score", 0.7))
    return out


def _has_critical(quality: dict) -> bool:
    return any(issue.get("severity") == "critical" for issue in quality.get("issues") or [])


def _aggregate(case: dict, samples: list[dict]) -> dict:
    evals = [s["eval"] for s in samples]
    runs = len(evals)
    passes = sum(1 for e in evals if e["passed"])

    def latencies(key: str) -> list[int]:
        return [e["metrics"].get(key) for e in evals if e["metrics"].get(key) is not None]

    lat_summary = {}
    for key in ("ttfb_ms", "ttft_ms", "done_ms"):
        vals = latencies(key)
        if vals:
            lat_summary[key] = {
                "p50": int(statistics.median(vals)),
                "max": max(vals),
                "min": min(vals),
            }
    return {
        "id": case.get("id"),
        "category": case.get("category"),
        "input": case.get("input"),
        "runs": runs,
        "pass_at_1": evals[0]["passed"] if evals else False,
        "pass_pow_k": passes == runs and runs > 0,  # pass^k：全部通过才算稳定可上线
        "pass_rate": round(passes / runs, 4) if runs else 0.0,
        "latency": lat_summary,
        "effect_score": round(statistics.mean(e["effect"]["score"] for e in evals), 4) if evals else 0.0,
        "speed_score": round(statistics.mean(e["speed"]["score"] or 0.0 for e in evals), 4) if evals else 0.0,
        "process_score": round(statistics.mean(e["process"]["score"] or 0.0 for e in evals), 4) if evals else 0.0,
        "first_sample": evals[0] if evals else {},
    }


def _sample_record(entry: dict) -> dict:
    """单次采样的完整留存记录：评估结论 + 最终答案 + 过程时间线 + trace，供回放与归因。"""
    sample = entry.get("sample") or {}
    done = sample.get("done") or {}
    timeline = [{"event": ev.get("event"), "elapsed_ms": ev.get("elapsed_ms")} for ev in sample.get("events") or []]
    return {
        "eval": entry.get("eval"),
        "answer": done.get("answer") or "",
        "reasoning": done.get("reasoning") or "",
        "stop_reason": done.get("stop_reason"),
        "run_id": done.get("run_id"),
        "trace_id": done.get("trace_id"),
        "event_timeline": timeline,
        "trace_events": done.get("trace_events") or [],
        "server_metrics": done.get("metrics"),
    }


def _write_reports(out_dir: Path, results: list[dict], raw: list[dict], meta: dict) -> tuple[Path, Path]:
    out_dir.mkdir(parents=True, exist_ok=True)
    stamp = datetime.now(timezone.utc).strftime("%Y%m%d-%H%M%S")
    jsonl_path = out_dir / f"engine-eval-{stamp}.jsonl"
    with jsonl_path.open("w", encoding="utf-8") as fh:
        fh.write(json.dumps({"meta": meta}, ensure_ascii=False) + "\n")
        for row in raw:
            fh.write(json.dumps(row, ensure_ascii=False) + "\n")
    md_path = out_dir / f"engine-eval-{stamp}.md"
    md_path.write_text(_render_markdown(results, meta), encoding="utf-8")
    return jsonl_path, md_path


def _render_markdown(results: list[dict], meta: dict) -> str:
    lines = [
        "# 智能引擎评估报告",
        "",
        f"- 时间：{meta['timestamp']}",
        f"- Runtime：{meta['runtime_url']}",
        f"- 重复次数/用例：{meta['repeats']}",
        f"- 执行用例：{len(results)}（跳过 {meta['skipped']} 个高风控 Boss 用例）",
        "",
    ]
    total = len(results)
    stable = sum(1 for r in results if r["pass_pow_k"])
    lines.append(f"- 稳定通过（pass^k）：{stable}/{total}")
    lines.append("")
    lines.append("| 用例 | 类别 | pass^k | 通过率 | ttfb p50 | ttft p50/max | done p50/max | 效果 | 速度 | 过程 |")
    lines.append("|---|---|---|---|---|---|---|---|---|---|")
    for r in results:
        lat = r["latency"]
        ttfb = lat.get("ttfb_ms", {})
        ttft = lat.get("ttft_ms", {})
        done = lat.get("done_ms", {})
        lines.append(
            "| {id} | {cat} | {pk} | {pr} | {ttfb} | {ttft} | {done} | {eff} | {spd} | {proc} |".format(
                id=r["id"],
                cat=r["category"],
                pk="✅" if r["pass_pow_k"] else "❌",
                pr=f"{r['pass_rate']:.0%}",
                ttfb=ttfb.get("p50", "-"),
                ttft=f"{ttft.get('p50', '-')}/{ttft.get('max', '-')}",
                done=f"{done.get('p50', '-')}/{done.get('max', '-')}",
                eff=f"{r['effect_score']:.2f}",
                spd=f"{r['speed_score']:.2f}",
                proc=f"{r['process_score']:.2f}",
            )
        )
    lines.append("")
    fails = [r for r in results if not r["pass_pow_k"]]
    if fails:
        lines.append("## 未稳定通过用例明细")
        lines.append("")
        for r in fails:
            fs = r["first_sample"]
            lines.append(f"### {r['id']}（{r['category']}）")
            if fs.get("error"):
                lines.append(f"- 错误：{fs['error']}")
            bad_effect = [c for c in fs.get("effect", {}).get("checks", []) if not c["passed"]]
            if bad_effect:
                lines.append(f"- 效果未过：{', '.join(c['code'] for c in bad_effect)}")
            if not fs.get("speed", {}).get("passed", True):
                lines.append(f"- 速度超预算：{[i.get('code') for i in fs.get('speed', {}).get('issues', [])]}")
            if not fs.get("process", {}).get("passed", True):
                lines.append(f"- 过程缺失事件：{fs.get('process', {}).get('missing_events')}")
            crit = [i for i in fs.get("quality", {}).get("issues", []) if i.get("severity") == "critical"]
            if crit:
                lines.append(f"- 质量严重问题：{[i.get('code') for i in crit]}")
            lines.append("")
    return "\n".join(lines)


def _self_check() -> int:
    """不连真实引擎，用构造样例验证评分链路。"""
    case = {
        "id": "selfcheck_open_qa",
        "category": "direct_synthesis_fast_path",
        "input": "Java volatile 的原理是什么",
        "expected": {"domain": "open_domain", "intent": "technical_qa", "answer_min_chars": 10},
        "latency_budget": {"ttft_ms_target": 2000, "ttft_ms_max": 4500, "done_ms_target": 6000, "done_ms_max": 11000},
    }
    sample = {
        "metrics": {"ttfb_ms": 300, "ttft_ms": 1500, "done_ms": 5000, "server_latency_ms": 5000},
        "events": [{"event": "processing"}, {"event": "token"}, {"event": "done"}],
        "done": {
            "status": "success",
            "stop_reason": "task_complete",
            "answer": "volatile 保证可见性和禁止指令重排序。",
            "trace_events": [
                {"event": e}
                for e in [
                    "run_start",
                    "understand_goal",
                    "task_understanding",
                    "capability_route",
                    "finalize",
                    "run_end",
                ]
            ]
            + [
                {
                    "event": "task_understanding",
                    "payload": {
                        "domain": "open_domain",
                        "intent": "technical_qa",
                        "router": "llm",
                        "confidence": 0.9,
                        "next_action": "run_runtime_planner",
                    },
                }
            ],
        },
        "error": None,
    }
    result = _evaluate_sample(case, sample)
    print(json.dumps(result, ensure_ascii=False, indent=2))
    ok = result["passed"] and result["speed"]["passed"] and result["process"]["passed"] and result["quality"]["passed"]
    print("[self-check]", "PASSED" if ok else "FAILED")
    return 0 if ok else 1


def main() -> int:
    parser = argparse.ArgumentParser(description="智能引擎效果/速度/过程联合评估")
    parser.add_argument("--runtime-url", default="http://127.0.0.1:8010")
    parser.add_argument("--cases", default=str(ROOT / "cases" / "engine-eval-v1.yaml"))
    parser.add_argument("--repeats", type=int, default=1, help="每个用例重复次数，用于时延分位与 pass^k")
    parser.add_argument("--timeout", type=float, default=60.0)
    parser.add_argument("--allow-boss", action="store_true", help="放开 requires_live_boss 用例（高风控，谨慎使用）")
    parser.add_argument("--only", help="只跑指定 case id，逗号分隔")
    parser.add_argument("--out", default=str(ROOT / "reports"))
    parser.add_argument("--self-check", action="store_true")
    args = parser.parse_args()

    if args.self_check:
        return _self_check()

    spec = _load_cases(Path(args.cases))
    only = set(filter(None, (args.only or "").split(","))) if args.only else None
    defaults = spec.get("latency_budget_defaults") or {}

    cases = []
    skipped = 0
    for case in spec.get("cases", []):
        if only and case.get("id") not in only:
            continue
        if case.get("requires_live_boss") and not args.allow_boss:
            skipped += 1
            continue
        budget = dict(defaults)
        budget.update(case.get("latency_budget") or {})
        profile = (
            case.get("runtime_profile")
            or case.get("profile")
            or spec.get("runtime_profile")
            or spec.get("profile")
            or "job-buddy"
        )
        case = dict(case, latency_budget=budget, runtime_profile=profile)
        cases.append(case)

    results = []
    raw_records = []
    for case in cases:
        samples = []
        for _ in range(max(1, args.repeats)):
            sample = _stream_case(args.runtime_url, case, args.timeout)
            ev = _evaluate_sample(case, sample)
            samples.append({"sample": sample, "eval": ev})
        agg = _aggregate(case, samples)
        results.append(agg)
        raw_records.append({"id": case.get("id"), "aggregate": agg, "samples": [_sample_record(s) for s in samples]})
        status = "OK" if agg["pass_pow_k"] else "FAIL"
        ttft = agg["latency"].get("ttft_ms", {})
        print(
            f"[{status}] {case['id']:<32} pass_rate={agg['pass_rate']:.0%} ttft_p50={ttft.get('p50', '-')}ms speed={agg['speed_score']:.2f}"
        )

    meta = {
        "timestamp": datetime.now(timezone.utc).isoformat(),
        "runtime_url": args.runtime_url,
        "repeats": args.repeats,
        "skipped": skipped,
        "cases_file": args.cases,
    }
    out_dir = Path(args.out)
    jsonl_path, md_path = _write_reports(out_dir, results, raw_records, meta)
    stable = sum(1 for r in results if r["pass_pow_k"])
    print(f"\n稳定通过 pass^k: {stable}/{len(results)}  跳过(高风控): {skipped}")
    print(f"报告: {md_path}")
    print(f"明细: {jsonl_path}")
    return 0 if stable == len(results) else 1


if __name__ == "__main__":
    raise SystemExit(main())
