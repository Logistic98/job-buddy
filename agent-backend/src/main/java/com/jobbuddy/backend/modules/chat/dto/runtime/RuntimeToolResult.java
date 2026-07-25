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
 * Runtime ToolResult 的稳定字段与可前向兼容扩展。
 */
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

  /**
   * 创建运行时工具结果实例。
   *
   * @param toolCallId 工具调用标识
   * @param toolName 工具名称
   * @param success 是否成功
   * @param output 工具输出
   * @param error 错误
   * @param latencyMs 耗时毫秒数
   * @param status 状态
   * @param summary 摘要
   * @param data 数据
   * @param warnings 警告列表
   * @param nextActions 后续动作列表
   * @param traceId Trace 标识
   * @param metadata 元数据
   * @param extensions 扩展名
   */
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

  /**
   * 创建空结果对象。
   *
   * @return 空结果对象
   */
  public static RuntimeToolResult empty() {
    return fromJson(emptyObject());
  }

  /**
   * 从 JSON 文本反序列化对象。
   *
   * @param source 源数据
   * @return 反序列化结果
   */
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

  /**
   * 将对象序列化为 JSON 文本。
   *
   * @return JSON 文本
   */
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
   * @return 是否未包含工具结果数据
   */
  public boolean isEmpty() {
    return toJson().isEmpty();
  }

  /**
   * 读取工具调用标识。
   *
   * @return 工具调用标识
   */
  public String toolCallId() {
    return toolCallId;
  }

  /**
   * 读取工具名称。
   *
   * @return 工具名称
   */
  public String toolName() {
    return toolName;
  }

  /**
   * 创建成功响应。
   *
   * @return 工具是否执行成功
   */
  public Boolean success() {
    return success;
  }

  /**
   * 读取工具输出。
   *
   * @return 工具输出
   */
  public JsonNode output() {
    return copy(output);
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
   * 读取执行耗时。
   *
   * @return 执行耗时，单位为毫秒
   */
  public Long latencyMs() {
    return latencyMs;
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
   * 读取结果摘要。
   *
   * @return 结果摘要
   */
  public String summary() {
    return summary;
  }

  /**
   * 读取结构化数据。
   *
   * @return 结构化数据
   */
  public JsonNode data() {
    return copy(data);
  }

  /**
   * 读取警告列表。
   *
   * @return 警告列表
   */
  public List<String> warnings() {
    return warnings;
  }

  /**
   * 读取后续动作列表。
   *
   * @return 后续动作列表
   */
  public List<String> nextActions() {
    return nextActions;
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
   * 读取扩展元数据。
   *
   * @return 扩展元数据
   */
  public ObjectNode metadata() {
    return metadata == null ? null : metadata.deepCopy();
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
   * 写入字符串列表字段。
   *
   * @param target 扩展字段对象
   * @param field 字段
   * @param values 值列表
   */
  private static void putStrings(ObjectNode target, String field, List<String> values) {
    if (values == null) return;
    ArrayNode array = target.putArray(field);
    for (String value : values) array.add(value);
  }

  /**
   * 将 JSON 数组转换为字符串列表。
   *
   * @param value 待处理值
   * @return 字符串列表
   */
  private static List<String> strings(JsonNode value) {
    if (value == null || !value.isArray()) return null;
    List<String> result = new ArrayList<String>();
    for (JsonNode item : value) result.add(item.isNull() ? null : item.asText());
    return result;
  }

  /**
   * 创建数据的不可变副本。
   *
   * @param values 值列表
   * @return 不可变副本
   */
  private static List<String> immutableCopy(List<String> values) {
    return values == null ? null : Collections.unmodifiableList(new ArrayList<String>(values));
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
   * 读取布尔字段。
   *
   * @param value 待处理值
   * @return 转换后的布尔值
   */
  private static Boolean bool(JsonNode value) {
    return value == null || !value.isBoolean() ? null : Boolean.valueOf(value.booleanValue());
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
   * 复制运行时工具结果。
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
