from app.core.context.compactor import COMPACTION_MARKER_PREFIX, ContextCompactor
from app.models.schemas import AgentPlan, AgentPlanStep, ToolCall, ToolResult


def _state(observations, tool_results=None, plan=None, selected_tool_call=None):
    return {
        "objective": "筛选 Java 岗位并匹配简历",
        "observations": list(observations),
        "tool_results": tool_results or [],
        "plan": plan,
        "selected_tool_call": selected_tool_call,
    }


def _result(tool, success=True, output="ok", error=None, synthetic=False):
    return ToolResult(
        tool_call_id=f"call_{tool}",
        tool_name=tool,
        success=success,
        output=output,
        error=error,
        metadata={"synthetic": True} if synthetic else {},
    )


def test_no_compaction_below_thresholds():
    compactor = ContextCompactor(enabled=True, trigger_observations=12, trigger_chars=6000, keep_recent=4)
    state = _state([f"obs-{i}" for i in range(5)])
    assert compactor.maybe_compact(state) is None
    assert state["observations"] == [f"obs-{i}" for i in range(5)]
    assert "compaction" not in state


def test_compaction_triggered_by_observation_count_preserves_five_elements():
    compactor = ContextCompactor(enabled=True, trigger_observations=6, trigger_chars=999999, keep_recent=2)
    plan = AgentPlan(
        objective="筛选 Java 岗位并匹配简历",
        steps=[AgentPlanStep(id="s1", goal="搜索岗位"), AgentPlanStep(id="s2", goal="匹配简历")],
    )
    call = ToolCall(id="toolu_1", name="resume_match", arguments={}, reason="执行简历匹配")
    state = _state(
        [f"obs-{i}" for i in range(6)],
        tool_results=[
            _result("job_search", success=True, output="找到 10 个岗位"),
            _result("resume_parse", success=False, output=None, error="解析超时"),
            _result("task_understanding", synthetic=True),
        ],
        plan=plan,
        selected_tool_call=call,
    )
    report = compactor.maybe_compact(state)
    assert report is not None
    assert report.folded_observations == 4
    assert report.rounds == 1

    snapshot = state["compaction"]
    assert snapshot["objective"] == "筛选 Java 岗位并匹配简历"
    assert {"tool": "job_search", "summary": "找到 10 个岗位"} in snapshot["changes"]
    assert {"tool": "resume_parse", "error": "解析超时"} in snapshot["failures"]
    assert snapshot["decisions"] == ["搜索岗位", "匹配简历"]
    assert snapshot["next_step"] == "执行简历匹配"
    # synthetic 结果不进入 changes
    assert all(item["tool"] != "task_understanding" for item in snapshot["changes"])

    observations = state["observations"]
    assert observations[0].startswith(COMPACTION_MARKER_PREFIX)
    assert observations[1:] == ["obs-4", "obs-5"]


def test_compaction_triggered_by_chars():
    compactor = ContextCompactor(enabled=True, trigger_observations=999, trigger_chars=100, keep_recent=1)
    state = _state(["x" * 80, "y" * 80, "tail"])
    report = compactor.maybe_compact(state)
    assert report is not None
    assert report.folded_observations == 2
    assert report.chars_before == 164
    assert state["observations"][1:] == ["tail"]


def test_repeated_compaction_merges_append_style_with_dedupe():
    compactor = ContextCompactor(enabled=True, trigger_observations=4, trigger_chars=999999, keep_recent=1)
    state = _state(
        [f"obs-{i}" for i in range(4)],
        tool_results=[_result("job_search", output="找到 10 个岗位")],
    )
    first = compactor.maybe_compact(state)
    assert first is not None and first.rounds == 1

    # 第二轮：新增观察与新工具结果后再次触发，快照合并且不重复既有条目
    state["observations"].extend([f"obs-late-{i}" for i in range(4)])
    state["tool_results"].append(_result("job_search", output="找到 10 个岗位"))
    state["tool_results"].append(_result("job_detail", output="岗位详情已加载"))
    second = compactor.maybe_compact(state)
    assert second is not None and second.rounds == 2

    snapshot = state["compaction"]
    assert snapshot["rounds"] == 2
    assert snapshot["folded_observations"] == first.folded_observations + second.folded_observations
    assert snapshot["changes"].count({"tool": "job_search", "summary": "找到 10 个岗位"}) == 1
    assert {"tool": "job_detail", "summary": "岗位详情已加载"} in snapshot["changes"]
    # 列表头部仍只有一条压缩标记
    markers = [item for item in state["observations"] if str(item).startswith(COMPACTION_MARKER_PREFIX)]
    assert len(markers) == 1


def test_compaction_records_metrics():
    compactor = ContextCompactor(enabled=True, trigger_observations=3, trigger_chars=999999, keep_recent=1)
    state = _state([f"obs-{i}" for i in range(3)])
    compactor.maybe_compact(state)
    assert state["metrics"]["compaction"] == {"rounds": 1, "folded_observations": 2}


def test_disabled_compactor_is_noop():
    compactor = ContextCompactor(enabled=False, trigger_observations=1, trigger_chars=1, keep_recent=1)
    state = _state([f"obs-{i}" for i in range(10)])
    assert compactor.maybe_compact(state) is None
    assert len(state["observations"]) == 10


def test_long_tool_output_is_truncated_in_snapshot():
    compactor = ContextCompactor(enabled=True, trigger_observations=3, trigger_chars=999999, keep_recent=1)
    state = _state(
        [f"obs-{i}" for i in range(3)],
        tool_results=[_result("job_search", output="z" * 1000)],
    )
    compactor.maybe_compact(state)
    summary = state["compaction"]["changes"][0]["summary"]
    assert len(summary) == ContextCompactor.MAX_ITEM_CHARS
