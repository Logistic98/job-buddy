"""统一 YAML 套件契约与执行适配；按 kind 隔离真实运行、校准及业务规格。"""

from typing import Any, Literal

from pydantic import BaseModel, ConfigDict, Field, model_validator


class Contract(BaseModel):
    model_config = ConfigDict(extra="forbid", strict=True)


class WebSearchQuality(Contract):
    allow_third_party_fallback: bool | None = None
    allowed_latest_verification_methods: list[str] | None = None
    allowed_official_verifications: list[str] | None = None
    allowed_published_date_sources: list[str] | None = None
    allowed_selection_bases: list[str] | None = None
    expected_content_scope: str | None = None
    expected_current_date: str | None = None
    forbid_site_operator: bool | None = None
    forbidden_query_fragments: list[str] | None = None
    latest_result_path_prefix: str | None = None
    latest_result_path_prefixes: list[str] | None = None
    max_queries: int | None = Field(default=None, ge=0)
    min_official_sources: int | None = Field(default=None, ge=0)
    preferred_source_domains_any: list[str] | None = None
    query_max_chars: int | None = Field(default=None, ge=0)
    require_expansion: bool | None = None
    require_latest_verified: bool | None = None
    require_official_tier: bool | None = None
    require_preferred_query_scope: bool | None = None
    require_preferred_source_flag: bool | None = None
    require_selected_published_date_in_answer: bool | None = None
    require_selected_title_in_answer: bool | None = None
    require_selected_url_in_answer: bool | None = None
    require_unique_urls: bool | None = None
    trusted_hosts_any: list[str] | None = None


class Expected(Contract):
    domain: str | None = None
    intent: str | None = None
    next_action: str | None = None
    forbidden_intent: str | None = None
    runtime_capability: str | None = None
    router_in: list[str] | None = None
    expect_status: str | None = None
    stop_reason: str | None = None
    needs_clarification: bool | None = None
    answer_min_chars: int = Field(default=0, ge=0)
    answer_contains_all: list[str] | None = None
    answer_not_contains: list[str] | None = None
    answer_equals: str | None = None
    events: list[Literal["processing", "token", "reasoning", "done", "error", "tool_status", "job_cards"]] | None = None
    trace_events: list[str] | None = None
    required_tools: list[str] | None = None
    forbidden_tools: list[str] | None = None
    forbidden_actions: list[str] | None = None
    slots: dict[str, Any] | None = None
    tool_output_contains: dict[str, list[str]] | None = None
    expect_no_tool_results: bool = False
    expect_no_llm_usage: bool = False
    expect_llm_usage: bool = False
    checkpoint_resume: bool = False
    checkpoint_replan: bool = False
    expect_injection_flag: bool = False
    expect_rejection: bool = False
    disallow_boss: bool = False
    requires_evidence: bool = False
    max_tool_executions: dict[str, int] | None = None
    max_trace_event_counts: dict[str, int] | None = None
    web_search_quality: WebSearchQuality | None = None
    minimum_recommended_match_score: int | None = None
    minimum_qualified_jobs: int | None = None
    require_complete_recommendation_scoring: bool = False
    min_score: float = Field(default=0.7, ge=0, le=1)

    @model_validator(mode="after")
    def validate_nested_assertions(self):
        if self.checkpoint_replan and not self.checkpoint_resume:
            raise ValueError("checkpoint_replan requires checkpoint_resume")
        for limits in (self.max_tool_executions, self.max_trace_event_counts):
            if limits is not None and any(value < 0 for value in limits.values()):
                raise ValueError("execution/event limits must be nonnegative")
        return self


