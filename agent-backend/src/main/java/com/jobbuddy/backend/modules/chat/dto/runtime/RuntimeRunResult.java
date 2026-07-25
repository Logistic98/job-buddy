package com.jobbuddy.backend.modules.chat.dto.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobbuddy.backend.common.util.JsonCodec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Agent Runtime run/done 结果。
 *
 * <p>Backend 直接消费的标识、终态、答案和工具结果为强类型；Planner、directive、Trace 等可演进结构以及未知字段以 JSON 节点无损透传。
 */
public final class RuntimeRunResult {
  private static final List<String> KNOWN_FIELDS =
      Arrays.asList(
          "run_id",
          "trace_id",
          "session_id",
          "status",
          "start_time",
          "end_time",
          "latency_ms",
          "answer",
          "reasoning",
          "messages",
          "plan",
          "directive",
          "task_understanding",
          "tool_results",
          "permission_records",
          "logs",
          "trace_events",
          "metrics",
          "stop_reason",
          "error");

  private final String runId;
  private final String traceId;
  private final String sessionId;
  private final String status;
  private final String startTime;
  private final String endTime;
  private final Long latencyMs;
  private final String answer;
  private final String reasoning;
  private final List<RuntimeMessage> messages;
  private final JsonNode plan;
  private final JsonNode directive;
  private final JsonNode taskUnderstanding;
  private final List<RuntimeToolResult> toolResults;
  private final JsonNode permissionRecords;
  private final JsonNode logs;
  private final JsonNode traceEvents;
  private final ObjectNode metrics;
  private final String stopReason;
  private final String error;
  private final ObjectNode extensions;

  /**
   * 创建运行时运行结果实例。
   *
   * @param runId 运行标识
   * @param traceId Trace 标识
   * @param sessionId 会话标识
   * @param status 状态
   * @param startTime 开始时间
   * @param endTime 结束时间
   * @param latencyMs 耗时毫秒数
   * @param answer 答案
   * @param reasoning 推理过程
   * @param messages 消息列表
   * @param plan 计划
   * @param directive 指令
   * @param taskUnderstanding 任务理解
   * @param toolResults 工具结果列表
   * @param permissionRecords 权限记录列表
   * @param logs 日志列表
   * @param traceEvents Trace 事件列表
   * @param metrics 指标数据
   * @param stopReason 停止原因
   * @param error 错误
   * @param extensions 扩展名
   */
  private RuntimeRunResult(
      String runId,
      String traceId,
      String sessionId,
      String status,
      String startTime,
      String endTime,
      Long latencyMs,
      String answer,
      String reasoning,
      List<RuntimeMessage> messages,
      JsonNode plan,
      JsonNode directive,
      JsonNode taskUnderstanding,
      List<RuntimeToolResult> toolResults,
      JsonNode permissionRecords,
      JsonNode logs,
      JsonNode traceEvents,
      ObjectNode metrics,
      String stopReason,
      String error,
      ObjectNode extensions) {
    this.runId = runId;
    this.traceId = traceId;
    this.sessionId = sessionId;
    this.status = status;
    this.startTime = startTime;
    this.endTime = endTime;
    this.latencyMs = latencyMs;
    this.answer = answer;
    this.reasoning = reasoning;
    this.messages = immutableMessages(messages);
    this.plan = copy(plan);
    this.directive = copy(directive);
    this.taskUnderstanding = copy(taskUnderstanding);
    this.toolResults = immutableToolResults(toolResults);
    this.permissionRecords = copy(permissionRecords);
    this.logs = copy(logs);
    this.traceEvents = copy(traceEvents);
    this.metrics = metrics == null ? null : metrics.deepCopy();
    this.stopReason = stopReason;
    this.error = error;
    this.extensions = extensions == null ? emptyObject() : extensions.deepCopy();
  }

