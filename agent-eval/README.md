# agent-eval

`agent-eval` 是 Agent 运行质量评估服务。它不只检查 Trace 是否跑完，还对完整运行结果做多维质量门禁，避免执行契约缺失、fixture/mock 数据、无证据评分、空回答和失败状态伪完成等问题进入产品链路。

## 从哪里开始

| 目标 | 入口 | 能证明什么 |
| --- | --- | --- |
| 看评分器会不会漏判、误判 | `uv run python -m pytest -q` | 规则、协议、统计和正反例校准正确；不代表真实模型效果 |
| 检查用例是否写对 | `uv run python scripts/run_engine_eval.py --validate-only --cases cases/runtime-regression.yaml` | 唯一 ID、类型、断言字段和预算有效，无网络调用 |
| 评估 Runtime 真实结果 | `scripts/run_engine_eval.py` | 同时检查效果、Trace、时延、规则质量；可选 Judge |
| 验证交付 | `../.agent-harness/scripts/gate.sh agent-eval --quick` | 模块检查 + 确定性评估 + Runtime 代码契约 |
| 检查 Backend 与页面业务结果 | 独立的 Backend / 浏览器验收 | Runtime 直连不能代替登录、数据库事务和页面验收 |

## 用例分层

所有数据集采用相同 YAML 契约：套件声明 `schema_version/id/version/name/description/kind/defaults/fixtures/contracts/cases`，单例声明 `id/category/description/input/expected`。字段和覆盖范围详见 [用例格式与覆盖范围](cases/用例格式与覆盖范围.md)。全目录校验使用 `$ uv run python scripts/validate_cases.py`，不需要逐个登记文件。


| 文件 | 类型 | 典型覆盖 |
| --- | --- | --- |
| `cases/runtime-engine.yaml` | 真实执行 | Hint 快路径、联网证据、Planner、简历切换、工具与拒绝 |
| `cases/runtime-regression.yaml` | 真实执行 | 路由边界、上下文缺失/纠正、附件事实/冲突/注入、精确格式、沙箱计算结果 |
| `cases/runtime-observability.yaml` | 真实执行 | LLM usage、工具耗时与 Trace |
| `cases/grader-calibration.yaml` | 合成校准 | 正常结果与对照反例；漏事件、假成功、伪造工具、时延边界、Judge 不可用 |
| `cases/memory-baseline.yaml` | 真实记忆基线 | 多事实检索、显式更新；由 Memory runner 执行，并检查隔离和生命周期 |
| `cases/business-job.yaml` | Backend 业务规格 | 求职业务验收参考，不能传给 Runtime runner |

真实执行集里的任务输入可以是合成文本，但执行证据必须来自本次 Runtime 请求；合成校准集不会进入真实模型通过率。回归集用于已承诺能力，`suite: capability` 用于探索能力上限；二者应分别运行和解释。这里的公开开发集不是隐藏测试集，不能据此宣称生产泛化能力。

## 判定规则

一次尝试必须同时满足：没有传输错误、有终态、所有声明的效果断言通过、Trace 完整有序、速度预算通过、质量规则通过；显式启用 Judge 时还必须取得有效的通过结论。正确的拒绝和澄清由用例声明，不默认要求它们以 success 结束。

主要维度是任务理解、工具执行、证据、输出、安全、运行契约、速度和可观测性。副作用根据真实工具结果、工具状态和执行 Trace 判断，正文提及平台不等于访问平台。拒绝依据结构化终止原因或拒绝动作判断，不能靠正文出现“不能”刷过评估。规则只能核对可观测契约；回答语义正确性、代码是否硬编码答案等仍需 Judge 和人工检查。

用例契约在 `app/cases.py`。每例声明 `id/category/description/input/expected`。Runtime 消息在 `input.message`，历史与上下文在 `input.messages/metadata`；预算与前置条件在 `options.latency_budget/preconditions`，共享配置在 `defaults`，开放评审要求在 `rubric`。未知断言直接拒绝加载。常用结果断言包括 `answer_equals`、`answer_contains_all`、`answer_not_contains`、`required_tools`、`forbidden_tools`、`tool_output_contains`、`slots`、`runtime_capability`、`expect_status`、`needs_clarification`。子串断言只适合标记和格式，不能代替开放答案事实评审；严格数字输出优先用唯一标记并复核源码。

Runtime SSE 使用 `processing/token/reasoning/done` 等事件；Backend 的 `intent/message/resume_match` 不属于这一采集入口。`preconditions` 不会自动创建简历或登录态，缺少条件会在报告中显式跳过；只有已经准备好环境才能通过 `--precondition` 声明。实际模型评估前应确认部署/模型配置与预算，并使用独立测试账号和合成数据。

