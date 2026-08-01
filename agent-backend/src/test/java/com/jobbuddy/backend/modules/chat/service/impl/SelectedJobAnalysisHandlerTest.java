package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.SELECTED_JOB_CONTEXT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 验证 SelectedJobAnalysisHandler 的核心行为、异常路径与边界条件。
 */
class SelectedJobAnalysisHandlerTest {

  /**
   * 验证 SelectedJobAnalysisHandler 中岗位的检索、筛选与排序规则。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  @SuppressWarnings("unchecked")
  void shouldKeepCompleteSelectedJobContextAndUseEvidenceMatchFlow() throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    ResumeFlowHandler resumeFlowHandler = mock(ResumeFlowHandler.class);
    SelectedJobAnalysisHandler handler =
        new SelectedJobAnalysisHandler(sender, new SelectedJobContextResolver(), resumeFlowHandler);
    ChatSessionState state = new ChatSessionState();
    state.resumeId = "resume-1";
    Map<String, Object> selectedJob = new LinkedHashMap<String, Object>();
    selectedJob.put("securityId", "job-1");
    selectedJob.put("jobName", "Go 云原生平台开发工程师");
    selectedJob.put("brandName", "星河云科（虚构）");
    selectedJob.put("jobDescription", "负责 Go、Kubernetes 服务工程化落地，以及生产环境评测、可观测和性能优化工作。");
    SseEmitter emitter = mock(SseEmitter.class);

    handler.handle(emitter, "session-1", state, "分析此岗位", selectedJob);

    assertTrue(state.lastSlots.get(SELECTED_JOB_CONTEXT_KEY) instanceof Map);
    Map<String, Object> context =
        (Map<String, Object>) state.lastSlots.get(SELECTED_JOB_CONTEXT_KEY);
    assertEquals("job-1", context.get("securityId"));
    assertEquals("Go 云原生平台开发工程师", context.get("jobName"));
    assertTrue(String.valueOf(context.get("description")).contains("生产环境评测"));
    verify(resumeFlowHandler)
        .handleSelectedJobMatch(
            eq(emitter), eq("session-1"), eq(state), eq("分析此岗位"), any(Map.class));
  }

  /**
   * 验证 SelectedJobAnalysisHandler 中岗位的输入校验与拒绝边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  @SuppressWarnings("unchecked")
  void shouldMergeMissingJobDescriptionFromCurrentRecommendationSnapshot() throws Exception {
    ResumeFlowHandler resumeFlowHandler = mock(ResumeFlowHandler.class);
    SelectedJobAnalysisHandler handler =
        new SelectedJobAnalysisHandler(
            mock(ChatSseEventSender.class), new SelectedJobContextResolver(), resumeFlowHandler);
    ChatSessionState state = new ChatSessionState();
    state.resumeId = "resume-2";
    Map<String, Object> recommendedJob = new LinkedHashMap<String, Object>();
    recommendedJob.put("securityId", "job-2");
    recommendedJob.put("jobName", "大模型应用开发岗");
    recommendedJob.put("jobDescription", "负责大模型应用平台、Agent 工作流、Java 后端服务和线上稳定性治理，要求具备完整工程落地经验。");
    state.jobs = java.util.Collections.singletonList(recommendedJob);
    Map<String, Object> selectedJob = new LinkedHashMap<String, Object>();
    selectedJob.put("securityId", "job-2");
    selectedJob.put("jobName", "大模型应用开发岗");
    selectedJob.put("jobDescription", "岗位摘要");

    handler.handle(mock(SseEmitter.class), "session-2", state, "分析此岗位", selectedJob);

    Map<String, Object> context =
        (Map<String, Object>) state.lastSlots.get(SELECTED_JOB_CONTEXT_KEY);
    assertTrue(String.valueOf(context.get("jobDescription")).contains("线上稳定性治理"));
    verify(resumeFlowHandler)
        .handleSelectedJobMatch(
            any(SseEmitter.class), eq("session-2"), eq(state), eq("分析此岗位"), any(Map.class));
  }

  /**
   * 验证 SelectedJobAnalysisHandler 中简历的流式生命周期与中断边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldNotLoadJobDetailBeforeResumeIsSelected() throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    ResumeFlowHandler resumeFlowHandler = mock(ResumeFlowHandler.class);
    SelectedJobAnalysisHandler handler =
        new SelectedJobAnalysisHandler(sender, new SelectedJobContextResolver(), resumeFlowHandler);
    Map<String, Object> selectedJob = new LinkedHashMap<String, Object>();
    selectedJob.put("securityId", "job-without-resume");
    selectedJob.put("jobName", "大模型应用开发岗");

    handler.handle(
        mock(SseEmitter.class),
        "session-without-resume",
        new ChatSessionState(),
        "分析此岗位",
        selectedJob);

    verify(resumeFlowHandler, never()).handleSelectedJobMatch(any(), any(), any(), any(), any());
    verify(sender)
        .sendAssistant(
            any(SseEmitter.class),
            eq("session-without-resume"),
            any(ChatSessionState.class),
            org.mockito.ArgumentMatchers.contains("请先选择或上传 PDF 简历"),
            any(Map.class));
  }

  /**
   * 验证 SelectedJobAnalysisHandler 中岗位的输入校验与拒绝边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldNotRunMatchWhenJobDescriptionCannotBeResolved() throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    ResumeFlowHandler resumeFlowHandler = mock(ResumeFlowHandler.class);
    SelectedJobAnalysisHandler handler =
        new SelectedJobAnalysisHandler(sender, new SelectedJobContextResolver(), resumeFlowHandler);
    Map<String, Object> selectedJob = new LinkedHashMap<String, Object>();
    selectedJob.put("jobName", "只有名称的岗位");
    ChatSessionState state = new ChatSessionState();
    state.resumeId = "resume-3";

    handler.handle(mock(SseEmitter.class), "session-3", state, "分析此岗位", selectedJob);

    verify(resumeFlowHandler, never()).handleSelectedJobMatch(any(), any(), any(), any(), any());
    ArgumentCaptor<String> messageCaptor = ArgumentCaptor.forClass(String.class);
    verify(sender)
        .sendAssistant(
            any(SseEmitter.class),
            eq("session-3"),
            any(ChatSessionState.class),
            messageCaptor.capture(),
            any(Map.class));
    assertTrue(messageCaptor.getValue().contains("不会仅凭岗位名称"));
    assertTrue(messageCaptor.getValue().contains("请重新检索岗位"));
    assertTrue(!messageCaptor.getValue().contains("重新点击“分析此岗位”"));
  }
}
