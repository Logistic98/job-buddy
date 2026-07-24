package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.SELECTED_JOB_CONTEXT_KEY;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.compactMatchDetail;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.fallbackGeneralResumeMatchAnswer;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.manualTargetJobs;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.resumeMatchSummary;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.selectedJobLabel;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.toolStatus;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.firstPresent;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.stringValue;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.dto.response.ChatMessageResponse;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunResult;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 简历相关链路：简历匹配分析（真实岗位/用户 JD/通用岗位画像三种基准）与简历解析。 */
class ResumeFlowHandler {
  private static final JsonCodec JSON = new JsonCodec();
  private final ChatSseEventSender sender;
  private final CurrentResumeLoader resumeLoader;
  private final ResumeStorageService resumeStorageService;
  private final JobRuntimeService jobRuntimeService;
  private final ChatSessionStore sessionStore;
  private final AgentIntegrationService integrationService;
  private final RuntimeManagedRequestFactory requestFactory;
  private final SelectedJobContextResolver contextResolver;

  ResumeFlowHandler(
      ChatSseEventSender sender,
      CurrentResumeLoader resumeLoader,
      ResumeStorageService resumeStorageService,
      JobRuntimeService jobRuntimeService,
      ChatSessionStore sessionStore,
      AgentIntegrationService integrationService,
      RuntimeManagedRequestFactory requestFactory,
      SelectedJobContextResolver contextResolver) {
    this.sender = sender;
    this.resumeLoader = resumeLoader;
    this.resumeStorageService = resumeStorageService;
    this.jobRuntimeService = jobRuntimeService;
    this.sessionStore = sessionStore;
    this.integrationService = integrationService;
    this.requestFactory = requestFactory;
    this.contextResolver = contextResolver;
  }

  void handleResumeMatch(
      SseEmitter emitter,
      String sessionId,
      ChatSessionState state,
      IntentResult intent,
      String rawMessage,
      Map<String, Object> directive)
      throws IOException {
    ResumeRecord resume = resumeLoader.loadCurrentResume(state);
    if (resume == null) {
      sender.sendAssistant(emitter, sessionId, state, "请先选择或上传 PDF 简历，再分析岗位匹配度。");
      return;
    }
    Map<String, Object> slots =
        intent == null || intent.getSlots() == null
            ? Collections.<String, Object>emptyMap()
            : intent.getSlots();
    String targetDescription =
        stringValue(firstPresent(slots, "target_job_description", "jd", "job_description"));
    String explicitTargetRole = stringValue(firstPresent(slots, "role", "target_role"));
    String targetRole = stringValue(explicitTargetRole, rawMessage);
    boolean reusePreviousSlots = shouldReusePreviousSlots(directive);
    Map<String, Object> selectedJob = selectedJobFromState(state);
    boolean reuseSelectedJob =
        shouldReuseSelectedJob(
            selectedJob, rawMessage, explicitTargetRole, targetDescription, reusePreviousSlots);

    List<Map<String, Object>> jobs;
    Map<String, Object> selectedJobContext = Collections.emptyMap();
    if (reuseSelectedJob) {
      SelectedJobContextResolver.Resolution resolution =
          contextResolver.resolve(selectedJob, state == null ? null : state.jobs);
      selectedJobContext = resolution.getJob();
      rememberSelectedJob(state, selectedJobContext);
      if (!contextResolver.hasSufficientDescription(selectedJobContext)) {
        sendSelectedJobEvidenceMissing(
            emitter, sessionId, state, resume, selectedJobContext, resolution.getWarning(), true);
        return;
      }
      jobs = Collections.singletonList(selectedJobContext);
    } else {
      jobs =
          resolveTargetJobs(
              state, rawMessage, explicitTargetRole, targetRole, targetDescription, slots, false);
    }

    if (jobs.isEmpty()) {
      handleGeneralResumeMatch(
          emitter, sessionId, state, rawMessage, resume, targetRole, targetDescription, slots);
      return;
    }
    executeEvidenceBasedMatch(
        emitter,
        sessionId,
        state,
        resume,
        jobs,
        targetDescription.isEmpty() ? targetRole : targetDescription,
        selectedJobContext,
        reuseSelectedJob);
  }

