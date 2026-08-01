from app.core.intent.web_search_policy import WebSearchPolicy
from app.models.schemas import QueryRewrite, TaskUnderstandingResult


def _search_capable_task(
    *,
    resolved_query: str,
    retrieval_query: str = "",
    planner_query: str = "",
) -> TaskUnderstandingResult:
    return TaskUnderstandingResult(
        original_query="",
        rewritten_query=QueryRewrite(
            resolved_query=resolved_query,
            retrieval_query=retrieval_query or resolved_query,
            planner_query=planner_query or resolved_query,
        ),
        metadata={
            "capability_contract": {
                "required_tools": [],
                "allowed_tools": ["web_search"],
            }
        },
    )


def test_resolved_latest_follow_up_requires_web_search():
    task = _search_capable_task(
        resolved_query="查找 Anthropic 最新发布的工程博客",
        retrieval_query="Anthropic 最新工程博客",
        planner_query="从 Anthropic 官方来源查找最新工程博客",
    )

    decision = WebSearchPolicy().decide("最新的呢", task)

    assert decision.mode == "required"
    assert decision.trigger == "autonomous"
    assert "temporal_freshness" in decision.signals


def test_original_opt_out_cannot_be_bypassed_by_resolved_search_query():
    task = _search_capable_task(
        resolved_query="联网查找 Anthropic 最新发布的工程博客",
        retrieval_query="Anthropic 最新工程博客",
        planner_query="搜索 Anthropic 官网并返回最新工程博客",
    )

    decision = WebSearchPolicy().decide("不要联网，最新的呢", task)

    assert decision.mode == "prohibited"
    assert decision.trigger == "user"
    assert "explicit_opt_out" in decision.signals


def test_resolved_current_resume_follow_up_keeps_web_search_optional():
    task = _search_capable_task(
        resolved_query="分析当前选择的简历",
        retrieval_query="当前简历分析",
        planner_query="读取并分析当前简历",
    )

    decision = WebSearchPolicy().decide("当前这份简历呢", task)

    assert decision.mode == "optional"
    assert "provided_context" in decision.signals
