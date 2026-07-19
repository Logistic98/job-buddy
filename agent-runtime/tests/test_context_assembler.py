from app.core.context.assembler import ContextAssembler
from app.models.schemas import ChatMessage, TaskUnderstandingResult, ToolResult


def test_context_assembler_outputs_budgeted_summary_and_metrics():
    assembler = ContextAssembler(max_chars=300)
    task = TaskUnderstandingResult(original_query="hello", profile="default")

    result = assembler.assemble(
        messages=[ChatMessage(role="user", content="hello")],
        task=task,
        observations=["obs"],
        tool_results=[ToolResult(tool_call_id="1", tool_name="echo", success=True, output={"text": "hello"})],
        metadata={"resume_id": "r1"},
    )

    assert result["summary"]
    assert result["payload"]["current_step"]["profile"] == "default"
    assert result["metrics"]["message_count"] == 1


def test_long_term_refs_use_config_driven_business_keys():
    from app.core.common.settings import settings

    assembler = ContextAssembler(max_chars=2000)
    task = TaskUnderstandingResult(original_query="hi", profile="default")
    result = assembler.assemble(
        messages=[ChatMessage(role="user", content="hi")],
        task=task,
        observations=[],
        tool_results=[],
        metadata={"resume_id": "r1", "previous_slots": {"city": "上海"}},
    )
    keys = {ref["key"] for ref in result["payload"]["long_term_refs"] if ref.get("source") == "request_metadata"}
    # 通用运行时键始终透出；业务键仅在部署配置声明后透出，核心代码不硬编码。
    assert "previous_slots" in keys
    if "resume_id" in settings.business_metadata_keys:
        assert "resume_id" in keys
