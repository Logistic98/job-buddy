from app.core.intent.task_understanding import TaskUnderstandingService
from app.tools_builtin.web_search_tool import WebSearchTool


def test_explicit_chinese_year_is_preserved_during_query_normalization(monkeypatch):
    monkeypatch.setattr(
        "app.core.intent.task_understanding.TimeUtils.get_current_date",
        lambda: "2026-07-31",
    )
    service = TaskUnderstandingService(llm_client=None)

    normalized = service._normalize_relative_time_query(
        "查一下 OpenAI 2025年最新模型",
        "OpenAI 2025年最新模型",
    )

    assert normalized == "OpenAI 2025年最新模型"


def test_web_search_does_not_append_current_year_after_explicit_chinese_year():
    queries = WebSearchTool()._expand_queries("OpenAI 2025年最新模型", current_year=2026)

    assert all("2026" not in query for query in queries)
