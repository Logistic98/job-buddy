"""统一磁盘格式、类型分派和所有消费方的加载契约。"""

from copy import deepcopy
from pathlib import Path

import pytest

from app.cases import load_suite, memory_dataset, runtime_case, validate_suite
from scripts.run_engine_eval import _case_payload, _load_cases
from scripts.validate_cases import validate_directory

DIRECTORY = Path(__file__).parents[2] / "cases"


def test_entire_catalog_has_uniform_envelope_and_case_fields():
    suites = validate_directory(DIRECTORY)
    assert {suite["kind"] for suite in suites} == {"runtime", "calibration", "business", "memory"}
    for suite in suites:
        for case in suite["cases"]:
            assert {"id", "category", "description", "input", "expected"} <= case.keys()
            assert isinstance(case["input"], dict) and isinstance(case["expected"], dict)


@pytest.mark.parametrize("filename", [path.name for path in sorted(DIRECTORY.glob("*.yaml"))])
def test_kind_dispatch_rejects_other_evaluator(filename):
    suite = load_suite(DIRECTORY / filename)
    if suite["kind"] != "runtime":
        with pytest.raises(ValueError, match="expected runtime"):
            _load_cases(DIRECTORY / filename)
    else:
        spec = _load_cases(DIRECTORY / filename)
        for disk_case, normalized in zip(suite["cases"], spec["cases"], strict=True):
            payload = _case_payload(normalized)
            assert normalized["expected"] == disk_case["expected"]
            request = disk_case["input"]
            assert payload["messages"] == request.get("messages", [{"role": "user", "content": request["message"]}])


@pytest.mark.parametrize(
    "mutation",
    [
        lambda spec: spec.update(schema_version=2),
        lambda spec: spec.update(schema_version=True),
        lambda spec: spec.update(unknown="ignored"),
        lambda spec: spec["cases"].append(deepcopy(spec["cases"][0])),
        lambda spec: spec["cases"][0].update(description=""),
        lambda spec: spec["cases"][0].update(input="old flat message"),
        lambda spec: spec["cases"][0]["input"].update(mesage="typo"),
        lambda spec: spec["cases"][0]["options"].update(runtime_url="http://invalid"),
        lambda spec: spec["defaults"].update(latency_budgets={}),
    ],
)
def test_invalid_catalog_fields_fail_closed(mutation):
    spec = load_suite(DIRECTORY / "runtime-engine.yaml")
    mutation(spec)
    with pytest.raises(ValueError):
        validate_suite(spec)


def test_calibration_unknown_assertion_and_missing_fixture_rejected():
    spec = load_suite(DIRECTORY / "grader-calibration.yaml")
    spec["cases"][0]["expected"]["checks"]["wrong_assertion"] = True
    with pytest.raises(ValueError):
        validate_suite(spec)
    spec = load_suite(DIRECTORY / "grader-calibration.yaml")
    spec["fixtures"] = {}
    with pytest.raises(ValueError):
        validate_suite(spec)


@pytest.mark.parametrize("text", ["kind: runtime\nkind: memory\n", "cases: [unclosed"])
def test_invalid_yaml_is_actionable_error(tmp_path, text):
    path = tmp_path / "bad.yaml"
    path.write_text(text)
    with pytest.raises(ValueError):
        load_suite(path)


def test_memory_adapter_preserves_thresholds_and_every_label():
    suite = load_suite(DIRECTORY / "memory-baseline.yaml")
    dataset = memory_dataset(suite)
    assert dataset["thresholds"] == suite["defaults"]["thresholds"]
    assert len(dataset["queries"]) + len(dataset["updates"]) == len(suite["cases"])
    for query in dataset["queries"]:
        case = next(row for row in suite["cases"] if row["id"] == query["id"])
        assert query["expected_ids"] == case["expected"]["ids"]
    assert dataset["facts"] == suite["fixtures"]["facts"]
    for update in dataset["updates"]:
        case = next(row for row in suite["cases"] if row["id"] == update["case_id"])
        assert update["content"] == case["expected"]["content"]


