package com.jobbuddy.backend.modules.chat.dto.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobbuddy.backend.common.util.JsonCodec;
import java.util.Map;

/**
 * 具体 Runtime 工具的动态参数对象；工具 schema 各异，因此以 ObjectNode 保持类型和扩展字段。
 */
public final class RuntimeToolArguments {
  private final ObjectNode values;

  /**
   * 创建运行时工具参数实例。
   *
   * @param values 值列表
   */
  private RuntimeToolArguments(ObjectNode values) {
    this.values = values == null ? JsonNodeFactory.instance.objectNode() : values.deepCopy();
  }

  /**
   * 创建空结果对象。
   *
   * @return 空结果对象
   */
  public static RuntimeToolArguments empty() {
    return new RuntimeToolArguments(JsonNodeFactory.instance.objectNode());
  }

  /**
   * 根据映射创建工具参数。
   *
   * @param arguments 参数
   * @param jsonCodec JSON 编解码器
   * @return 工具参数对象
   */
  public static RuntimeToolArguments fromMap(Map<String, Object> arguments, JsonCodec jsonCodec) {
    if (arguments == null || arguments.isEmpty() || jsonCodec == null) return empty();
    JsonNode tree = jsonCodec.toTree(arguments);
    return tree instanceof ObjectNode ? new RuntimeToolArguments((ObjectNode) tree) : empty();
  }

  /**
   * 从 JSON 文本反序列化对象。
   *
   * @param arguments 参数
   * @return 反序列化结果
   */
  public static RuntimeToolArguments fromJson(JsonNode arguments) {
    return arguments instanceof ObjectNode
        ? new RuntimeToolArguments((ObjectNode) arguments)
        : empty();
  }

  /**
   * 将对象序列化为 JSON 文本。
   *
   * @return JSON 文本
   */
  public ObjectNode toJson() {
    return values.deepCopy();
  }

  /**
   * 获取运行时工具参数。
   *
   * @param field 字段
   * @return 查询结果
   */
  public JsonNode get(String field) {
    JsonNode value = values.get(field);
    return value == null ? null : value.deepCopy();
  }

  /**
   * 将输入转换为键值映射。
   *
   * @param jsonCodec JSON 编解码器
   * @return 键值映射
   */
  public Map<String, Object> toMap(JsonCodec jsonCodec) {
    return jsonCodec == null ? java.util.Collections.emptyMap() : jsonCodec.toMap(values);
  }

  /**
   * 判断是否空值。
   *
   * @return 是否未包含工具参数
   */
  public boolean isEmpty() {
    return values.isEmpty();
  }
}