  /**
   * 创建空结果对象。
   *
   * @return 空结果对象
   */
  public static RuntimeRunResult empty() {
    return fromJson(emptyObject());
  }

  /**
   * 从 JSON 文本反序列化对象。
   *
   * @param source 源数据
   * @return 反序列化结果
   */
  public static RuntimeRunResult fromJson(JsonNode source) {
    if (source == null || !source.isObject()) source = emptyObject();
    ObjectNode object = (ObjectNode) source;
    ObjectNode extensions = object.deepCopy();
    extensions.remove(KNOWN_FIELDS);
    return new RuntimeRunResult(
        text(object.get("run_id")),
        text(object.get("trace_id")),
        text(object.get("session_id")),
        text(object.get("status")),
        text(object.get("start_time")),
        text(object.get("end_time")),
        longValue(object.get("latency_ms")),
        text(object.get("answer")),
        text(object.get("reasoning")),
        messages(object.get("messages")),
        object.has("plan") ? object.get("plan") : null,
        object.has("directive") ? object.get("directive") : null,
        object.has("task_understanding") ? object.get("task_understanding") : null,
        toolResults(object.get("tool_results")),
        object.has("permission_records") ? object.get("permission_records") : null,
        object.has("logs") ? object.get("logs") : null,
        object.has("trace_events") ? object.get("trace_events") : null,
        object.get("metrics") instanceof ObjectNode
            ? ((ObjectNode) object.get("metrics")).deepCopy()
            : null,
        text(object.get("stop_reason")),
        text(object.get("error")),
        extensions);
  }

  /**
   * 创建携带错误信息的结果。
   *
   * @param message 消息内容
   * @return 错误结果
   */
  public RuntimeRunResult withError(String message) {
    ObjectNode updated = toJson();
    if (message == null) updated.putNull("error");
    else updated.put("error", message);
    return fromJson(updated);
  }

  /**
   * 将对象序列化为 JSON 文本。
   *
   * @return JSON 文本
   */
  public ObjectNode toJson() {
    ObjectNode result = extensions.deepCopy();
    if (runId != null) result.put("run_id", runId);
    if (traceId != null) result.put("trace_id", traceId);
    if (sessionId != null) result.put("session_id", sessionId);
    if (status != null) result.put("status", status);
    if (startTime != null) result.put("start_time", startTime);
    if (endTime != null) result.put("end_time", endTime);
    if (latencyMs != null) result.put("latency_ms", latencyMs.longValue());
    if (answer != null) result.put("answer", answer);
    if (reasoning != null) result.put("reasoning", reasoning);
    if (messages != null) {
      ArrayNode array = result.putArray("messages");
      for (RuntimeMessage message : messages) array.add(message.toJson());
    }
    putNode(result, "plan", plan);
    putNode(result, "directive", directive);
    putNode(result, "task_understanding", taskUnderstanding);
    if (toolResults != null) {
      ArrayNode array = result.putArray("tool_results");
      for (RuntimeToolResult toolResult : toolResults) array.add(toolResult.toJson());
    }
    putNode(result, "permission_records", permissionRecords);
    putNode(result, "logs", logs);
    putNode(result, "trace_events", traceEvents);
    if (metrics != null) result.set("metrics", metrics.deepCopy());
    if (stopReason != null) result.put("stop_reason", stopReason);
    if (error != null) result.put("error", error);
    return result;
  }

  /**
   * 将输入转换为键值映射。
   *
   * @param jsonCodec JSON 编解码器
   * @return 键值映射
   */
  public Map<String, Object> toMap(JsonCodec jsonCodec) {
    return jsonCodec == null ? Collections.emptyMap() : jsonCodec.toMap(toJson());
  }

  /**
   * 判断是否空值。
   *
   * @return 是否未包含运行结果
   */
  public boolean isEmpty() {
    return toJson().isEmpty();
  }

  /**
   * 执行标识。
   *
   * @return 执行标识
   */
  public String runId() {
    return runId;
  }

