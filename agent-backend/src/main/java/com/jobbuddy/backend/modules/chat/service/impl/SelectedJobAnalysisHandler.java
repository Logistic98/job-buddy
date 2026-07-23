package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.SELECTED_JOB_CONTEXT_KEY;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.selectedJobLabel;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.toolStatus;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 选中岗位分析入口：先把列表卡片补全为可复用岗位上下文，再委托简历匹配链路执行统一的证据型分析。 */
class SelectedJobAnalysisHandler {
  private final ChatSseEventSender sender;
  private final SelectedJobContextResolver contextResolver;
  private final ResumeFlowHandler resumeFlowHandler;

  SelectedJobAnalysisHandler(
      ChatSseEventSender sender,
      SelectedJobContextResolver contextResolver,
      ResumeFlowHandler resumeFlowHandler) {
    this.sender = sender;
    this.contextResolver = contextResolver;
    this.resumeFlowHandler = resumeFlowHandler;
  }

  void handle(
      SseEmitter emitter,
      String sessionId,
      ChatSessionState state,
      String rawMessage,
      Map<String, Object> selectedJob)
      throws IOException {
    Map<String, Object> initialContext = contextResolver.compact(selectedJob);
    Map<String, Object> startDetail = new LinkedHashMap<String, Object>();
    startDetail.put("job", initialContext);
    startDetail.put("hasJobDescription", contextResolver.hasSufficientDescription(initialContext));
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "selected_job_context", "读取选中岗位上下文", "running", "正在确认当前岗位并按需加载完整职位描述。", startDetail));

    SelectedJobContextResolver.Resolution resolution =
        contextResolver.resolve(selectedJob, state == null ? null : state.jobs);
    Map<String, Object> selectedJobContext = resolution.getJob();
    rememberSelectedJob(state, selectedJobContext);

    if (!contextResolver.hasSufficientDescription(selectedJobContext)) {
      Map<String, Object> detail = new LinkedHashMap<String, Object>();
      detail.put("job", selectedJobContext);
      detail.put("detailLoaded", resolution.isDetailLoaded());
      detail.put("warning", resolution.getWarning());
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus(
              "selected_job_context",
              "岗位证据不足",
              "error",
              "已识别选中岗位，但没有取得完整 JD，因此不会仅凭岗位名称生成精确评分。",
              detail));
      Map<String, Object> metadata = new LinkedHashMap<String, Object>();
      metadata.put("selectedJob", selectedJobContext);
      metadata.put("matchBasis", "selected_job_without_jd");
      metadata.put("contextResolution", detail);
      sender.sendAssistant(
          emitter,
          sessionId,
          state,
          "已定位到选中岗位「"
              + selectedJobLabel(selectedJobContext)
              + "」，但当前岗位卡片没有完整 JD"
              + warningSuffix(resolution.getWarning())
              + "。为避免生成看似精确但没有证据的评分，本次不会仅凭岗位名称进行推测。请重新点击“分析此岗位”加载职位描述，或打开 Boss 原岗位确认详情后再试。",
          metadata);
      return;
    }

    Map<String, Object> successDetail = new LinkedHashMap<String, Object>();
    successDetail.put("job", selectedJobContext);
    successDetail.put("detailLoaded", resolution.isDetailLoaded());
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "selected_job_context",
            "岗位上下文已确认",
            "success",
            resolution.isDetailLoaded() ? "已加载完整 JD，并保存为后续追问上下文。" : "已保存选中岗位与完整 JD。",
            successDetail));
    resumeFlowHandler.handleSelectedJobMatch(
        emitter, sessionId, state, rawMessage, selectedJobContext);
  }

  private void rememberSelectedJob(ChatSessionState state, Map<String, Object> selectedJobContext) {
    if (state == null) return;
    state.lastSlots =
        state.lastSlots == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(state.lastSlots);
    state.lastSlots.put(SELECTED_JOB_CONTEXT_KEY, selectedJobContext);
  }

  private String warningSuffix(String warning) {
    return warning == null || warning.trim().isEmpty() ? "" : "（" + warning.trim() + "）";
  }
}
