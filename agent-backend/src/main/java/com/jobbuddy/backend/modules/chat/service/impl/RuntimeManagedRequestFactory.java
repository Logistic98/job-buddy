package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.prompt.model.PersonalContext;
import com.jobbuddy.backend.modules.prompt.service.PersonalContextBuilder;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Runtime 托管请求工厂：统一构造流式/非流式托管请求体与元数据，并装配个人上下文， 保证消息/预算/元数据在各调用入口保持一致。 */
class RuntimeManagedRequestFactory {
  private static final Logger log = LoggerFactory.getLogger(RuntimeManagedRequestFactory.class);
  private final AgentIntegrationService integrationService;
  private final PersonalContextBuilder personalContextBuilder;
  private final JobBuddyProperties properties;

  RuntimeManagedRequestFactory(
      AgentIntegrationService integrationService,
      PersonalContextBuilder personalContextBuilder,
      JobBuddyProperties properties) {
    this.integrationService = integrationService;
    this.personalContextBuilder = personalContextBuilder;
    this.properties = properties;
  }

  /** 自动装配求职画像、当前简历、求职进展等个人上下文，工作台问答无需用户重复提供。 */
  Map<String, Object> buildPersonalContext(
      String message, IntentResult intent, ChatSessionState state) {
    try {
      if (state == null || state.tenantId == null || state.userId == null) {
        throw new IllegalArgumentException("聊天会话缺少 tenantId/userId，拒绝装配个人上下文");
      }
      PersonalContext context =
          personalContextBuilder.build(state.tenantId, state.userId, message, intent, state);
      return context == null || context.isEmpty()
          ? Collections.<String, Object>emptyMap()
          : context.toMap();
    } catch (Exception e) {
      // 个人上下文装配失败时降级为空上下文，不阻断问答，但留痕便于定位画像缺失。
      log.warn("装配个人上下文失败", e);
      return Collections.emptyMap();
    }
  }

  Map<String, Object> runRuntimeManagedAnswerWithProfile(
      String sessionId, String message, String profile, Map<String, Object> extraMetadata) {
    return integrationService.runRuntime(
        buildRuntimeManagedRequest(sessionId, message, profile, extraMetadata, false));
  }

  /** 构造 Runtime 托管请求体，供流式与非流式入口共用，保证消息/预算/元数据一致。 */
  Map<String, Object> buildRuntimeManagedRequest(
      String sessionId,
      String message,
      String profile,
      Map<String, Object> extraMetadata,
      boolean stream) {
    Map<String, Object> request = new LinkedHashMap<String, Object>();
    List<Map<String, Object>> messages = new java.util.ArrayList<Map<String, Object>>();
    Map<String, Object> user = new LinkedHashMap<String, Object>();
    user.put("role", "user");
    user.put("content", message == null ? "" : message);
    messages.add(user);
    request.put("messages", messages);
    request.put("session_id", sessionId);
    request.put("stream", stream);
    Map<String, Object> budget = new LinkedHashMap<String, Object>();
    budget.put("max_turns", properties.getRuntimeMaxTurns());
    budget.put("max_tool_calls", properties.getRuntimeMaxToolCalls());
    budget.put("max_failures", properties.getRuntimeMaxFailures());
    request.put("budget", budget);
    Map<String, Object> metadata = new LinkedHashMap<String, Object>();
    metadata.put("profile", profile);
    if (extraMetadata != null) metadata.putAll(extraMetadata);
    request.put("metadata", metadata);
    return request;
  }

  Map<String, Object> runtimeManagedMetadata(
      String message, ChatSessionState state, Map<String, Object> directive, IntentResult intent) {
    Map<String, Object> metadata = new LinkedHashMap<String, Object>();
    metadata.put("job_buddy", true);
    metadata.put("entrypoint", "chat.ask");
    metadata.put("runtime_execute", true);
    metadata.put("tenant_id", state == null ? null : state.tenantId);
    metadata.put("user_id", state == null ? null : state.userId);
    metadata.put("operator_id", state == null ? null : state.userId);
    metadata.put("resume_id", state == null ? null : state.resumeId);
    metadata.put(
        "previous_slots",
        state == null || state.lastSlots == null ? Collections.emptyMap() : state.lastSlots);
    metadata.put("current_jobs_count", state == null || state.jobs == null ? 0 : state.jobs.size());
    metadata.put("personal_context", buildPersonalContext(message, intent, state));
    metadata.put("upstream_directive", directive == null ? Collections.emptyMap() : directive);
    return metadata;
  }
}
