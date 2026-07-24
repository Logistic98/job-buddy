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

/** Runtime ToolResult 的稳定字段与可前向兼容扩展。 */
public final class RuntimeToolResult {
  private static final List<String> KNOWN_FIELDS =
      Arrays.asList(
          "tool_call_id",
          "tool_name",
          "success",
          "output",
          "error",
          "latency_ms",
          "status",
          "summary",
          "data",
          "warnings",
          "next_actions",
          "trace_id",
          "metadata");

  private final String toolCallId;
  private final String toolName;
  private final Boolean success;
  private final JsonNode output;
  private final String error;
  private final Long latencyMs;
  private final String status;
  private final String summary;
  private final JsonNode data;
  private final List<String> warnings;
  private final List<String> nextActions;
  private final String traceId;
  private final ObjectNode metadata;
  private final ObjectNode extensions;

  private RuntimeToolResult(
      String toolCallId,
      String toolName,
      Boolean success,
      JsonNode output,
      String error,
      Long latencyMs,
      String status,
      String summary,
      JsonNode data,
      List<String> warnings,
      List<String> nextActions,
      String traceId,
      ObjectNode metadata,
      ObjectNode extensions) {
    this.toolCallId = toolCallId;
    this.toolName = toolName;
    this.success = success;
    this.output = copy(output);
    this.error = error;
    this.latencyMs = latencyMs;
    this.status = status;
    this.summary = summary;
    this.data = copy(data);
    this.warnings = immutableCopy(warnings);
    this.nextActions = immutableCopy(nextActions);
    this.traceId = traceId;
    this.metadata = metadata == null ? null : metadata.deepCopy();
    this.extensions = extensions == null ? emptyObject() : extensions.deepCopy();
  }

  public static RuntimeToolResult empty() {
    return fromJson(emptyObject());
  }

  public static RuntimeToolResult fromJson(JsonNode source) {
    if (source == null || !source.isObject()) source = emptyObject();
    ObjectNode object = (ObjectNode) source;
    ObjectNode extensions = object.deepCopy();
    extensions.remove(KNOWN_FIELDS);
    return new RuntimeToolResult(
        text(object.get("tool_call_id")),
        text(object.get("tool_name")),
        bool(object.get("success")),
        object.has("output") ? object.get("output") : null,
        text(object.get("error")),
        longValue(object.get("latency_ms")),
        text(object.get("status")),
        text(object.get("summary")),
        object.has("data") ? object.get("data") : null,
        strings(object.get("warnings")),
        strings(object.get("next_actions")),
        text(object.get("trace_id")),
        object.get("metadata") instanceof ObjectNode
            ? ((ObjectNode) object.get("metadata")).deepCopy()
            : null,
        extensions);
  }

  public ObjectNode toJson() {
    ObjectNode result = extensions.deepCopy();
    if (toolCallId != null) result.put("tool_call_id", toolCallId);
    if (toolName != null) result.put("tool_name", toolName);
    if (success != null) result.put("success", success.booleanValue());
    if (output != null) result.set("output", copy(output));
    if (error != null) result.put("error", error);
    if (latencyMs != null) result.put("latency_ms", latencyMs.longValue());
    if (status != null) result.put("status", status);
    if (summary != null) result.put("summary", summary);
    if (data != null) result.set("data", copy(data));
    putStrings(result, "warnings", warnings);
    putStrings(result, "next_actions", nextActions);
    if (traceId != null) result.put("trace_id", traceId);
    if (metadata != null) result.set("metadata", metadata.deepCopy());
    return result;
  }

  public Map<String, Object> toMap(JsonCodec jsonCodec) {
    return jsonCodec == null ? Collections.emptyMap() : jsonCodec.toMap(toJson());
  }

  public boolean isEmpty() {
    return toJson().isEmpty();
  }

  public String toolCallId() {
    return toolCallId;
  }

  public String toolName() {
    return toolName;
  }

  public Boolean success() {
    return success;
  }

  public JsonNode output() {
    return copy(output);
  }

  public String error() {
    return error;
  }

  public Long latencyMs() {
    return latencyMs;
  }

  public String status() {
    return status;
  }

  public String summary() {
    return summary;
  }

  public JsonNode data() {
    return copy(data);
  }

  public List<String> warnings() {
    return warnings;
  }

  public List<String> nextActions() {
    return nextActions;
  }

  public String traceId() {
    return traceId;
  }

  public ObjectNode metadata() {
    return metadata == null ? null : metadata.deepCopy();
  }

  public ObjectNode extensions() {
    return extensions.deepCopy();
  }

  private static void putStrings(ObjectNode target, String field, List<String> values) {
    if (values == null) return;
    ArrayNode array = target.putArray(field);
    for (String value : values) array.add(value);
  }

  private static List<String> strings(JsonNode value) {
    if (value == null || !value.isArray()) return null;
    List<String> result = new ArrayList<String>();
    for (JsonNode item : value) result.add(item.isNull() ? null : item.asText());
    return result;
  }

  private static List<String> immutableCopy(List<String> values) {
    return values == null ? null : Collections.unmodifiableList(new ArrayList<String>(values));
  }

  private static String text(JsonNode value) {
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Boolean bool(JsonNode value) {
    return value == null || !value.isBoolean() ? null : Boolean.valueOf(value.booleanValue());
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
