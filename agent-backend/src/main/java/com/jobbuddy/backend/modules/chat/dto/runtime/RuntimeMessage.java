package com.jobbuddy.backend.modules.chat.dto.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;

/** Runtime 对话消息。content 与新增协议字段允许保持任意 JSON 结构。 */
public final class RuntimeMessage {
  private static final java.util.List<String> KNOWN_FIELDS =
      Arrays.asList("role", "content", "name", "tool_call_id");

  private final String role;
  private final JsonNode content;
  private final String name;
  private final String toolCallId;
  private final ObjectNode extensions;

  private RuntimeMessage(
      String role, JsonNode content, String name, String toolCallId, ObjectNode extensions) {
    this.role = role;
    this.content = copy(content);
    this.name = name;
    this.toolCallId = toolCallId;
    this.extensions = copyObject(extensions);
  }

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

  public ObjectNode toJson() {
    ObjectNode result = extensions.deepCopy();
    if (role != null) result.put("role", role);
    if (content != null) result.set("content", copy(content));
    if (name != null) result.put("name", name);
    if (toolCallId != null) result.put("tool_call_id", toolCallId);
    return result;
  }

  public String role() {
    return role;
  }

  public JsonNode content() {
    return copy(content);
  }

  public String name() {
    return name;
  }

  public String toolCallId() {
    return toolCallId;
  }

  public ObjectNode extensions() {
    return extensions.deepCopy();
  }

  private static String text(JsonNode value) {
    return value == null || value.isNull() ? null : value.asText();
  }

  private static JsonNode copy(JsonNode value) {
    return value == null ? null : value.deepCopy();
  }

  private static ObjectNode copyObject(ObjectNode value) {
    return value == null ? emptyObject() : value.deepCopy();
  }

  private static ObjectNode emptyObject() {
    return JsonNodeFactory.instance.objectNode();
  }
}
