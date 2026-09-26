"""Deterministic scoring of real memory outcomes; transport success is insufficient."""

import math


def percentile95(values):
    if not values or any(not math.isfinite(value) or value < 0 for value in values):
        raise ValueError("latency samples must be finite and nonempty")
    return sorted(values)[math.ceil(len(values) * 0.95) - 1]


def grade_memory(report, thresholds):
    queries = report["queries"]
    if len(queries) < 20:
        raise ValueError("at least 20 retrieval samples are required")
    recalls, reciprocals = [], []
    for case in queries:
        expected = set(case["expected_ids"])
        if not expected:
            raise ValueError("expected facts must not be empty")
        found = case["found_ids"][:5]
        recalls.append(len(expected.intersection(found)) / len(expected))
        reciprocals.append(next((1 / rank for rank, key in enumerate(found, 1) if key in expected), 0))
    metrics = {
        "recall_at_5": sum(recalls) / len(recalls),
        "mrr_at_5": sum(reciprocals) / len(reciprocals),
        "search_p95_ms": percentile95([case["elapsed_ms"] for case in queries]),
        "update_p95_ms": percentile95([case["elapsed_ms"] for case in report["updates"]]),
    }
    for field, metric in [
        ("updates", "update_accuracy"),
        ("isolation", "isolation_accuracy"),
        ("lifecycle", "lifecycle_accuracy"),
    ]:
        cases = report[field]
        if not cases:
            raise ValueError(f"missing {field} samples")
        metrics[metric] = sum(case["passed"] is True for case in cases) / len(cases)
    checks = {
        name: (metrics[name] <= value if name.endswith("_ms") else metrics[name] >= value)
        for name, value in thresholds.items()
    }
    return {"metrics": metrics, "checks": checks, "passed": all(checks.values()) and not report.get("errors")}