  void handleSelectedJobMatch(
      SseEmitter emitter,
      String sessionId,
      ChatSessionState state,
      String rawMessage,
      Map<String, Object> selectedJobContext)
      throws IOException {
    ResumeRecord resume = resumeLoader.loadCurrentResume(state);
    if (resume == null) {
      sender.sendAssistant(
          emitter,
          sessionId,
          state,
          "请先选择或上传 PDF 简历，再分析此岗位与简历的匹配度。",
          Collections.<String, Object>singletonMap("selectedJob", selectedJobContext));
      return;
    }
    executeEvidenceBasedMatch(
        emitter,
        sessionId,
        state,
        resume,
        Collections.singletonList(selectedJobContext),
        selectedJobLabel(selectedJobContext),
        selectedJobContext,
        false);
  }

  private void handleGeneralResumeMatch(
      SseEmitter emitter,
      String sessionId,
      ChatSessionState state,
      String rawMessage,
      ResumeRecord resume,
      String targetRole,
      String targetDescription,
      Map<String, Object> slots)
      throws IOException {
    Map<String, Object> detail = new LinkedHashMap<String, Object>();
    detail.put("basis", "general_role_knowledge");
    detail.put("targetRole", targetRole);
    detail.put("slots", slots);
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus("resume_match", "通用岗位分析", "running", "缺少目标 JD 或岗位列表，将基于通用岗位要求做参考分析。", detail));
    Map<String, Object> general =
        streamGeneralResumeMatchAnswer(
            emitter, sessionId, rawMessage, resume, targetRole, targetDescription, slots);
    String answer = stringValue(general.get("answer"));
    if (answer.isEmpty()) answer = fallbackGeneralResumeMatchAnswer(resume, targetRole);
    Map<String, Object> metadata = new LinkedHashMap<String, Object>();
    metadata.put("resumeMatch", general);
    metadata.put("matchBasis", "general_role_knowledge");
    Object assistantId = general.remove("assistantId");
    if (assistantId != null) metadata.put("assistantId", assistantId);
    Object reasoning = general.remove("reasoning");
    if (reasoning != null && !stringValue(reasoning).isEmpty())
      metadata.put("reasoning", reasoning);
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus("resume_match", "通用岗位分析完成", "success", "参考分析已完成。", metadata));
    sender.sendAssistant(emitter, sessionId, state, answer, metadata);
  }

  private void executeEvidenceBasedMatch(
      SseEmitter emitter,
      String sessionId,
      ChatSessionState state,
      ResumeRecord resume,
      List<Map<String, Object>> jobs,
      String target,
      Map<String, Object> selectedJobContext,
      boolean reusedPreviousJob)
      throws IOException {
    Map<String, Object> runningDetail = new LinkedHashMap<String, Object>();
    runningDetail.put("target", target);
    runningDetail.put("resumeId", resume.getResumeId());
    if (selectedJobContext != null && !selectedJobContext.isEmpty()) {
      runningDetail.put("selectedJob", selectedJobContext);
      runningDetail.put(
          "contextSource", reusedPreviousJob ? "previous_selected_job" : "selected_job");
    }
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "resume_match",
            reusedPreviousJob ? "复评上一轮岗位" : "简历匹配分析",
            "running",
            reusedPreviousJob ? "已解析当前追问，正在使用当前简历重新评估上一轮选中岗位。" : "正在基于完整 JD 与当前简历执行证据型匹配。",
            runningDetail));
    Map<String, Object> match =
        jobRuntimeService.matchResumeSections(
            resume,
            jobs,
            sessionId,
            java.util.Arrays.asList(
                "score",
                "score_confidence",
                "recommendation",
                "reasoning",
                "evidence",
                "hits",
                "gaps",
                "limitations"));
    if (!match.containsKey("target")) match.put("target", target);
    state.resumeMatch = match;
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "resume_match",
            "简历匹配完成",
            "success",
            reusedPreviousJob ? "已使用当前简历复评上一轮岗位。" : "简历匹配已完成。",
            compactMatchDetail(match)));
    sender.send(emitter, "resume_match", match);

    Map<String, Object> metadata = new LinkedHashMap<String, Object>();
    metadata.put("resumeMatch", match);
    metadata.put("resumeId", resume.getResumeId());
    metadata.put("resumeName", resume.getOriginalName());
    if (selectedJobContext != null && !selectedJobContext.isEmpty()) {
      metadata.put("selectedJob", selectedJobContext);
      metadata.put("contextSource", reusedPreviousJob ? "previous_selected_job" : "selected_job");
    }
    sender.sendAssistant(
        emitter,
        sessionId,
        state,
        resumeMatchSummary(match, resume.getOriginalName(), selectedJobContext, reusedPreviousJob),
        metadata);
  }

  private void sendSelectedJobEvidenceMissing(
      SseEmitter emitter,
      String sessionId,
      ChatSessionState state,
      ResumeRecord resume,
      Map<String, Object> selectedJob,
      String warning,
      boolean reusedPreviousJob)
      throws IOException {
    Map<String, Object> detail = new LinkedHashMap<String, Object>();
    detail.put("selectedJob", selectedJob);
    detail.put("resumeId", resume.getResumeId());
    detail.put("contextSource", reusedPreviousJob ? "previous_selected_job" : "selected_job");
    detail.put("warning", warning);
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus("resume_match", "岗位证据不足", "error", "已正确复用上一轮岗位，但未取得完整 JD，因此未生成伪精确评分。", detail));
    Map<String, Object> metadata = new LinkedHashMap<String, Object>();
    metadata.put("selectedJob", selectedJob);
    metadata.put("resumeId", resume.getResumeId());
    metadata.put("resumeName", resume.getOriginalName());
    metadata.put("matchBasis", "previous_selected_job_without_jd");
    metadata.put("contextResolution", detail);
    String reason = warning == null || warning.trim().isEmpty() ? "岗位卡片缺少完整 JD" : warning.trim();
    sender.sendAssistant(
        emitter,
        sessionId,
        state,
        "已理解你的意思：使用当前简历「"
            + stringValue(resume.getOriginalName(), "当前选择的简历")
            + "」重新评估上一轮岗位「"
            + selectedJobLabel(selectedJob)
            + "」。本轮没有给出精确分数，不是因为丢失了对话上下文，而是因为"
            + reason
            + "。已保留该岗位引用；补充或重新加载完整 JD 后，可直接继续复评。",
        metadata);
  }

  private void rememberSelectedJob(ChatSessionState state, Map<String, Object> selectedJobContext) {
    if (state == null) return;
    state.lastSlots =
        state.lastSlots == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(state.lastSlots);
    state.lastSlots.put(SELECTED_JOB_CONTEXT_KEY, selectedJobContext);
  }

  /** 通用简历匹配分析：流式优先逐字下发，流式无产出时回退非流式托管调用，最终回退本地模板。 */
  private Map<String, Object> streamGeneralResumeMatchAnswer(
      final SseEmitter emitter,
      final String sessionId,
      String rawMessage,
      ResumeRecord resume,
      String targetRole,
      String targetDescription,
      Map<String, Object> slots) {
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    Map<String, Object> resumeSummary = JSON.toMap(resumeStorageService.summarize(resume));
    String role = stringValue(targetRole, stringValue(targetDescription, "目标岗位"));
    String prompt =
        "请基于通用岗位画像，而不是具体 JD，对当前简历与目标方向做参考匹配分析。\n"
            + "目标方向："
            + role
            + "\n"
            + "用户原始问题："
            + stringValue(rawMessage)
            + "\n"
            + "已知槽位："
            + String.valueOf(slots == null ? Collections.emptyMap() : slots)
            + "\n"
            + "简历摘要："
            + String.valueOf(resumeSummary)
            + "\n\n"
            + "要求：1）基于通用岗位画像给出参考判断；2）不输出精确匹配分；3）输出匹配结论、主要优势、明显短板、面试准备建议和简历修改建议；"
            + "4）简历摘要信息不足时，说明需要补充的信息。";
    // runtime_execute 让 Runtime 跳过重复任务理解直达流式合成，与托管问答路径保持一致的首字延迟。
    Map<String, Object> extraMetadata = new LinkedHashMap<String, Object>();
    extraMetadata.put("runtime_execute", true);
    extraMetadata.put("entrypoint", "resume.match.general");
    final String assistantId =
        "assistant_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    final StringBuilder buffer = new StringBuilder();
    final StringBuilder reasoningBuffer = new StringBuilder();
    try {
      RuntimeRunRequest request =
          requestFactory.buildRuntimeManagedRequest(
              sessionId, prompt, "default", extraMetadata, true);
      RuntimeRunResult streamResult =
          integrationService.runRuntimeStream(
              request,
              new java.util.function.Consumer<String>() {
                @Override
                public void accept(String piece) {
                  if (piece == null || piece.isEmpty()) return;
                  buffer.append(piece);
                  try {
                    sender.sendMessageDelta(emitter, sessionId, assistantId, piece);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                }
              },
              new java.util.function.Consumer<String>() {
                @Override
                public void accept(String piece) {
                  if (piece == null || piece.isEmpty()) return;
                  reasoningBuffer.append(piece);
                  try {
                    sender.sendReasoningDelta(emitter, sessionId, assistantId, piece);
                  } catch (IOException e) {
                    throw new RuntimeException(e);
                  }
                }
              });
      Map<String, Object> runtimeResult =
          streamResult == null ? Collections.<String, Object>emptyMap() : streamResult.toMap(JSON);
      String answer = stringValue(firstPresent(runtimeResult, "answer", "final_answer"));
      if (answer.isEmpty()) answer = buffer.toString().trim();
      String reasoning = stringValue(runtimeResult.get("reasoning"));
      if (reasoning.isEmpty()) reasoning = reasoningBuffer.toString().trim();
      if (answer.isEmpty()) {
        // 流式无任何产出时回退非流式托管调用，避免偶发流式中断直接对用户报错。
        Map<String, Object> fallback =
            requestFactory.runRuntimeManagedAnswerWithProfile(
                sessionId, prompt, "default", Collections.<String, Object>emptyMap());
        answer = stringValue(firstPresent(fallback, "answer", "final_answer"));
        if (!answer.isEmpty()) runtimeResult = fallback;
      }
      if (!answer.isEmpty()) {
        response.put("answer", answer);
        if (!reasoning.isEmpty()) response.put("reasoning", reasoning);
        response.put("assistantId", assistantId);
        response.put("runtimeResult", runtimeResult);
        response.put("target", role);
        response.put("resumeSummary", resumeSummary);
        response.put("basis", "general_role_knowledge");
        return response;
      }
    } catch (RuntimeException ignored) {
      response.put("runtime_error", ignored.getMessage());
    }
    response.put("answer", fallbackGeneralResumeMatchAnswer(resume, role));
    response.put("target", role);
    response.put("resumeSummary", resumeSummary);
    response.put("basis", "general_role_knowledge_fallback");
    return response;
  }

  List<Map<String, Object>> resolveTargetJobs(
      ChatSessionState state,
      String rawMessage,
      String explicitTargetRole,
      String targetRole,
      String targetDescription,
      Map<String, Object> slots,
      boolean reusePreviousSlots) {
    Map<String, Object> selectedJob = selectedJobFromState(state);
    if (shouldReuseSelectedJob(
        selectedJob, rawMessage, explicitTargetRole, targetDescription, reusePreviousSlots))
      return Collections.singletonList(selectedJob);
    List<Map<String, Object>> manualJobs = manualTargetJobs(targetRole, targetDescription, slots);
    if (!manualJobs.isEmpty()) return manualJobs;
    if (!stringValue(explicitTargetRole).isEmpty()
        && hasExplicitNewTarget(rawMessage, explicitTargetRole, targetDescription)) {
      return Collections.emptyList();
    }
    if (state != null && state.jobs != null && !state.jobs.isEmpty()) return state.jobs;
    return Collections.emptyList();
  }

  static boolean shouldReuseSelectedJob(
      Map<String, Object> selectedJob,
      String rawMessage,
      String explicitTargetRole,
      String targetDescription,
      boolean reusePreviousSlots) {
    if (selectedJob == null || selectedJob.isEmpty()) return false;
    if (hasExplicitNewTarget(rawMessage, explicitTargetRole, targetDescription)) return false;
    return reusePreviousSlots
        || (stringValue(targetDescription).isEmpty()
            && (stringValue(explicitTargetRole).isEmpty()
                || isSelectedJobResumeFollowUp(rawMessage)));
  }

  static boolean hasExplicitNewTarget(
      String rawMessage, String explicitTargetRole, String targetDescription) {
    String message = stringValue(rawMessage).trim();
    if (message.isEmpty()) return false;
    if (message.contains("这些岗位")
        || message.contains("这批岗位")
        || message.contains("当前岗位列表")
        || message.contains("上述岗位")
        || message.contains("前面这些岗位")) return false;
    if (message.contains("另一个岗位")
        || message.contains("另一个职位")
        || message.contains("新岗位")
        || message.contains("新的岗位")
        || message.contains("新职位")
        || message.contains("新的职位")
        || message.contains("换个岗位")) return true;

    String normalizedMessage = message.replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    String normalizedRole =
        stringValue(explicitTargetRole).replaceAll("\\s+", "").toLowerCase(java.util.Locale.ROOT);
    if (normalizedRole.length() >= 2 && normalizedMessage.contains(normalizedRole)) return true;
    if (!stringValue(targetDescription).isEmpty() && message.length() >= 30) return true;

    String withoutReferences =
        message
            .replace("这个岗位", "")
            .replace("该岗位", "")
            .replace("上一轮岗位", "")
            .replace("上一个岗位", "")
            .replace("刚才的岗位", "")
            .replace("刚才那个岗位", "");
    return withoutReferences.contains("岗位")
        || withoutReferences.contains("职位")
        || withoutReferences.contains("JD")
        || withoutReferences.contains("jd")
        || withoutReferences.contains("工程师");
  }

  static boolean isSelectedJobResumeFollowUp(String rawMessage) {
    String message = stringValue(rawMessage);
    if (!message.contains("简历")) return false;
    return message.contains("这个")
        || message.contains("这份")
        || message.contains("现在")
        || message.contains("当前")
        || message.contains("换");
  }

  @SuppressWarnings("unchecked")
  static boolean shouldReusePreviousSlots(Map<String, Object> directive) {
    if (directive == null) return false;
    Object taskValue = directive.get("task");
    if (!(taskValue instanceof Map)) return false;
    Object metadataValue = ((Map<String, Object>) taskValue).get("metadata");
    if (!(metadataValue instanceof Map)) return false;
    Object reuseValue = ((Map<String, Object>) metadataValue).get("reuse_previous_slots");
    return Boolean.TRUE.equals(reuseValue) || "true".equalsIgnoreCase(stringValue(reuseValue));
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> selectedJobFromState(ChatSessionState state) {
    if (state == null) return Collections.emptyMap();
    Object selectedJob =
        state.lastSlots == null ? null : state.lastSlots.get(SELECTED_JOB_CONTEXT_KEY);
    if (selectedJob instanceof Map) {
      return new LinkedHashMap<String, Object>((Map<String, Object>) selectedJob);
    }
    if (state.tenantId == null || state.userId == null || state.sessionId == null) {
      return Collections.emptyMap();
    }
    // 兼容已有会话及槽位被历史版本覆盖的情况：具体岗位分析的助手消息已持久化 selectedJob 元数据，
    // 从最近一条向前恢复即可，不需要数据库结构变更，也不会把整批岗位误当成选中岗位。
    List<ChatMessageResponse> messages =
        sessionStore.listMessages(state.tenantId, state.userId, state.sessionId);
    for (int index = messages.size() - 1; index >= 0; index--) {
      Map<String, Object> metadata = JSON.toMap(messages.get(index).getMetadata());
      Object persistedSelectedJob = metadata.get("selectedJob");
      if (persistedSelectedJob instanceof Map) {
        return new LinkedHashMap<String, Object>((Map<String, Object>) persistedSelectedJob);
      }
    }
    return Collections.emptyMap();
  }

  void handleResumeAnalyze(SseEmitter emitter, String sessionId, ChatSessionState state)
      throws IOException {
    ResumeRecord resume = resumeLoader.loadCurrentResume(state);
    if (resume == null) {
      sender.sendAssistant(emitter, sessionId, state, "请先选择或上传 PDF 简历，再分析简历。");
      return;
    }
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus("resume_analyze", "解析当前简历", "running", "正在解析当前简历。", resume.getResumeId()));
    ResumeRecord analyzed = resumeStorageService.analyzeSync(resume.getResumeId(), sessionId);
    Map<String, Object> summary = JSON.toMap(resumeStorageService.summarize(analyzed));
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus("resume_analyze", "简历解析完成", "success", "简历结构化信息已读取。", summary));
    sender.sendAssistant(
        emitter,
        sessionId,
        state,
        "已解析当前简历，可继续生成分析建议。",
        Collections.<String, Object>singletonMap("resumeSummary", summary));
  }
}
