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
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Runtime 托管请求工厂：统一构造流式/非流式托管请求体与元数据，并装配个人上下文， 保证消息/预算/元数据在各调用入口保持一致。
 */
class RuntimeManagedRequestFactory {
  private static final Logger log = LoggerFactory.getLogger(RuntimeManagedRequestFactory.class);
  private static final JsonCodec JSON = new JsonCodec();
  private final AgentIntegrationService integrationService;
  private final PersonalContextBuilder personalContextBuilder;
  private final ResumeStorageService resumeStorageService;
  private final JobBuddyProperties properties;

  /**
   * 创建运行时托管请求工厂实例。
   *
   * @param integrationService 集成服务
   * @param personalContextBuilder 个人上下文构建器
   * @param properties 配置属性
   */
  RuntimeManagedRequestFactory(
      AgentIntegrationService integrationService,
      PersonalContextBuilder personalContextBuilder,
      JobBuddyProperties properties) {
    this(integrationService, personalContextBuilder, null, properties);
  }

  /**
   * 创建带轻量简历目录读取能力的运行时托管请求工厂实例。
   *
   * @param integrationService 集成服务
   * @param personalContextBuilder 个人上下文构建器
   * @param resumeStorageService 简历存储服务
   * @param properties 配置属性
   */
  RuntimeManagedRequestFactory(
      AgentIntegrationService integrationService,
      PersonalContextBuilder personalContextBuilder,
      ResumeStorageService resumeStorageService,
      JobBuddyProperties properties) {
    this.integrationService = integrationService;
    this.personalContextBuilder = personalContextBuilder;
    this.resumeStorageService = resumeStorageService;
    this.properties = properties;
  }

  /**
   * 自动装配求职画像、当前简历、求职进展等个人上下文，工作台问答无需用户重复提供。
   *
   * @param message 消息内容
   * @param intent 意图
   * @param state 状态
   * @return 个人上下文
   */
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
   *
   * @param message 消息内容
   * @param intent 意图
   * @param state 状态
   * @return 任务理解上下文
   */
  Map<String, Object> buildUnderstandingContext(
      String message, IntentResult intent, ChatSessionState state) {
    Map<String, Object> compact = new LinkedHashMap<String, Object>();
    String taskType =
        intent == null || intent.getIntent() == null || intent.getIntent().trim().isEmpty()
            ? "general"
            : intent.getIntent().trim();
    compact.put("task_type", taskType);

    if (state != null && state.resumeId != null && !state.resumeId.trim().isEmpty()) {
      compact.put(
          "resume_ref",
          "resume.match".equals(taskType)
              ? Collections.<String, Object>singletonMap("available", true)
              : buildResumeReference(state));
    }

    List<Map<String, Object>> jobs =
        state == null || state.jobs == null
            ? Collections.<Map<String, Object>>emptyList()
            : state.jobs;
    if (!jobs.isEmpty()) {
      List<Map<String, Object>> refs = new java.util.ArrayList<Map<String, Object>>();
      for (Map<String, Object> job : jobs) {
        if (job == null) continue;
        Map<String, Object> ref = new LinkedHashMap<String, Object>();
        copyFirstText(ref, "securityId", job, 220, "securityId", "id", "jobId", "encryptJobId");
        copyFirstText(ref, "jobName", job, 180, "jobName", "job_name", "title", "name");
        copyFirstText(ref, "company", job, 180, "brandName", "companyName", "company");
        ref.put("has_job_description", hasJobDescription(job));
        if (!ref.isEmpty()) refs.add(ref);
        if (refs.size() >= 8) break;
      }
      compact.put("current_job_refs", refs);
    }
    compact.put("current_jobs_count", jobs.size());
    compact.put(
        "summary", understandingSummary(taskType, compact.containsKey("resume_ref"), jobs.size()));
    return compact;
  }

