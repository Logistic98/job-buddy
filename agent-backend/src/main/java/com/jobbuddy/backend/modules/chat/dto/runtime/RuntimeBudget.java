package com.jobbuddy.backend.modules.chat.dto.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;

/**
 * 单次 Runtime run 的显式执行预算。
 */
public final class RuntimeBudget {
  private static final java.util.List<String> KNOWN_FIELDS =
      Arrays.asList("max_turns", "max_tool_calls", "max_failures", "max_tokens");

  private final Integer maxTurns;
  private final Integer maxToolCalls;
  private final Integer maxFailures;
  private final Integer maxTokens;
  private final ObjectNode extensions;

  /**
   * 创建运行时预算实例。
   *
   * @param maxTurns 最大轮次
   * @param maxToolCalls 最大工具调用次数
   * @param maxFailures 最大失败次数
   * @param maxTokens 最大令牌数
   * @param extensions 扩展名
   */
  private RuntimeBudget(
      Integer maxTurns,
      Integer maxToolCalls,
      Integer maxFailures,
      Integer maxTokens,
      ObjectNode extensions) {
    this.maxTurns = maxTurns;
    this.maxToolCalls = maxToolCalls;
    this.maxFailures = maxFailures;
    this.maxTokens = maxTokens;
    this.extensions = extensions == null ? emptyObject() : extensions.deepCopy();
  }

  /**
   * 从 JSON 文本反序列化对象。
   *
   * @param source 源数据
   * @return 反序列化结果
   */
  public static RuntimeBudget fromJson(JsonNode source) {
    if (source == null || !source.isObject()) return null;
    ObjectNode object = (ObjectNode) source;
    ObjectNode extensions = object.deepCopy();
    extensions.remove(KNOWN_FIELDS);
    return new RuntimeBudget(
        integer(object.get("max_turns")),
        integer(object.get("max_tool_calls")),
        integer(object.get("max_failures")),
        integer(object.get("max_tokens")),
        extensions);
  }

  /**
   * 将对象序列化为 JSON 文本。
   *
   * @return JSON 文本
   */
  public ObjectNode toJson() {
    ObjectNode result = extensions.deepCopy();
    if (maxTurns != null) result.put("max_turns", maxTurns.intValue());
    if (maxToolCalls != null) result.put("max_tool_calls", maxToolCalls.intValue());
    if (maxFailures != null) result.put("max_failures", maxFailures.intValue());
    if (maxTokens != null) result.put("max_tokens", maxTokens.intValue());
    return result;
  }

  /**
   * 读取最大执行轮次。
   *
   * @return 最大执行轮次
   */
  public Integer maxTurns() {
    return maxTurns;
  }

  /**
   * 读取最大工具调用次数。
   *
   * @return 最大工具调用次数
   */
  public Integer maxToolCalls() {
    return maxToolCalls;
  }

  /**
   * 读取最大失败次数。
   *
   * @return 最大失败次数
   */
  public Integer maxFailures() {
    return maxFailures;
  }

  /**
   * 读取最大令牌数。
   *
   * @return 最大令牌数
   */
  public Integer maxTokens() {
    return maxTokens;
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
   * 读取整数值。
   *
   * @param value 待处理值
   * @return 整数值
   */
  private static Integer integer(JsonNode value) {
    return value == null || !value.isNumber() ? null : Integer.valueOf(value.intValue());
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
