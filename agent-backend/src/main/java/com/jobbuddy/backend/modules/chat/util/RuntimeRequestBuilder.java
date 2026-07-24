package com.jobbuddy.backend.modules.chat.util;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent Runtime 代理请求的统一构造器，收敛 IntentService / ChatSseService / AgentFlowService 中重复的
 * messages、budget、metadata 组装逻辑，保证 profile、job_buddy 等公共字段一致。
 */
public final class RuntimeRequestBuilder {

  private final Map<String, Object> payload = new LinkedHashMap<String, Object>();
  private final Map<String, Object> metadata = new LinkedHashMap<String, Object>();

  private RuntimeRequestBuilder(String sessionId, String userMessage, String entrypoint) {
    List<Map<String, Object>> messages = new ArrayList<Map<String, Object>>();
    Map<String, Object> user = new LinkedHashMap<String, Object>();
    user.put("role", "user");
    user.put("content", userMessage == null ? "" : userMessage);
    messages.add(user);
    payload.put("messages", messages);
    payload.put("session_id", sessionId);
    payload.put("stream", false);
    metadata.put("profile", "job-buddy");
    metadata.put("job_buddy", true);
    metadata.put("entrypoint", entrypoint);
  }

  public static RuntimeRequestBuilder forEntrypoint(
      String sessionId, String userMessage, String entrypoint) {
    return new RuntimeRequestBuilder(sessionId, userMessage, entrypoint);
  }

  public RuntimeRequestBuilder budget(
      int maxTurns, int maxToolCalls, int maxFailures, int maxTokens) {
    Map<String, Object> budget = new LinkedHashMap<String, Object>();
    budget.put("max_turns", maxTurns);
    budget.put("max_tool_calls", maxToolCalls);
    budget.put("max_failures", maxFailures);
    budget.put("max_tokens", maxTokens);
    payload.put("budget", budget);
    return this;
  }

  /** 替换默认的单条用户消息，用于需要近期对话完成指代解析和查询重写的入口。 */
  public RuntimeRequestBuilder messages(List<Map<String, Object>> messages) {
    if (messages == null || messages.isEmpty()) return this;
    List<Map<String, Object>> snapshot = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> item : messages) {
      if (item != null) snapshot.add(new LinkedHashMap<String, Object>(item));
    }
    if (!snapshot.isEmpty()) payload.put("messages", snapshot);
    return this;
  }

  public RuntimeRequestBuilder metadata(String key, Object value) {
    metadata.put(key, value);
    return this;
  }

  public Map<String, Object> build() {
    payload.put("metadata", metadata);
    return payload;
  }

  /**
   * 从 runtime 返回结果中提取 job_buddy_directive，优先 tool_results 中的 directive 输出， 其次顶层 directive
   * 字段。返回可变副本，调用方可安全追加字段。
   */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> extractDirective(Map<String, Object> runtimeResult) {
    if (runtimeResult == null || runtimeResult.isEmpty()) return Collections.emptyMap();
    Object toolResults = runtimeResult.get("tool_results");
    if (toolResults instanceof List) {
      for (Object item : (List) toolResults) {
        if (!(item instanceof Map)) continue;
        Object output = ((Map) item).get("output");
        if (output instanceof Map && "job_buddy_directive".equals(((Map) output).get("type"))) {
          return new LinkedHashMap<String, Object>((Map<String, Object>) output);
        }
      }
    }
    Object directive = runtimeResult.get("directive");
    if (directive instanceof Map)
      return new LinkedHashMap<String, Object>((Map<String, Object>) directive);
    return Collections.emptyMap();
  }
}
