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
                "code": 200,
                "message": "success",
                "data": {
                    "status": "success",
                    "summary": "success",
                    "data": {"code": 200, "message": "success", "data": {"search_used_hour": 0}},
                },
            }

    class FakeClient:
        def __init__(self, timeout):
            captured["timeout"] = timeout

        async def __aenter__(self):
            return self

        async def __aexit__(self, exc_type, exc, tb):
            return False

        async def post(self, url, json, headers=None):
            captured["url"] = url
            captured["headers"] = headers
            captured["json"] = json
            return FakeResponse()

    monkeypatch.setenv("AGENT_TOOL_URL", "http://agent-tool.local")
    monkeypatch.setenv("AGENT_INTERNAL_SERVICE_TOKEN", "internal-test-token")
    monkeypatch.setattr("app.tools_builtin.boss_browser_tool.httpx.AsyncClient", FakeClient)
    tool_context.metadata.update({"tenant_id": "tenant-a", "operator_id": "user-a"})

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
    assert result.output == {"code": 200, "message": "success", "data": {"search_used_hour": 0}}
    assert captured["url"] == "http://agent-tool.local/v1/tools/boss_browser/execute"
    assert captured["headers"] == {
        "X-Tenant-Id": "tenant-a",
        "X-Operator-Id": "user-a",
        "X-Internal-Service-Token": "internal-test-token",
    }
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
                "code": 200,
                "message": "success",
                "data": {
                    "status": "success",
                    "summary": "success",
                    "data": {
                        "code": 200,
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

        async def post(self, url, json, headers=None):
            return FakeResponse()

    monkeypatch.setattr("app.tools_builtin.boss_browser_tool.httpx.AsyncClient", FakeClient)
    tool_context.metadata.update({"tenant_id": "tenant-a", "operator_id": "user-a"})

    tool = BossBrowserTool()
    result = await tool.safe_run(
        ToolCall(id="call_boss_qr", name="boss_browser", arguments={"operation": "qr_start", "payload": {}}),
        tool_context,
    )

    assert result.success is True
    assert result.output["data"]["image_base64"] == image_base64
    assert result.metadata["truncated"] is False


@pytest.mark.asyncio
async def test_boss_browser_tool_accepts_read_only_favorite_list_operation(tool_context):
    tool_context.metadata.update({"tenant_id": "tenant-a", "operator_id": "user-a"})
    tool = BossBrowserTool()

    result = await tool.validate_input({"operation": "favorite_list", "payload": {"page": 1}}, tool_context)

    assert result.result is True
    assert "favorite_list" in tool.definition().input_schema["properties"]["operation"]["enum"]
    assert "qr_cancel" in tool.definition().input_schema["properties"]["operation"]["enum"]


@pytest.mark.asyncio
async def test_boss_browser_tool_declares_and_accepts_refresh_auth_operation(tool_context):
    tool_context.metadata.update({"tenant_id": "tenant-a", "operator_id": "user-a"})
    tool = BossBrowserTool()

    result = await tool.validate_input({"operation": "refresh_auth", "payload": {}}, tool_context)
    operation_schema = tool.definition().input_schema["properties"]["operation"]

    assert result.result is True
    assert "refresh_auth" in operation_schema["enum"]
    assert "refresh_auth" in operation_schema["description"]


@pytest.mark.asyncio
async def test_boss_browser_tool_rejects_missing_authenticated_owner(tool_context):
    tool_context.metadata.clear()
    tool = BossBrowserTool()

    result = await tool.safe_run(
        ToolCall(
            id="call_boss_unowned",
            name="boss_browser",
            arguments={"operation": "rate", "payload": {}},
        ),
        tool_context,
    )

    assert result.success is False
    assert "已认证的租户与操作人" in (result.error or "")


@pytest.mark.asyncio
async def test_boss_browser_tool_rejects_unknown_operation(tool_context):
    tool = BossBrowserTool()
    result = await tool.safe_run(
        ToolCall(id="call_boss_bad", name="boss_browser", arguments={"operation": "bad", "payload": {}}),
        tool_context,
    )
    assert result.success is False
    assert "不支持的 Boss 操作" in (result.error or "")
