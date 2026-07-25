package com.jobbuddy.backend.modules.chat.dto.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;

/**
 * Runtime 对话消息。content 与新增协议字段允许保持任意 JSON 结构。
 */
public final class RuntimeMessage {
  private static final java.util.List<String> KNOWN_FIELDS =
      Arrays.asList("role", "content", "name", "tool_call_id");

  private final String role;
  private final JsonNode content;
  private final String name;
  private final String toolCallId;
  private final ObjectNode extensions;

  /**
   * 创建运行时消息实例。
   *
   * @param role 角色
   * @param content 内容
   * @param name 名称
   * @param toolCallId 工具调用标识
   * @param extensions 扩展名
   */
  private RuntimeMessage(
      String role, JsonNode content, String name, String toolCallId, ObjectNode extensions) {
    this.role = role;
    this.content = copy(content);
    this.name = name;
    this.toolCallId = toolCallId;
    this.extensions = copyObject(extensions);
  }

  /**
   * 从 JSON 文本反序列化对象。
   *
   * @param source 源数据
   * @return 反序列化结果
   */
  public static RuntimeMessage fromJson(JsonNode source) {
    if (source == null || !source.isObject()) {
      return new RuntimeMessage(null, null, null, null, emptyObject());
    }
    ObjectNode object = (ObjectNode) source;
    ObjectNode extensions = object.deepCopy();
    extensions.remove(KNOWN_FIELDS);
    return new RuntimeMessage(
        text(object.get("role")),
        object.has("content") ? object.get("content") : null,
        text(object.get("name")),
        text(object.get("tool_call_id")),
        extensions);
  }

  /**
   * 将对象序列化为 JSON 文本。
   *
   * @return JSON 文本
   */
  public ObjectNode toJson() {
    ObjectNode result = extensions.deepCopy();
    if (role != null) result.put("role", role);
    if (content != null) result.set("content", copy(content));
    if (name != null) result.put("name", name);
    if (toolCallId != null) result.put("tool_call_id", toolCallId);
    return result;
  }

  /**
   * 读取消息角色。
   *
   * @return 消息角色
   */
  public String role() {
    return role;
  }

  /**
   * 读取消息内容。
   *
   * @return 消息内容
   */
  public JsonNode content() {
    return copy(content);
  }

  /**
   * 读取名称。
   *
   * @return 名称
   */
  public String name() {
    return name;
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
   * 获取扩展字段对象。
   *
   * @return 扩展字段对象
   */
  public ObjectNode extensions() {
    return extensions.deepCopy();
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
   * 复制运行时消息。
   *
   * @param value 待处理值
   * @return 复制
   */
  private static JsonNode copy(JsonNode value) {
    return value == null ? null : value.deepCopy();
  }

  /**
   * 复制对象。
   *
   * @param value 待处理值
   * @return 复制对象
   */
  private static ObjectNode copyObject(ObjectNode value) {
    return value == null ? emptyObject() : value.deepCopy();
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
