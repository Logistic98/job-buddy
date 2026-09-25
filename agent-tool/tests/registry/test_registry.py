from app.registry import list_tools


def test_list_tools_contains_sandbox_execute():
    tools = list_tools()
    assert any(tool["name"] == "sandbox_execute" for tool in tools)
