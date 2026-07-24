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

  public static RuntimeRunResult empty() {
    return fromJson(emptyObject());
  }

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

  public RuntimeRunResult withError(String message) {
    ObjectNode updated = toJson();
    if (message == null) updated.putNull("error");
    else updated.put("error", message);
    return fromJson(updated);
  }

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

  public Map<String, Object> toMap(JsonCodec jsonCodec) {
    return jsonCodec == null ? Collections.emptyMap() : jsonCodec.toMap(toJson());
  }

  public boolean isEmpty() {
    return toJson().isEmpty();
  }

  public String runId() {
    return runId;
  }

  public String traceId() {
    return traceId;
  }

  public String sessionId() {
    return sessionId;
  }

  public String status() {
    return status;
  }

  public String answer() {
    return answer;
  }

  public String reasoning() {
    return reasoning;
  }

  public List<RuntimeToolResult> toolResults() {
    return toolResults;
  }

  public String stopReason() {
    return stopReason;
  }

  public String error() {
    return error;
  }

  public ObjectNode extensions() {
    return extensions.deepCopy();
  }

  private static void putNode(ObjectNode target, String field, JsonNode value) {
    if (value != null) target.set(field, copy(value));
  }

  private static List<RuntimeMessage> messages(JsonNode value) {
    if (value == null || !value.isArray()) return null;
    List<RuntimeMessage> result = new ArrayList<RuntimeMessage>();
    for (JsonNode item : value) result.add(RuntimeMessage.fromJson(item));
    return result;
  }

  private static List<RuntimeToolResult> toolResults(JsonNode value) {
    if (value == null || !value.isArray()) return null;
    List<RuntimeToolResult> result = new ArrayList<RuntimeToolResult>();
    for (JsonNode item : value) result.add(RuntimeToolResult.fromJson(item));
    return result;
  }

  private static List<RuntimeMessage> immutableMessages(List<RuntimeMessage> values) {
    return values == null
        ? null
        : Collections.unmodifiableList(new ArrayList<RuntimeMessage>(values));
  }

  private static List<RuntimeToolResult> immutableToolResults(List<RuntimeToolResult> values) {
    return values == null
        ? null
        : Collections.unmodifiableList(new ArrayList<RuntimeToolResult>(values));
  }

  private static String text(JsonNode value) {
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Long longValue(JsonNode value) {
    return value == null || !value.isNumber() ? null : Long.valueOf(value.longValue());
  }

  private static JsonNode copy(JsonNode value) {
    return value == null ? null : value.deepCopy();
  }

  private static ObjectNode emptyObject() {
    return JsonNodeFactory.instance.objectNode();
  }
}
