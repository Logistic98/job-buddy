package com.jobbuddy.backend.modules.chat.dto.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.util.Arrays;

/** 单次 Runtime run 的显式执行预算。 */
public final class RuntimeBudget {
  private static final java.util.List<String> KNOWN_FIELDS =
      Arrays.asList("max_turns", "max_tool_calls", "max_failures", "max_tokens");

  private final Integer maxTurns;
  private final Integer maxToolCalls;
  private final Integer maxFailures;
  private final Integer maxTokens;
  private final ObjectNode extensions;

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

  public ObjectNode toJson() {
    ObjectNode result = extensions.deepCopy();
    if (maxTurns != null) result.put("max_turns", maxTurns.intValue());
    if (maxToolCalls != null) result.put("max_tool_calls", maxToolCalls.intValue());
    if (maxFailures != null) result.put("max_failures", maxFailures.intValue());
    if (maxTokens != null) result.put("max_tokens", maxTokens.intValue());
    return result;
  }

  public Integer maxTurns() {
    return maxTurns;
  }

  public Integer maxToolCalls() {
    return maxToolCalls;
  }

  public Integer maxFailures() {
    return maxFailures;
  }

  public Integer maxTokens() {
    return maxTokens;
  }

  public ObjectNode extensions() {
    return extensions.deepCopy();
  }

  private static Integer integer(JsonNode value) {
    return value == null || !value.isNumber() ? null : Integer.valueOf(value.intValue());
  }

  private static ObjectNode emptyObject() {
    return JsonNodeFactory.instance.objectNode();
  }
}
