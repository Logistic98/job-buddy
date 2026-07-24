package com.jobbuddy.backend.modules.chat.dto.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobbuddy.backend.common.util.JsonCodec;
import java.util.Map;

/** 具体 Runtime 工具的动态参数对象；工具 schema 各异，因此以 ObjectNode 保持类型和扩展字段。 */
public final class RuntimeToolArguments {
  private final ObjectNode values;

  private RuntimeToolArguments(ObjectNode values) {
    this.values = values == null ? JsonNodeFactory.instance.objectNode() : values.deepCopy();
  }

  public static RuntimeToolArguments empty() {
    return new RuntimeToolArguments(JsonNodeFactory.instance.objectNode());
  }

  public static RuntimeToolArguments fromMap(Map<String, Object> arguments, JsonCodec jsonCodec) {
    if (arguments == null || arguments.isEmpty() || jsonCodec == null) return empty();
    JsonNode tree = jsonCodec.toTree(arguments);
    return tree instanceof ObjectNode ? new RuntimeToolArguments((ObjectNode) tree) : empty();
  }

  public static RuntimeToolArguments fromJson(JsonNode arguments) {
    return arguments instanceof ObjectNode
        ? new RuntimeToolArguments((ObjectNode) arguments)
        : empty();
  }

  public ObjectNode toJson() {
    return values.deepCopy();
  }

  public JsonNode get(String field) {
    JsonNode value = values.get(field);
    return value == null ? null : value.deepCopy();
  }

  public Map<String, Object> toMap(JsonCodec jsonCodec) {
    return jsonCodec == null ? java.util.Collections.emptyMap() : jsonCodec.toMap(values);
  }

  public boolean isEmpty() {
    return values.isEmpty();
  }
}
