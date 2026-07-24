from app.core.tool.registry import ToolRegistry
from app.tools_builtin.boss_browser_tool import BossBrowserTool
from app.tools_builtin.echo_tool import EchoTool
from app.tools_builtin.file_edit_tool import FileEditTool
from app.tools_builtin.file_read_tool import FileReadTool
from app.tools_builtin.file_write_tool import FileWriteTool
from app.tools_builtin.interview_tools import InterviewQuestionGenerateTool
from app.tools_builtin.resume_tools import JobProfileSummaryTool, ResumeAnalyzeTool, ResumeMatchTool, ResumeParseTool
from app.tools_builtin.search_tools import GlobTool, GrepTool
from app.tools_builtin.shell_tool import ShellTool
from app.tools_builtin.web_fetch_tool import WebFetchTool
from app.tools_builtin.web_search_tool import WebSearchTool


def _builtin_tool_instances():
    return [
        EchoTool(),
        BossBrowserTool(),
        FileReadTool(),
        FileWriteTool(),
        FileEditTool(),
        GlobTool(),
        GrepTool(),
        WebFetchTool(),
        WebSearchTool(),
        ShellTool(),
        InterviewQuestionGenerateTool(),
        ResumeParseTool(),
        ResumeAnalyzeTool(),
        ResumeMatchTool(),
        JobProfileSummaryTool(),
    ]


def register_builtin_tools(registry: ToolRegistry):
    for tool in _builtin_tool_instances():
        registry.register(tool)


def register_missing_builtin_tools(registry: ToolRegistry):
    """幂等注册 Registry 中缺失的内置工具。"""

    registered = []
    for tool in _builtin_tool_instances():
        if registry.has(tool.name):
            continue
        registry.register(tool)
        registered.append(tool.name)
    return registered