@pytest.mark.parametrize(
    "mutation",
    [
        lambda spec: spec["defaults"].update(thresholds={}),
        lambda spec: spec["defaults"]["thresholds"].update(recall_at_5=float("nan")),
        lambda spec: spec["cases"][0]["expected"].update(ids=["missing_fact"]),
        lambda spec: spec["fixtures"]["facts"].append(deepcopy(spec["fixtures"]["facts"][0])),
        lambda spec: spec["cases"][-1]["input"].update(fact_id="missing"),
        lambda spec: spec["cases"][-1]["expected"].update(content="wrong content"),
        lambda spec: spec["cases"][-1]["expected"].update(previous_content_absent=False),
        lambda spec: spec.update(cases=spec["cases"][:10]),
    ],
)
def test_invalid_memory_labels_and_insufficient_samples_rejected(mutation):
    spec = load_suite(DIRECTORY / "memory-baseline.yaml")
    mutation(spec)
    with pytest.raises(ValueError):
        validate_suite(spec)


def test_business_requires_observable_outcome():
    spec = load_suite(DIRECTORY / "business-job.yaml")
    spec["cases"][0]["expected"]["outcomes"] = []
    with pytest.raises(ValueError):
        validate_suite(spec)


def test_directory_rejects_legacy_json_and_duplicate_suite_ids(tmp_path):
    source = (DIRECTORY / "runtime-engine.yaml").read_text()
    (tmp_path / "runtime.yaml").write_text(source)
    (tmp_path / "old.json").write_text("{}")
    with pytest.raises(ValueError, match="unified"):
        validate_directory(tmp_path)
    (tmp_path / "old.json").unlink()
    (tmp_path / "duplicate.yaml").write_text(source)
    with pytest.raises(ValueError, match="duplicate suite"):
        validate_directory(tmp_path)


def test_runtime_normalization_preserves_context_and_options():
    spec = load_suite(DIRECTORY / "runtime-regression.yaml")
    case = next(row for row in spec["cases"] if row["id"] == "context_latest_instruction")
    normalized = runtime_case(case)
    assert normalized["messages"] == case["input"]["messages"]
    assert normalized["input"] == case["input"]["message"]


@pytest.mark.parametrize(
    ("filename", "mutation", "message"),
    [
        ("runtime-engine.yaml", lambda s: s["cases"][0].update(input={}), "must not be empty"),
        ("runtime-engine.yaml", lambda s: s["defaults"].update(runtime_profile=" "), "nonempty string"),
        (
            "runtime-engine.yaml",
            lambda s: s["cases"][0]["expected"].update(checkpoint_replan=True, checkpoint_resume=False),
            "requires checkpoint_resume",
        ),
        ("grader-calibration.yaml", lambda s: s["cases"][0]["expected"].update(checks={}), "empty calibration checks"),
        (
            "grader-calibration.yaml",
            lambda s: s["cases"][0].update(options={"unknown": True}),
            "unsupported calibration options",
        ),
        (
            "business-job.yaml",
            lambda s: s["cases"][0].update(options={"unknown": True}),
            "unsupported business options",
        ),
        ("business-job.yaml", lambda s: s["cases"][0].update(options={"preconditions": "invalid"}), "must be a list"),
        ("memory-baseline.yaml", lambda s: s["fixtures"].update(facts=[]), "requires fixtures.facts"),
        ("memory-baseline.yaml", lambda s: s["fixtures"]["facts"][0].update(content=""), "nonempty id/content"),
        ("memory-baseline.yaml", lambda s: s["defaults"].update(thresholds=[]), "must be a mapping"),
        ("memory-baseline.yaml", lambda s: s["defaults"]["thresholds"].update(recall_at_5=2), "invalid threshold"),
        (
            "memory-baseline.yaml",
            lambda s: s["cases"][0].update(options={"unknown": True}),
            "memory options not supported",
        ),
        (
            "memory-baseline.yaml",
            lambda s: s["cases"][0]["input"].update(content="unexpected"),
            "search fields do not match",
        ),
    ],
)
def test_suite_semantic_boundaries(filename, mutation, message):
    spec = load_suite(DIRECTORY / filename)
    mutation(spec)
    with pytest.raises(ValueError, match=message):
        validate_suite(spec)


def test_loader_rejects_non_string_keys_and_non_yaml_extension(tmp_path):
    path = tmp_path / "invalid.yaml"
    path.write_text("1: invalid")
    with pytest.raises(ValueError, match="keys must be strings"):
        load_suite(path)
    with pytest.raises(ValueError, match="must use .yaml"):
        load_suite(tmp_path / "invalid.json")
    with pytest.raises(ValueError, match="expected memory suite"):
        memory_dataset(load_suite(DIRECTORY / "runtime-engine.yaml"))
