import pytest

from app.grader import grade_memory


def test_memory_thresholds_fail_on_wrong_results_and_missing_samples():
    report = {
        "queries": [{"expected_ids": ["a"], "found_ids": ["a"], "elapsed_ms": 10} for _ in range(20)],
        "updates": [{"passed": True, "elapsed_ms": 10}],
        "isolation": [{"passed": True}],
        "lifecycle": [{"passed": True}],
    }
    thresholds = {"recall_at_5": 0.9, "mrr_at_5": 0.85, "isolation_accuracy": 1, "search_p95_ms": 3000}
    assert grade_memory(report, thresholds)["passed"]
    report["isolation"][0]["passed"] = False
    assert not grade_memory(report, thresholds)["passed"]
    report["isolation"][0]["passed"] = True
    for case in report["queries"][:3]:
        case["found_ids"] = ["wrong"]
    assert not grade_memory(report, thresholds)["passed"]
    report["queries"].pop()
    with pytest.raises(ValueError):
        grade_memory(report, thresholds)
