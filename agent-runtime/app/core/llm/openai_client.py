class OpenAICompatibleClient:
    async def chat(self, messages, **kwargs):
        return {"choices": [{"message": {"role": "assistant", "content": ""}}]}
