package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunResult;
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
  private static final JsonCodec JSON = new JsonCodec();
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

  /**
   * 任务理解只需要判断“有哪些上下文以及当前引用对象是谁”，不需要读取完整项目、经历和 JD 正文。 完整个人上下文仍保留给后续执行/答案合成，理解阶段改用该高信号目录以降低噪声和 token
   * 开销。
   */
  @SuppressWarnings("unchecked")
  Map<String, Object> buildUnderstandingContext(
      String message, IntentResult intent, ChatSessionState state) {
    Map<String, Object> full = buildPersonalContext(message, intent, state);
    if (full.isEmpty()) return Collections.emptyMap();

    Map<String, Object> compact = new LinkedHashMap<String, Object>();
    putText(compact, "task_type", full.get("task_type"), 80);
    putText(compact, "summary", full.get("summary"), 500);
    Object sources = full.get("sources");
    if (sources instanceof List)
      compact.put("sources", new java.util.ArrayList<Object>((List<?>) sources));

    Object resumeValue = full.get("resume_summary");
    if (resumeValue instanceof Map && !((Map<?, ?>) resumeValue).isEmpty()) {
      Map<String, Object> resume = (Map<String, Object>) resumeValue;
      Map<String, Object> reference = new LinkedHashMap<String, Object>();
      reference.put("available", true);
      for (String key :
          new String[] {"name", "targetRole", "years_experience", "work_years", "current_title"}) {
        putText(reference, key, resume.get(key), 180);
      }
      reference.put("skills_count", collectionSize(resume.get("skills")));
      reference.put("projects_count", collectionSize(resume.get("projects")));
      reference.put("experiences_count", collectionSize(resume.get("experiences")));
      compact.put("resume_ref", reference);
    }

    Object jobsValue = full.get("current_jobs");
    if (jobsValue instanceof List) {
      List<Map<String, Object>> refs = new java.util.ArrayList<Map<String, Object>>();
      for (Object item : (List<?>) jobsValue) {
        if (!(item instanceof Map)) continue;
        Map<String, Object> job = (Map<String, Object>) item;
        Map<String, Object> ref = new LinkedHashMap<String, Object>();
        copyFirstText(ref, "securityId", job, 220, "securityId", "id", "jobId", "encryptJobId");
        copyFirstText(ref, "jobName", job, 180, "jobName", "job_name", "title", "name");
        copyFirstText(ref, "company", job, 180, "brandName", "companyName", "company");
        ref.put("has_job_description", hasJobDescription(job));
        if (!ref.isEmpty()) refs.add(ref);
        if (refs.size() >= 8) break;
      }
      compact.put("current_job_refs", refs);
      compact.put("current_jobs_count", ((List<?>) jobsValue).size());
    }
    compact.put("favorite_jobs_count", collectionSize(full.get("favorite_jobs")));
    compact.put("journey_records_count", collectionSize(full.get("journey_records")));
    compact.put("long_term_memory_count", collectionSize(full.get("long_term_memory")));
    return compact;
  }

  private void copyFirstText(
      Map<String, Object> target,
      String field,
      Map<String, Object> source,
      int maxChars,
      String... keys) {
    for (String key : keys) {
      Object value = source.get(key);
      if (value == null || String.valueOf(value).trim().isEmpty()) continue;
      putText(target, field, value, maxChars);
      return;
    }
  }

  private void putText(Map<String, Object> target, String field, Object value, int maxChars) {
    if (value == null) return;
    String text = String.valueOf(value).trim().replace('\n', ' ').replace('\r', ' ');
    if (text.isEmpty() || "null".equalsIgnoreCase(text)) return;
    target.put(field, text.length() > maxChars ? text.substring(0, maxChars) : text);
  }

  private int collectionSize(Object value) {
    if (value instanceof java.util.Collection) return ((java.util.Collection<?>) value).size();
    if (value instanceof Map) return ((Map<?, ?>) value).size();
    return value == null || String.valueOf(value).trim().isEmpty() ? 0 : 1;
  }

  private boolean hasJobDescription(Map<String, Object> job) {
    for (String key :
        new String[] {
          "jobDescription",
          "description",
          "postDescription",
          "jobDesc",
          "jobSecText",
          "detailText",
          "jobRequire",
          "jobContent"
        }) {
      Object value = job.get(key);
      if (value != null && String.valueOf(value).trim().length() >= 30) return true;
    }
    return false;
  }

  Map<String, Object> runRuntimeManagedAnswerWithProfile(
      String sessionId, String message, String profile, Map<String, Object> extraMetadata) {
    RuntimeRunResult result =
        integrationService.runRuntime(
            buildRuntimeManagedRequest(sessionId, message, profile, extraMetadata, false));
    return result == null ? Collections.<String, Object>emptyMap() : result.toMap(JSON);
  }

  /** 构造 Runtime 托管请求体，供流式与非流式入口共用，保证消息/预算/元数据一致。 */
  RuntimeRunRequest buildRuntimeManagedRequest(
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
    budget.put("max_tokens", properties.getRuntimeMaxTokens());
    request.put("budget", budget);
    Map<String, Object> metadata = new LinkedHashMap<String, Object>();
    metadata.put("profile", profile);
    if (extraMetadata != null) metadata.putAll(extraMetadata);
    request.put("metadata", metadata);
    return RuntimeRunRequest.fromPayload(request, JSON);
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
    Map<String, Object> upstreamDirective =
        directive == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(directive);
    Object upstreamResult = upstreamDirective.remove("runtime_result");
    if (upstreamResult instanceof Map) {
      Map<?, ?> result = (Map<?, ?>) upstreamResult;
      metadata.put("upstream_run_id", result.get("run_id"));
      metadata.put("upstream_trace_id", result.get("trace_id"));
    }
    metadata.put("upstream_directive", upstreamDirective);
    return metadata;
  }
}
