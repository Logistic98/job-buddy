from .boss_browser.tool import run_boss_browser
from .memory_search import run_memory_search
from .sandbox_execute import run_sandbox_execute
from .trace_summarize import run_trace_summarize

TOOL_EXECUTORS = {
    "core_trace_summarize": run_trace_summarize,
    "memory_search": run_memory_search,
    "sandbox_execute": run_sandbox_execute,
    "boss_browser": run_boss_browser,
}
