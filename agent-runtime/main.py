
import asyncio

from app.core.agent.executor import AgentExecutor
from app.models.schemas import AgentRunRequest, ChatMessage


async def main():
    executor = AgentExecutor(use_llm=False)
    request = AgentRunRequest(messages=[ChatMessage(role="user", content="请回显 hello runtime")])
    response = await executor.execute(request)
    print(response.model_dump_json(indent=2))


if __name__ == "__main__":
    asyncio.run(main())