class LatencyBudget(Contract):
    ttfb_ms_target: float | None = Field(default=None, ge=0, allow_inf_nan=False)
    ttfb_ms_max: float | None = Field(default=None, gt=0, allow_inf_nan=False)
    ttft_ms_target: float | None = Field(default=None, ge=0, allow_inf_nan=False)
    ttft_ms_max: float | None = Field(default=None, gt=0, allow_inf_nan=False)
    done_ms_target: float | None = Field(default=None, ge=0, allow_inf_nan=False)
    done_ms_max: float | None = Field(default=None, gt=0, allow_inf_nan=False)
    min_score: float = Field(default=0.6, ge=0, le=1)

    @model_validator(mode="after")
    def ordered(self):
        for metric in ("ttfb", "ttft", "done"):
            target = getattr(self, f"{metric}_ms_target")
            maximum = getattr(self, f"{metric}_ms_max")
            if target is not None and maximum is not None and target >= maximum:
                raise ValueError(f"{metric}: target must be below max")
        return self


class EvalCase(Contract):
    id: str = Field(min_length=1, pattern=r"^[a-z0-9_]+$")
    category: str = Field(min_length=1)
    input: str = Field(min_length=1)
    description: str = ""
    suite: Literal["regression", "capability"] = "regression"
    runtime_profile: str = "job-buddy"
    messages: list[dict[str, str]] | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    session_id: str | None = None
    preconditions: list[str] = Field(default_factory=list)
    requires_live_boss: bool = False
    expected: Expected
    latency_budget: LatencyBudget = Field(default_factory=LatencyBudget)
    rubric: str | None = None

    @model_validator(mode="after")
    def assertions_present(self):
        if not self.expected.model_fields_set:
            raise ValueError("expected must declare assertions")
        return self


def validate_cases(spec: dict) -> dict:
    if not isinstance(spec, dict) or not isinstance(spec.get("cases"), list) or not spec["cases"]:
        raise ValueError("suite must contain a nonempty cases list")
    seen = set()
    for raw in spec["cases"]:
        case = EvalCase.model_validate(raw)
        if case.id in seen:
            raise ValueError(f"duplicate case id: {case.id}")
        seen.add(case.id)
        LatencyBudget.model_validate({**spec.get("latency_budget_defaults", {}), **raw.get("latency_budget", {})})
    return spec


class CaseRecord(Contract):
    id: str = Field(min_length=1, pattern=r"^[a-z0-9_]+$")
    category: str = Field(min_length=1)
    description: str = Field(min_length=1)
    input: dict[str, Any]
    expected: dict[str, Any]
    options: dict[str, Any] = Field(default_factory=dict)
    rubric: str | None = None


class CaseSuite(Contract):
    schema_version: Literal[1]
    id: str = Field(min_length=1, pattern=r"^[a-z0-9_-]+$")
    version: int = Field(ge=1)
    name: str = Field(min_length=1)
    description: str = Field(min_length=1)
    kind: Literal["runtime", "calibration", "business", "memory"]
    defaults: dict[str, Any]
    fixtures: dict[str, Any]
    contracts: dict[str, Any]
    cases: list[CaseRecord] = Field(min_length=1)


class RuntimeInput(Contract):
    message: str = Field(min_length=1)
    messages: list[dict[str, str]] | None = None
    metadata: dict[str, Any] = Field(default_factory=dict)
    session_id: str | None = None


class CalibrationInput(Contract):
    run_overrides: dict[str, Any] = Field(default_factory=dict)
    metrics: dict[str, Any] = Field(default_factory=dict)
    events: list[dict[str, Any]] = Field(default_factory=list)
    error: str | None = None
    judge: dict[str, Any] | None = None


class CalibrationExpected(Contract):
    passed: bool
    checks: Expected
    issue_codes: list[str] = Field(default_factory=list)


class BusinessExpected(Contract):
    domain: str | None = None
    intent: str | None = None
    runtime_capability: str | None = None
    next_action: str | None = None
    forbidden_intent: str | None = None
    events: list[str] | None = None
    trace_events: list[str] | None = None
    required_tools: list[str] | None = None
    forbidden_actions: list[str] | None = None
    slots: dict[str, Any] | None = None
    minimum_recommended_match_score: int | None = None
    require_complete_recommendation_scoring: bool = False
    outcomes: list[str] = Field(min_length=1)


class MemoryInput(Contract):
    operation: Literal["search", "update"]
    query: str = Field(min_length=1)
    fact_id: str | None = None
    content: str | None = None


