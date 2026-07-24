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
          "messages", "trace_id", "session_id", "permission_mode", "budget", "stream", "metadata");

  private final List<RuntimeMessage> messages;
  private final String traceId;
  private final String sessionId;
  private final String permissionMode;
  private final RuntimeBudget budget;
  private final Boolean stream;
  private final ObjectNode metadata;
  private final ObjectNode extensions;

  private RuntimeRunRequest(
      List<RuntimeMessage> messages,
      String traceId,
      String sessionId,
      String permissionMode,
      RuntimeBudget budget,
      Boolean stream,
      ObjectNode metadata,
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
    this.extensions = extensions == null ? emptyObject() : extensions.deepCopy();
  }

  public static RuntimeRunRequest empty() {
    return fromJson(emptyObject());
  }

  public static RuntimeRunRequest fromPayload(Map<String, Object> payload, JsonCodec jsonCodec) {
    return fromJson(
        jsonCodec == null || payload == null ? emptyObject() : jsonCodec.toTree(payload));
  }

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
        extensions);
  }

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
    return result;
  }

  public List<RuntimeMessage> messages() {
    return messages;
  }

  public String traceId() {
    return traceId;
  }

  public String sessionId() {
    return sessionId;
  }

  public String permissionMode() {
    return permissionMode;
  }

  public RuntimeBudget budget() {
    return budget;
  }

  public Boolean stream() {
    return stream;
  }

  public ObjectNode metadata() {
    return metadata == null ? null : metadata.deepCopy();
  }

  public ObjectNode extensions() {
    return extensions.deepCopy();
  }

  private static List<RuntimeMessage> messages(JsonNode value) {
    if (value == null || !value.isArray()) return null;
    List<RuntimeMessage> result = new ArrayList<RuntimeMessage>();
    for (JsonNode item : value) result.add(RuntimeMessage.fromJson(item));
    return result;
  }

  private static String text(JsonNode value) {
    return value == null || value.isNull() ? null : value.asText();
  }

  private static Boolean bool(JsonNode value) {
    return value == null || !value.isBoolean() ? null : Boolean.valueOf(value.booleanValue());
  }

  private static ObjectNode emptyObject() {
    return JsonNodeFactory.instance.objectNode();
  }
}
