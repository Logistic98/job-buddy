# agent-intent

`agent-intent` 是意图识别与路由模块，提供结构化分类结果：`domain`、`intent`、`confidence`、`secondary`、`risk`、`needs_clarification`、`next_action`。同时提供高风险工具调用前的独立 transcript 复核，只读取用户消息和拟执行工具调用，不使用 assistant 自我解释作为授权证据。

## 接口

- `GET /health`
- `POST /v1/intent/classify`
- `POST /v1/intent/review-transcript`：返回 `approve`、`require_human_confirmation` 或 `deny`。

## 启动

```bash
uv sync --extra dev
uv run python server.py
```

## 验证

```bash
uv run python -m pytest
../.agent-harness/scripts/verify.sh agent-intent --quick
```
