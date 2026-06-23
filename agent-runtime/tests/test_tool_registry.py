
import pytest

from app.core.tool.registry import ToolRegistry
from app.tools_builtin import register_builtin_tools
from app.tools_builtin.echo_tool import EchoTool


def test_register_builtin_tools_full_set():
    registry = ToolRegistry()
    register_builtin_tools(registry)
    names = registry.names()
    expected = {"echo", "file_read", "file_write", "file_edit", "glob", "grep", "web_fetch", "web_search", "shell_exec"}
    assert expected.issubset(set(names))


def test_registry_alias_lookup():
    registry = ToolRegistry()
    registry.register(EchoTool())
    assert registry.has("echo")
    assert registry.has("print_text")
    assert registry.get("print_text").name == "echo"


def test_registry_duplicate_register_rejected():
    registry = ToolRegistry()
    registry.register(EchoTool())
    with pytest.raises(ValueError):
        registry.register(EchoTool())


def test_registry_unregister_clears_aliases():
    registry = ToolRegistry()
    registry.register(EchoTool())
    registry.unregister("echo")
    assert not registry.has("echo")
    assert not registry.has("print_text")


def test_registry_list_definitions_sorted_and_enabled(fresh_registry):
    definitions = fresh_registry.list_definitions()
    names = [item.name for item in definitions]
    assert names == sorted(names)
    assert all(item.enabled for item in definitions)
