"""逐条执行统一校准集；预先标注标签不由待测评分器生成。"""

from copy import deepcopy
from pathlib import Path

import pytest

from app.cases import load_suite
from scripts.run_engine_eval import _evaluate_sample

SPEC = load_suite(Path(__file__).parents[2] / "cases" / "grader-calibration.yaml", kind="calibration")


@pytest.mark.parametrize("case", SPEC["cases"], ids=lambda case: case["id"])
def test_labelled_grader_calibration(case):
    request = case["input"]
    sample = {**deepcopy(request), "done": {**deepcopy(SPEC["fixtures"]["run"]), **deepcopy(request["run_overrides"])}}
    expected = case["expected"]
    result = _evaluate_sample({"expected": expected["checks"], **case.get("options", {})}, sample)
    assert result["passed"] is expected["passed"], (case["description"], result)
    codes = {item["code"] for item in result["effect"]["checks"] if not item["passed"]}
    codes.update(item["code"] for item in result["quality"]["issues"])
    codes.update(item["code"] for item in result["speed"]["issues"])
    assert set(expected.get("issue_codes", [])) <= codes, result


def test_calibration_has_both_labels_and_unique_ids():
    assert SPEC["kind"] == "calibration"
    assert {case["expected"]["passed"] for case in SPEC["cases"]} == {True, False}
    assert len({case["id"] for case in SPEC["cases"]}) == len(SPEC["cases"])
