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
 * Backend 到 Agent Runtime 的 run 请求。
 *
 * <p>稳定协议字段使用明确类型；metadata 和未来新增顶层字段按 JSON 节点透传，避免 Runtime 升级时静默丢字段。
 */
public final class RuntimeRunRequest {
  private static final List<String> KNOWN_FIELDS =
      Arrays.asList(
          "messages",
          "trace_id",
          "session_id",
          "permission_mode",
          "budget",
          "stream",
          "metadata",
          "resume_from_run_id");

  private final List<RuntimeMessage> messages;
  private final String traceId;
  private final String sessionId;
  private final String permissionMode;
  private final RuntimeBudget budget;
  private final Boolean stream;
  private final ObjectNode metadata;
  private final String resumeFromRunId;
  private final ObjectNode extensions;

  /**
   * 创建运行时运行请求实例。
   *
   * @param messages 消息列表
   * @param traceId Trace 标识
   * @param sessionId 会话标识
   * @param permissionMode 权限模式
   * @param budget 预算
   * @param stream 流式响应
   * @param metadata 元数据
   * @param resumeFromRunId 待恢复的来源运行标识
   * @param extensions 扩展名
   */
  private RuntimeRunRequest(
      List<RuntimeMessage> messages,
      String traceId,
      String sessionId,
      String permissionMode,
      RuntimeBudget budget,
      Boolean stream,
      ObjectNode metadata,
      String resumeFromRunId,
      ObjectNode extensions) {
    this.messages =
        messages == null
            ? null
            : Collections.unmodifiableList(new ArrayList<RuntimeMessage>(messages));
    this.traceId = traceId;
    this.sessionId = sessionId;
    this.permissionMode = permissionMode;
    this.budget = budget;
    this.stream = stream;
    this.metadata = metadata == null ? null : metadata.deepCopy();
    this.resumeFromRunId = resumeFromRunId;
    this.extensions = extensions == null ? emptyObject() : extensions.deepCopy();
  }

  /**
   * 创建空结果对象。
   *
   * @return 空结果对象
   */
  public static RuntimeRunRequest empty() {
    return fromJson(emptyObject());
  }

  /**
   * 根据运行时载荷创建请求。
   *
   * @param payload 请求载荷
   * @param jsonCodec JSON 编解码器
   * @return 运行时请求
   */
  public static RuntimeRunRequest fromPayload(Map<String, Object> payload, JsonCodec jsonCodec) {
    return fromJson(
        jsonCodec == null || payload == null ? emptyObject() : jsonCodec.toTree(payload));
  }

  /**
   * 从 JSON 文本反序列化对象。
   *
   * @param source 源数据
   * @return 反序列化结果
   */
  public static RuntimeRunRequest fromJson(JsonNode source) {
    if (source == null || !source.isObject()) source = emptyObject();
    ObjectNode object = (ObjectNode) source;
    ObjectNode extensions = object.deepCopy();
    extensions.remove(KNOWN_FIELDS);
    return new RuntimeRunRequest(
        messages(object.get("messages")),
        text(object.get("trace_id")),
        text(object.get("session_id")),
        text(object.get("permission_mode")),
        RuntimeBudget.fromJson(object.get("budget")),
        bool(object.get("stream")),
        object.get("metadata") instanceof ObjectNode
            ? ((ObjectNode) object.get("metadata")).deepCopy()
            : null,
        text(object.get("resume_from_run_id")),
        extensions);
  }

  /**
   * 将对象序列化为 JSON 文本。
   *
   * @return JSON 文本
   */
  public ObjectNode toJson() {
    ObjectNode result = extensions.deepCopy();
    if (messages != null) {
      ArrayNode array = result.putArray("messages");
      for (RuntimeMessage message : messages) array.add(message.toJson());
    }
    if (traceId != null) result.put("trace_id", traceId);
    if (sessionId != null) result.put("session_id", sessionId);
    if (permissionMode != null) result.put("permission_mode", permissionMode);
    if (budget != null) result.set("budget", budget.toJson());
    if (stream != null) result.put("stream", stream.booleanValue());
    if (metadata != null) result.set("metadata", metadata.deepCopy());
    if (resumeFromRunId != null) result.put("resume_from_run_id", resumeFromRunId);
    return result;
  }

  /**
   * 创建只增加来源运行标识的新请求，保留原请求不可变。
   *
   * @param sourceRunId 待恢复的来源运行标识
   * @return 带断点恢复字段的新请求
   */
  public RuntimeRunRequest withResumeFromRunId(String sourceRunId) {
    ObjectNode updated = toJson();
    if (sourceRunId == null || sourceRunId.trim().isEmpty()) {
      updated.remove("resume_from_run_id");
    } else {
      updated.put("resume_from_run_id", sourceRunId.trim());
    }
    return fromJson(updated);
  }

  /**
   * 读取消息列表。
   *
   * @return 消息列表
   */
  public List<RuntimeMessage> messages() {
    return messages;
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
   * 读取权限模式。
   *
   * @return 权限模式
   */
  public String permissionMode() {
    return permissionMode;
  }

  /**
   * 构造运行时预算。
   *
   * @return 运行时预算
   */
  public RuntimeBudget budget() {
    return budget;
  }

  /**
   * 订阅运行时运行请求。
   *
   * @return 是否启用流式响应
   */
  public Boolean stream() {
    return stream;
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
   * 读取待恢复的来源运行标识。
   *
   * @return 来源运行标识
   */
  public String resumeFromRunId() {
    return resumeFromRunId;
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
   * 创建空 JSON 对象。
   *
   * @return 空 JSON 对象
   */
  private static ObjectNode emptyObject() {
    return JsonNodeFactory.instance.objectNode();
  }
}
