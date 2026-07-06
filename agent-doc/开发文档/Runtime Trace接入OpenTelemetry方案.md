# Runtime Trace 接入 OpenTelemetry 方案

## 为什么做

当前 agent-runtime 的 Trace 由 `TraceRecorder` 承载：内存滚动窗口供实时查询，JSONL 按 run 落盘支撑回放与 agent-eval 取证。该方案满足单机排障与评估，但存在两点不足：一是 Trace 只能落在本机文件，无法汇入 Jaeger、Tempo、SigNoz 等标准可观测后端做跨服务关联与瀑布图分析；二是仓库设计原则明确要求"观测按 OpenTelemetry 上报 Trace / 日志 / 指标"，当前实现与该原则存在缺口。本方案在保留既有 JSONL Trace 全部行为的前提下，为 Runtime 增加可配置的 OpenTelemetry 导出能力。

## 方案是什么

在 `app/core/observability/` 新增 `OtelExporter`，将每条 `TraceEvent` 映射为一个 OTLP Span，通过 OTLP/HTTP JSON 协议（`/v1/traces`）上报到可配置的 Collector 端点。核心取舍：

- 不引入 `opentelemetry-sdk` 等重依赖，复用已有 `httpx` 直接构造 OTLP/HTTP JSON 载荷。OTLP JSON 是稳定公开协议，Runtime 的 Trace 事件结构简单（点事件 + 扁平 payload），手工映射成本远低于引入并维护 SDK 及其版本矩阵。SDK 接入作为演进项保留。
- 默认关闭（`otel_enabled: false`），开启后导出失败静默降级为 warning 日志，绝不阻塞主链路；导出走 `asyncio.create_task` fire-and-forget，不增加 `record` 的时延。
- JSONL 落盘与内存窗口行为完全不变，OTel 导出是纯增量旁路。agent-eval 的取证与回放继续基于 JSONL，不依赖 OTel。

字段映射：

| TraceEvent 字段 | OTLP 字段 |
| --- | --- |
| trace_id | Span.traceId（MD5 哈希为 32 位十六进制，保证同一 run 的事件聚合为一条 trace） |
| —— | Span.spanId（随机 16 位十六进制） |
| event | Span.name |
| timestamp（记录时刻） | startTimeUnixNano == endTimeUnixNano（点事件，零时长） |
| run_id / trace_id | Span attributes：`run_id`、`trace_id` |
| payload | Span attribute：`payload`（JSON 字符串，避免深层扁平化引发字段爆炸） |
| —— | Resource attribute：`service.name`（配置项 `otel_service_name`） |

## 具体怎么做

1. `ObservabilityConfig` 新增配置项：`otel_enabled: bool = False`、`otel_endpoint: str = "http://localhost:4318/v1/traces"`、`otel_service_name: str = "job-buddy-runtime"`、`otel_timeout_seconds: float = 3.0`。端点通过 `config.yaml` 环境变量占位符注入，不硬编码。
2. 新增 `app/core/observability/otel.py`：`OtelExporter.export(item: TraceEvent)` 构造 OTLP JSON 载荷并 POST；构造与发送分离（`build_payload` 独立方法），便于离线单测断言载荷结构。
3. `TraceRecorder.record` 在落盘后调用 `self._otel.submit(item)`：开关关闭直接返回；开启时创建后台任务发送，异常捕获记 warning。
4. 单元测试 `tests/test_otel_exporter.py`：默认关闭零网络调用；载荷结构正确（traceId/spanId 十六进制长度、span name、attributes、service.name）；同一 trace_id 映射稳定；发送失败不抛异常且不影响 record 主流程。

## 涉及模块与接口

仅 agent-runtime：`app/core/common/settings.py`（ObservabilityConfig）、`app/core/observability/otel.py`（新增）、`app/core/observability/trace.py`（record 旁路挂接）。对外 HTTP 接口、SSE 事件、Trace JSONL 格式均无变化。agent-eval grader 消费的必备事件集合与 metrics 键不受影响，无需同步修改评估用例与 Harness 脚本。

## 风险与注意

- Collector 不可用时导出持续失败：仅记 warning，不重试（点事件丢失可接受，权威数据在 JSONL）；避免重试放大故障。
- fire-and-forget 任务在进程退出时可能丢尾部事件：可接受，OTel 通道定位为在线观测而非取证。
- payload 以 JSON 字符串整体上报，超大 payload 会增大导出体积：沿用 Trace 本身的 payload 约束，导出侧对 payload 字符串做 8000 字符截断兜底。

## 如何验证

`uv run python -m pytest -q tests/test_otel_exporter.py` 与全量 pytest 通过；`./.agent-harness/scripts/gate.sh agent-runtime --quick` 通过。有 Collector 的环境可将 `otel_enabled` 置 true 并指向本地 Jaeger（4318 端口）人工验证瀑布图。

## 后续演进

- 事件批量缓冲与定时 flush，降低高频事件下的 HTTP 请求数。
- 引入官方 SDK 与 W3C TraceContext 传播，实现 Java BFF 与 Runtime 的跨服务 trace 关联。
- 指标（token 用量、时延、缓存命中率）经 OTLP Metrics 通道上报。
