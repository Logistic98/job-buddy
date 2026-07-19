import asyncio
import contextvars

from app.core.llm.openai_client import OpenAICompatibleClient
from app.core.llm.usage import current_usage, record_usage, start_usage_tracking


def test_record_usage_normalizes_openai_and_anthropic_fields():
    usage = start_usage_tracking()
    record_usage({"prompt_tokens": 100, "completion_tokens": 20, "total_tokens": 120})
    record_usage({"input_tokens": 30, "output_tokens": 10})
    assert usage["prompt_tokens"] == 130
    assert usage["completion_tokens"] == 30
    assert usage["total_tokens"] == 160
    assert usage["llm_calls"] == 2


def test_record_usage_noop_without_tracking():
    def _in_fresh_context():
        # 未开启追踪时 record_usage 为空操作，不抛异常
        record_usage({"prompt_tokens": 10})

    context = contextvars.Context()
    context.run(_in_fresh_context)
    assert context.run(current_usage) is None


def test_record_usage_tolerates_bad_input():
    usage = start_usage_tracking()
    record_usage(None)
    record_usage("not-a-dict")
    record_usage({"prompt_tokens": "oops", "completion_tokens": None})
    assert usage["total_tokens"] == 0
    # None/非字典不计调用，坏字段的字典仍视作一次真实调用
    assert usage["llm_calls"] == 1


def test_usage_isolated_between_concurrent_tasks():
    async def _run(prompt: int) -> dict:
        usage = start_usage_tracking()
        await asyncio.sleep(0)
        record_usage({"prompt_tokens": prompt, "completion_tokens": 1})
        await asyncio.sleep(0)
        return usage

    async def _main():
        return await asyncio.gather(_run(10), _run(200))

    first, second = asyncio.run(_main())
    assert first["prompt_tokens"] == 10
    assert second["prompt_tokens"] == 200
    assert first["llm_calls"] == second["llm_calls"] == 1


def test_child_tasks_share_parent_accumulator():
    async def _main():
        usage = start_usage_tracking()

        async def _call(n: int):
            record_usage({"prompt_tokens": n, "completion_tokens": n})

        await asyncio.gather(_call(5), _call(7))
        return usage

    usage = asyncio.run(_main())
    assert usage["prompt_tokens"] == 12
    assert usage["total_tokens"] == 24
    assert usage["llm_calls"] == 2


def test_record_stream_usage_openai_final_frame():
    client = OpenAICompatibleClient(provider="deepseek_api", api_key="test", model="deepseek-chat")
    usage = start_usage_tracking()
    # 中间增量帧不含 usage，不应计数
    client._record_stream_usage({"choices": [{"delta": {"content": "hi"}}]})
    client._record_stream_usage({"choices": [{"delta": {"content": "!"}}], "usage": None})
    assert usage["llm_calls"] == 0
    # 末帧携带完整 usage，记一次调用
    client._record_stream_usage(
        {"choices": [], "usage": {"prompt_tokens": 50, "completion_tokens": 8, "total_tokens": 58}}
    )
    assert usage == {"prompt_tokens": 50, "completion_tokens": 8, "total_tokens": 58, "llm_calls": 1}


def test_record_stream_usage_anthropic_two_frames_single_call():
    client = OpenAICompatibleClient(provider="claude_max", api_key="test", model="claude-sonnet-4-6")
    usage = start_usage_tracking()
    client._record_stream_usage(
        {"type": "message_start", "message": {"usage": {"input_tokens": 40, "output_tokens": 1}}}
    )
    client._record_stream_usage({"type": "content_block_delta", "delta": {"type": "text_delta", "text": "hi"}})
    client._record_stream_usage({"type": "message_delta", "usage": {"output_tokens": 15}})
    assert usage["prompt_tokens"] == 40
    assert usage["completion_tokens"] == 15
    assert usage["total_tokens"] == 55
    assert usage["llm_calls"] == 1


def test_stream_payload_includes_usage_option_only_for_deepseek():
    deepseek = OpenAICompatibleClient(provider="deepseek_api", api_key="test", model="deepseek-chat")
    payload = deepseek._build_payload([], None, None, None, stream=True)
    assert payload["stream_options"] == {"include_usage": True}
    anthropic = OpenAICompatibleClient(provider="claude_max", api_key="test", model="claude-sonnet-4-6")
    payload = anthropic._build_payload([], None, None, None, stream=True)
    assert "stream_options" not in payload