class MemoryExpected(Contract):
    ids: list[str] | None = Field(default=None, min_length=1)
    content: str | None = None
    previous_content_absent: bool | None = None


def runtime_case(case: dict) -> dict:
    """把磁盘统一格式转换为 runner 内部执行参数，不改变 HTTP 协议。"""
    request = RuntimeInput.model_validate(case["input"]).model_dump(exclude_unset=True)
    message = request.pop("message")
    allowed_options = {"suite", "runtime_profile", "preconditions", "requires_live_boss", "latency_budget"}
    if set(case.get("options", {})) - allowed_options:
        raise ValueError(f"{case['id']}: unsupported runtime options")
    return {
        "id": case["id"],
        "category": case["category"],
        "description": case["description"],
        "input": message,
        **request,
        "expected": case["expected"],
        **case.get("options", {}),
        **({"rubric": case["rubric"]} if case.get("rubric") else {}),
    }


def validate_suite(spec: dict) -> dict:
    if not isinstance(spec, dict) or type(spec.get("schema_version")) is not int:
        raise ValueError("schema_version must be an integer")
    suite = CaseSuite.model_validate(spec)
    ids = [case.id for case in suite.cases]
    if len(ids) != len(set(ids)):
        raise ValueError(f"{suite.id}: duplicate case id")
    if any(not case.input or not case.expected for case in suite.cases):
        raise ValueError("input and expected must not be empty")
    allowed_defaults = {
        "runtime": {"runtime_profile", "latency_budget"},
        "calibration": {"latency_budget"},
        "business": set(),
        "memory": {"thresholds"},
    }
    allowed_fixtures = {"runtime": set(), "calibration": {"run"}, "business": set(), "memory": {"facts"}}
    if set(suite.defaults) - allowed_defaults[suite.kind] or set(suite.fixtures) - allowed_fixtures[suite.kind]:
        raise ValueError(f"{suite.id}: unsupported defaults or fixtures")
    if suite.kind in {"runtime", "calibration"}:
        LatencyBudget.model_validate(suite.defaults.get("latency_budget", {}))
    if "runtime_profile" in suite.defaults and (
        not isinstance(suite.defaults["runtime_profile"], str) or not suite.defaults["runtime_profile"].strip()
    ):
        raise ValueError("runtime_profile must be a nonempty string")
    if suite.kind == "runtime":
        validate_cases(
            {
                "cases": [runtime_case(case) for case in spec["cases"]],
                "latency_budget_defaults": suite.defaults.get("latency_budget", {}),
            }
        )
    elif suite.kind == "calibration":
        if not isinstance(suite.fixtures.get("run"), dict) or not suite.fixtures["run"]:
            raise ValueError("calibration requires fixtures.run")
        for case in suite.cases:
            CalibrationInput.model_validate(case.input)
            expected = CalibrationExpected.model_validate(case.expected)
            if not expected.checks.model_fields_set:
                raise ValueError(f"{case.id}: empty calibration checks")
            if set(case.options) - {"latency_budget"}:
                raise ValueError(f"{case.id}: unsupported calibration options")
            LatencyBudget.model_validate(
                {**suite.defaults.get("latency_budget", {}), **case.options.get("latency_budget", {})}
            )
    elif suite.kind == "business":
        for case in suite.cases:
            RuntimeInput.model_validate(case.input)
            BusinessExpected.model_validate(case.expected)
            if set(case.options) - {"preconditions"}:
                raise ValueError(f"{case.id}: unsupported business options")
            if not isinstance(case.options.get("preconditions", []), list):
                raise ValueError(f"{case.id}: preconditions must be a list")
    else:
        _validate_memory_suite(suite)
    return spec