## 真实评估与报告

在已启动 Runtime、加载根目录所需环境变量后，从本目录执行：

```bash
# 先查看并校验用例，不调用模型
uv run python scripts/run_engine_eval.py --cases cases/runtime-regression.yaml --validate-only

# 按类别检查典型结果，三次独立采样
uv run python scripts/run_engine_eval.py --cases cases/runtime-regression.yaml \
  --category output_contract,multi_turn --repeats 3 \
  --runtime-url http://127.0.0.1:8010 --deployment-label '<部署版本/模型/配置>'

# 开放质量评估：启用 Judge，未配置或调用失败会使该尝试失败
uv run python scripts/run_engine_eval.py --cases cases/runtime-regression.yaml \
  --category attachment_grounding --repeats 3 --judge
```

`--only` 按 ID 选择，`--category` 按类别选择；非法 ID、空执行集和零重复次数不会成功退出。Boss 用例默认跳过，显式开启时仅允许一次采样，任何一次失败后停止后续 Boss 用例；不得用重复采样访问真实招聘平台。

JSONL 保留全部采样、最终答案、工具证据和评估明细；Markdown 给出分类覆盖、跳过原因、每次失败与统计。报告使用唯一文件名与仅属主可读写权限，可能包含输入与输出证据，禁止提交带真实业务数据的报告。

- `pass@1`：成功尝试占比；Wilson 95% 区间体现样本不足带来的不确定性。
- `pass@k`：本次 k 次尝试至少成功一次；`pass^k`：本次全部成功。这是观测值，不是对未来概率的承诺。
- 时延：p50、最近秩 p95、min/max 和有效样本数；小样本 p95 不能当作生产 SLO。
- 复现信息：评估端 Git SHA/脏状态、用例与依赖锁摘要、Python 版本及手工提供的被测部署标签。评估端版本不等于远端 Runtime 版本。

Judge 输入包含任务、期望、回答与有界工具证据，不能覆盖规则失败。阈值为 0.7，越界、非有限分数和矛盾结论均拒绝。上线前应对代表性好坏答案做人工双人标注，与 Judge 的误接受/误拒绝对照；本模块未把未经人工校准的 Judge 当作可靠真值。

## 接口

- `GET /health`
- `POST /v1/eval/trace`：对 Runtime 事件流和 Backend 节点流执行核心链路评分。
- `POST /v1/eval/run`：完整运行质量评估。
- `POST /v1/eval/capabilities`：Profile 能力清单评估，检查稳定标识、执行意图、工具或 Planner 契约和证据要求。
- `POST /v1/eval/latency`：按 TTFB、TTFT 和总时延预算执行确定性评分。
- `POST /v1/eval/judge`：LLM Judge 开放质量评审，与规则评分互补。通过环境变量 `AGENT_EVAL_JUDGE_BASE_URL`（OpenAI 兼容地址）、`AGENT_EVAL_JUDGE_MODEL`、`AGENT_EVAL_JUDGE_API_KEY`、`AGENT_EVAL_JUDGE_TIMEOUT_SECONDS` 配置；未配置或调用失败时返回 `code=503` 且 `data.enabled/ok` 标记不可用，调用方不得将其视为评审通过。

配置 `AGENT_INTERNAL_SERVICE_TOKEN` 后，除 `/health` 外的接口都必须携带 `X-Internal-Service-Token`；`production` / `prod` 环境缺少令牌时服务拒绝启动。

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
        "evidence_requirements": [
          "已解析简历",
          "真实岗位列表或完整 JD",
          "逐条匹配证据"
        ]
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
$ uv sync --extra dev
$ uv run python server.py
$ uv run python -m pytest -q
$ ../.agent-harness/scripts/evaluate.sh agent-runtime
```

Harness 默认运行评分器测试、Engine Eval 自检和无需外部模型的真实 Runtime 代码契约；`run_engine_eval.py` 则用于直连已启动 Runtime 执行真实流式效果与时延评估。部署配置了 `AGENT_INTERNAL_SERVICE_TOKEN` 时，runner 会从环境变量读取该令牌并仅通过 `X-Internal-Service-Token` 请求头传递，不写入用例载荷、日志或报告。运行本地真实回归前应先加载仓库根目录环境变量；例如只验证切换简历后复评上一岗位的上下文捷径：

```bash
$ set -a
$ source ../.env
$ set +a
$ uv run python scripts/run_engine_eval.py \
  --runtime-url http://127.0.0.1:8010 \
  --cases cases/runtime-engine.yaml \
  --only resume_switch_reuses_selected_job
```
