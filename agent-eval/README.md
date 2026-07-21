# agent-eval

`agent-eval` 是 Agent 运行质量评估服务。它不只检查 Trace 是否跑完，还对完整运行结果做多维质量门禁，避免执行契约缺失、fixture/mock 数据、无证据评分、空回答和失败状态伪完成等问题进入产品链路。

## 评估能力

- **Trace 完整性**：检查 `run_start → understand_goal → task_understanding → capability_route → finalize → run_end` 关键事件和顺序。
- **任务理解质量**：检查结构化意图、领域、置信度和 LLM-first 路由结果。
- **工具执行质量**：检查过程事件是否悬挂、失败是否被最终回答解释。
- **证据可信度**：拒绝 fixture/mock 伪装真实结果；简历匹配必须有证据链、置信度和限制说明。
- **输出质量**：检查是否有可展示回答，禁止把“未产出/失败”包装成“已完成”。
- **安全与副作用**：支持用例声明 `disallow_boss`，用于防止非 Boss 任务触发 Boss 登录/搜索。
- **功能可用性**：未实现或未接入能力不能被标记为成功。

## 接口

- `GET /health`
- `POST /v1/eval/trace`：对 Runtime 事件流和 Backend 节点流执行核心链路评分。
- `POST /v1/eval/run`：完整运行质量评估。
- `POST /v1/eval/capabilities`：Profile 能力清单评估，检查稳定标识、执行意图、工具或 Planner 契约和证据要求。
- `POST /v1/eval/judge`：LLM Judge 开放质量评审，与规则评分互补。通过环境变量 `AGENT_EVAL_JUDGE_BASE_URL`（OpenAI 兼容地址）、`AGENT_EVAL_JUDGE_MODEL`、`AGENT_EVAL_JUDGE_API_KEY`、`AGENT_EVAL_JUDGE_TIMEOUT_SECONDS` 配置；未配置或调用失败时返回 `code=1` 且 `data.enabled/ok` 标记不可用，调用方不得将其视为评审通过。

`/v1/eval/run` 请求示例：

```json
{
  "run": {
    "status": "success",
    "answer": "已完成基于岗位证据的简历匹配评估。",
    "directive": {
      "domain": "job",
      "intent": "resume.match",
      "router": "llm",
      "confidence": 0.91,
      "next_action": "run_resume_match"
    },
    "trace_events": [
      { "event": "run_start" },
      { "event": "understand_goal" },
      { "event": "task_understanding" },
      { "event": "capability_route" },
      { "event": "finalize" },
      { "event": "run_end" }
    ],
    "resume_match": {
      "matches": [
        {
          "id": "j1",
          "score": 82,
          "score_confidence": "medium",
          "evidence_count": 3,
          "evidence": [
            {
              "resume_evidence": "Agent 项目",
              "job_requirement": "Agent 应用开发",
              "assessment": "相关"
            }
          ]
        }
      ]
    }
  },
  "expected": {
    "domain": "job",
    "intent": "resume.match",
    "requires_evidence": true,
    "min_score": 0.75
  }
}
```

`/v1/eval/capabilities` 请求示例：

```json
{
  "profile": {
    "capabilities": [
      {
        "id": "resume.match",
        "execution_intent": "compare_analyze",
        "required_tools": ["resume_match"],
        "evidence_requirements": ["已解析简历", "真实岗位列表或完整 JD", "逐条匹配证据"]
      },
      {
        "id": "interview.prepare",
        "execution_intent": "generate_artifact",
        "planner_needed": true,
        "allowed_tools": ["web_search", "web_fetch"],
        "evidence_requirements": ["已解析简历", "目标岗位 JD"]
      }
    ]
  }
}
```

## 启动与验证

```bash
uv sync --extra dev
uv run python server.py
uv run python -m pytest -q
```