def _validate_memory_suite(suite: CaseSuite) -> None:
    import math

    facts = suite.fixtures.get("facts")
    if not isinstance(facts, list) or not facts:
        raise ValueError("memory requires fixtures.facts")
    if any(
        not isinstance(fact, dict)
        or set(fact) != {"id", "content"}
        or not all(isinstance(value, str) and value for value in fact.values())
        for fact in facts
    ):
        raise ValueError("memory facts require nonempty id/content strings")
    fact_ids = {fact["id"] for fact in facts}
    if len(fact_ids) != len(facts):
        raise ValueError("duplicate fact id")
    thresholds = suite.defaults.get("thresholds", {})
    if not isinstance(thresholds, dict):
        raise ValueError("memory thresholds must be a mapping")
    required = {
        "recall_at_5",
        "mrr_at_5",
        "update_accuracy",
        "isolation_accuracy",
        "lifecycle_accuracy",
        "search_p95_ms",
        "update_p95_ms",
    }
    if set(thresholds) != required:
        raise ValueError("memory thresholds must declare all seven metrics")
    for name, value in thresholds.items():
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value):
            raise ValueError(f"invalid threshold: {name}")
        if (name.endswith("_ms") and value <= 0) or (not name.endswith("_ms") and not 0 <= value <= 1):
            raise ValueError(f"invalid threshold: {name}")
    searches = 0
    for case in suite.cases:
        request = MemoryInput.model_validate(case.input)
        expected = MemoryExpected.model_validate(case.expected)
        if case.options:
            raise ValueError(f"{case.id}: memory options not supported")
        if request.operation == "search":
            searches += 1
            if not expected.ids or not set(expected.ids) <= fact_ids:
                raise ValueError(f"{case.id}: unknown or empty expected fact IDs")
            if request.fact_id is not None or request.content is not None or set(case.expected) != {"ids"}:
                raise ValueError(f"{case.id}: search fields do not match operation")
        elif (
            request.fact_id not in fact_ids
            or not request.content
            or expected.content != request.content
            or expected.previous_content_absent is not True
            or set(case.expected) != {"content", "previous_content_absent"}
        ):
            raise ValueError(f"{case.id}: invalid update expectation")
    if searches < 20:
        raise ValueError("memory suite requires at least 20 retrieval cases")


def load_suite(path, kind: str | None = None) -> dict:
    """严格读取统一 YAML，拒绝重复键覆盖既有标签。"""
    from pathlib import Path

    import yaml

    class UniqueKeyLoader(yaml.SafeLoader):
        pass

    def mapping(loader, node):
        result = {}
        for key_node, value_node in node.value:
            key = loader.construct_object(key_node)
            if not isinstance(key, str):
                raise ValueError("YAML mapping keys must be strings")
            if key in result:
                raise ValueError(f"duplicate YAML key: {key}")
            result[key] = loader.construct_object(value_node)
        return result

    UniqueKeyLoader.add_constructor(yaml.resolver.BaseResolver.DEFAULT_MAPPING_TAG, mapping)
    path = Path(path)
    if path.suffix != ".yaml":
        raise ValueError("case suites must use .yaml")
    try:
        document = yaml.load(path.read_text(encoding="utf-8"), Loader=UniqueKeyLoader)
    except yaml.YAMLError as exc:
        raise ValueError(f"{path.name}: invalid YAML: {exc}") from exc
    spec = validate_suite(document)
    if kind is not None and spec["kind"] != kind:
        raise ValueError(f"{path.name}: expected {kind}, got {spec['kind']}")
    return spec


def memory_dataset(spec: dict) -> dict:
    """为现有 Memory 基线保留执行数据形状；磁盘只保留统一套件。"""
    validate_suite(spec)
    if spec["kind"] != "memory":
        raise ValueError("expected memory suite")
    return {
        "version": spec["version"],
        "thresholds": spec["defaults"]["thresholds"],
        "facts": spec["fixtures"]["facts"],
        "queries": [
            {"id": case["id"], "query": case["input"]["query"], "expected_ids": case["expected"]["ids"]}
            for case in spec["cases"]
            if case["input"]["operation"] == "search"
        ],
        "updates": [
            {
                "id": case["input"]["fact_id"],
                "case_id": case["id"],
                "content": case["expected"]["content"],
                "query": case["input"]["query"],
            }
            for case in spec["cases"]
            if case["input"]["operation"] == "update"
        ],
    }
