package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.compactMatchDetail;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.fallbackGeneralResumeMatchAnswer;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.manualTargetJobs;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.resumeMatchSummary;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.toolStatus;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.firstPresent;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.stringValue;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
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
  private final AgentIntegrationService integrationService;
  private final RuntimeManagedRequestFactory requestFactory;

  ResumeFlowHandler(
      ChatSseEventSender sender,
      CurrentResumeLoader resumeLoader,
      ResumeStorageService resumeStorageService,
      JobRuntimeService jobRuntimeService,
      AgentIntegrationService integrationService,
      RuntimeManagedRequestFactory requestFactory) {
    this.sender = sender;
    this.resumeLoader = resumeLoader;
    this.resumeStorageService = resumeStorageService;
    this.jobRuntimeService = jobRuntimeService;
    this.integrationService = integrationService;
    this.requestFactory = requestFactory;
  }

  void handleResumeMatch(
      SseEmitter emitter,
      String sessionId,
      ChatSessionState state,
      IntentResult intent,
      String rawMessage)
      throws IOException {
    ResumeRecord resume = resumeLoader.loadCurrentResume(state);
    if (resume == null) {
      sender.sendAssistant(emitter, sessionId, state, "请先选择或上传 PDF 简历，再分析岗位匹配度。");
      return;
    }
    String targetDescription =
        stringValue(
            firstPresent(intent.getSlots(), "target_job_description", "jd", "job_description"));
    String targetRole =
        stringValue(firstPresent(intent.getSlots(), "role", "target_role"), rawMessage);
    List<Map<String, Object>> jobs =
        state.jobs == null || state.jobs.isEmpty()
            ? manualTargetJobs(targetRole, targetDescription, intent.getSlots())
            : state.jobs;
    if (jobs.isEmpty()) {
      Map<String, Object> detail = new LinkedHashMap<String, Object>();
      detail.put("basis", "general_role_knowledge");
      detail.put("targetRole", targetRole);
      detail.put("slots", intent.getSlots());
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus("resume_match", "通用岗位分析", "running", "缺少目标 JD 或岗位列表，将基于通用岗位要求做参考分析。", detail));
      Map<String, Object> general =
          streamGeneralResumeMatchAnswer(
              emitter,
              sessionId,
              rawMessage,
              resume,
              targetRole,
              targetDescription,
              intent.getSlots());
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
      return;
    }
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "resume_match", "简历匹配分析", "running", "正在基于真实岗位或用户 JD 评估简历匹配。", intent.getSlots()));
    Map<String, Object> match = jobRuntimeService.matchResume(resume, jobs, sessionId);
    if (!match.containsKey("target"))
      match.put("target", targetDescription.isEmpty() ? targetRole : targetDescription);
    // 匹配结果写入内存状态，随本轮助手消息一并异步落库，避免单独的同步写阻塞 SSE。
    state.resumeMatch = match;
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus("resume_match", "简历匹配完成", "success", "简历匹配已完成。", compactMatchDetail(match)));
    sender.send(emitter, "resume_match", match);
    sender.sendAssistant(
        emitter,
        sessionId,
        state,
        resumeMatchSummary(match),
        Collections.<String, Object>singletonMap("resumeMatch", match));
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
      Map<String, Object> request =
          requestFactory.buildRuntimeManagedRequest(
              sessionId, prompt, "default", extraMetadata, true);
      Map<String, Object> runtimeResult =
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
