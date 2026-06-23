import pytest

from app.models.schemas import ToolCall
from app.tools_builtin.boss_browser_tool import BossBrowserTool


@pytest.mark.asyncio
async def test_boss_browser_tool_is_registered_as_deferred_builtin(fresh_registry):
    tool = fresh_registry.get("boss_browser")
    assert tool is not None
    definition = tool.definition()
    assert definition.name == "boss_browser"
    assert definition.should_defer is True
    assert definition.always_load is False
    assert definition.read_only is False
    assert "agent-tool" in definition.description


@pytest.mark.asyncio
async def test_boss_browser_tool_proxies_to_agent_tool(monkeypatch, tool_context):
    captured = {}

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {
                "code": 0,
                "message": "success",
                "data": {
                    "status": "success",
                    "summary": "success",
                    "data": {"code": 0, "message": "success", "data": {"search_used_hour": 0}},
                },
            }

    class FakeClient:
        def __init__(self, timeout):
            captured["timeout"] = timeout

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return False

        async def post(self, url, json):
            captured["url"] = url
            captured["json"] = json
            return FakeResponse()

    monkeypatch.setenv("AGENT_TOOL_URL", "http://agent-tool.local")
    monkeypatch.setattr("app.tools_builtin.boss_browser_tool.httpx.AsyncClient", FakeClient)

    tool = BossBrowserTool()
    result = await tool.safe_run(
        ToolCall(
            id="call_boss_rate",
            name="boss_browser",
            arguments={"operation": "rate", "payload": {"probe": True}},
        ),
        tool_context,
    )

    assert result.success is True
    assert result.output == {"code": 0, "message": "success", "data": {"search_used_hour": 0}}
    assert captured["url"] == "http://agent-tool.local/v1/tools/boss_browser/execute"
    assert captured["json"]["arguments"] == {"operation": "rate", "payload": {"probe": True}}
    assert captured["json"]["confirm"] is True


@pytest.mark.asyncio
async def test_boss_browser_tool_keeps_qr_payload_inline(monkeypatch, tool_context):
    image_base64 = "a" * 20000

    class FakeResponse:
        def raise_for_status(self):
            return None

        def json(self):
            return {
                "code": 0,
                "message": "success",
                "data": {
                    "status": "success",
                    "summary": "success",
                    "data": {
                        "code": 0,
                        "message": "success",
                        "data": {"status": "qr_ready", "image_base64": image_base64, "image_mime": "image/png"},
                    },
                },
            }

    class FakeClient:
        def __init__(self, timeout):
            pass

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return False

        async def post(self, url, json):
            return FakeResponse()

    monkeypatch.setattr("app.tools_builtin.boss_browser_tool.httpx.AsyncClient", FakeClient)

    tool = BossBrowserTool()
    result = await tool.safe_run(
        ToolCall(id="call_boss_qr", name="boss_browser", arguments={"operation": "qr_start", "payload": {}}),
        tool_context,
    )

    assert result.success is True
    assert result.output["data"]["image_base64"] == image_base64
    assert result.metadata["truncated"] is False


@pytest.mark.asyncio
async def test_boss_browser_tool_rejects_unknown_operation(tool_context):
    tool = BossBrowserTool()
    result = await tool.safe_run(
        ToolCall(id="call_boss_bad", name="boss_browser", arguments={"operation": "bad", "payload": {}}),
        tool_context,
    )
    assert result.success is False
    assert "不支持的 Boss 操作" in (result.error or "")
