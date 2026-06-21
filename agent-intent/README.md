# agent-intent

`agent-intent` 是意图识别与路由模块，提供结构化分类结果：`domain`、`intent`、`confidence`、`secondary`、`risk`、`needs_clarification`、`next_action`。

## 接口

- `GET /health`
- `POST /v1/intent/classify`

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