  /**
   * 读取 Trace 标识。
   *
   * @return Trace 标识
   */
  public String traceId() {
    return traceId;
  }

  /**
   * 读取会话标识。
   *
   * @return 会话标识
   */
  public String sessionId() {
    return sessionId;
  }

  /**
   * 读取执行状态。
   *
   * @return 执行状态
   */
  public String status() {
    return status;
  }

  /**
   * 获取助手回答。
   *
   * @return 助手回答
   */
  public String answer() {
    return answer;
  }

  /**
   * 读取推理过程。
   *
   * @return 推理过程
   */
  public String reasoning() {
    return reasoning;
  }

  /**
   * 读取工具结果列表。
   *
   * @return 工具结果列表
   */
  public List<RuntimeToolResult> toolResults() {
    return toolResults;
  }

  /**
   * 停止原因。
   *
   * @return 停止原因
   */
  public String stopReason() {
    return stopReason;
  }

  /**
   * 创建错误响应。
   *
   * @return 错误响应
   */
  public String error() {
    return error;
  }

  /**
   * 获取扩展字段对象。
   *
   * @return 扩展字段对象
   */
  public ObjectNode extensions() {
    return extensions.deepCopy();
  }

  /**
   * 写入 JSON 节点字段。
   *
   * @param target 扩展字段对象
   * @param field 字段
   * @param value 待处理值
   */
  private static void putNode(ObjectNode target, String field, JsonNode value) {
    if (value != null) target.set(field, copy(value));
  }

  /**
   * 读取消息列表。
   *
   * @param value 待处理值
   * @return 消息列表
   */
  private static List<RuntimeMessage> messages(JsonNode value) {
    if (value == null || !value.isArray()) return null;
    List<RuntimeMessage> result = new ArrayList<RuntimeMessage>();
    for (JsonNode item : value) result.add(RuntimeMessage.fromJson(item));
    return result;
  }

  /**
   * 读取工具结果列表。
   *
   * @param value 待处理值
   * @return 工具结果列表
   */
  private static List<RuntimeToolResult> toolResults(JsonNode value) {
    if (value == null || !value.isArray()) return null;
    List<RuntimeToolResult> result = new ArrayList<RuntimeToolResult>();
    for (JsonNode item : value) result.add(RuntimeToolResult.fromJson(item));
    return result;
  }

  /**
   * 创建消息列表的不可变副本。
   *
   * @param values 值列表
   * @return 不可变消息列表
   */
  private static List<RuntimeMessage> immutableMessages(List<RuntimeMessage> values) {
    return values == null
        ? null
        : Collections.unmodifiableList(new ArrayList<RuntimeMessage>(values));
  }

  /**
   * 创建工具结果列表的不可变副本。
   *
   * @param values 值列表
   * @return 不可变工具结果列表
   */
  private static List<RuntimeToolResult> immutableToolResults(List<RuntimeToolResult> values) {
    return values == null
        ? null
        : Collections.unmodifiableList(new ArrayList<RuntimeToolResult>(values));
  }

  /**
   * 读取文本内容。
   *
   * @param value 待处理值
   * @return 文本内容
   */
  private static String text(JsonNode value) {
    return value == null || value.isNull() ? null : value.asText();
  }

  /**
   * 读取长整型值。
   *
   * @param value 待处理值
   * @return 长整型值
   */
  private static Long longValue(JsonNode value) {
    return value == null || !value.isNumber() ? null : Long.valueOf(value.longValue());
  }

  /**
   * 复制运行时运行结果。
   *
   * @param value 待处理值
   * @return 复制
   */
  private static JsonNode copy(JsonNode value) {
    return value == null ? null : value.deepCopy();
  }

  /**
   * 创建空 JSON 对象。
   *
   * @return 空 JSON 对象
   */
  private static ObjectNode emptyObject() {
    return JsonNodeFactory.instance.objectNode();
  }
}