  /**
   * 读取当前简历的有界目录字段。该路径只访问租户范围内的一条本地简历记录，不加载画像、长期记忆或其他业务集合，也不把简历正文放入 Prompt。
   *
   * @param state 会话状态
   * @return 简历目录引用
   */
  private Map<String, Object> buildResumeReference(ChatSessionState state) {
    Map<String, Object> reference = new LinkedHashMap<String, Object>();
    reference.put("available", true);
    if (resumeStorageService == null
        || state == null
        || state.tenantId == null
        || state.userId == null) {
      return reference;
    }
    try {
      ResumeRecord record =
          resumeStorageService.get(state.resumeId.trim(), state.tenantId, state.userId);
      Map<String, Object> parsed =
          record == null || record.getParsed() == null
              ? Collections.<String, Object>emptyMap()
              : record.getParsed();
      copyFirstText(reference, "targetRole", parsed, 180, "targetRole", "target_role");
      copyFirstText(
          reference,
          "current_title",
          parsed,
          180,
          "currentTitle",
          "current_title",
          "currentRole",
          "current_role");
      reference.put("skills_count", collectionSize(parsed.get("skills")));
      reference.put("projects_count", collectionSize(parsed.get("projects")));
      reference.put("experiences_count", collectionSize(parsed.get("experiences")));
    } catch (RuntimeException error) {
      log.warn(
          "读取任务理解简历目录失败 resumeId={} tenantId={} userId={}: {}",
          state.resumeId,
          state.tenantId,
          state.userId,
          error.getMessage());
    }
    return reference;
  }

  /**
   * 生成不触发数据库或下游服务读取的任务理解目录摘要。
   *
   * @param taskType 前置任务类型
   * @param resumeAvailable 是否已选择简历
   * @param currentJobsCount 当前会话岗位数量
   * @return 精简目录摘要
   */
  private String understandingSummary(
      String taskType, boolean resumeAvailable, int currentJobsCount) {
    StringBuilder builder = new StringBuilder();
    builder.append("任务：").append(taskType).append("。");
    if (resumeAvailable) builder.append("已选择当前简历。");
    if (currentJobsCount > 0) builder.append("当前会话岗位 ").append(currentJobsCount).append(" 个。");
    return builder.toString();
  }

  /**
   * 复制首个文本。
   *
   * @param target 待写入的精简上下文
   * @param field 字段名称
   * @param source 源数据
   * @param maxChars 最大字符数
   * @param keys 键列表
   */
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

  /**
   * 写入文本。
   *
   * @param target 待写入的精简上下文
   * @param field 字段名称
   * @param value 输入值
   * @param maxChars 最大字符数
   */
  private void putText(Map<String, Object> target, String field, Object value, int maxChars) {
    if (value == null) return;
    String text = String.valueOf(value).trim().replace('\n', ' ').replace('\r', ' ');
    if (text.isEmpty() || "null".equalsIgnoreCase(text)) return;
    target.put(field, text.length() > maxChars ? text.substring(0, maxChars) : text);
  }

  /**
   * 统计集合元素数量。
   *
   * @param value 输入值
   * @return 集合元素数量
   */
  private int collectionSize(Object value) {
    if (value instanceof java.util.Collection) return ((java.util.Collection<?>) value).size();
    if (value instanceof Map) return ((Map<?, ?>) value).size();
    return value == null || String.valueOf(value).trim().isEmpty() ? 0 : 1;
  }

  /**
   * 判断是否存在岗位描述。
   *
   * @param job 岗位
   * @return 是否存在岗位描述
   */
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

  /**
   * 结合用户画像执行运行时托管答复。
   *
   * @param sessionId 会话标识
   * @param message 消息内容
   * @param profile 画像
   * @param extraMetadata 附加元数据
   * @return 带画像的运行时回答
   */
  Map<String, Object> runRuntimeManagedAnswerWithProfile(
      String sessionId, String message, String profile, Map<String, Object> extraMetadata) {
    RuntimeRunResult result =
        integrationService.runRuntime(
            buildRuntimeManagedRequest(sessionId, message, profile, extraMetadata, false));
    return result == null ? Collections.<String, Object>emptyMap() : result.toMap(JSON);
  }

  /**
   * 构造 Runtime 托管请求体，供流式与非流式入口共用，保证消息/预算/元数据一致。
   *
   * @param sessionId 会话标识
   * @param message 消息内容
   * @param profile 画像
   * @param extraMetadata 附加元数据
   * @param stream 流式
   * @return Runtime 托管请求体，供流式与非流式入口共用，保证消息/预算/元数据一致
   */
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

  /**
   * 构建运行时托管元数据。
   *
   * @param message 消息内容
   * @param state 状态
   * @param directive 运行时指令
   * @param intent 意图
   * @return 运行时托管元数据
   */
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
    metadata.put(
        "attachments",
        state == null || state.attachments == null ? Collections.emptyList() : state.attachments);
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
