package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.SELECTED_JOB_CONTEXT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SelectedJobAnalysisHandlerTest {

  @Test
  @SuppressWarnings("unchecked")
  void shouldKeepCompleteSelectedJobContextAndUseEvidenceMatchFlow() throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    ResumeFlowHandler resumeFlowHandler = mock(ResumeFlowHandler.class);
    SelectedJobAnalysisHandler handler =
        new SelectedJobAnalysisHandler(
            sender, new SelectedJobContextResolver(mock(BossCliService.class)), resumeFlowHandler);
    ChatSessionState state = new ChatSessionState();
    Map<String, Object> selectedJob = new LinkedHashMap<String, Object>();
    selectedJob.put("securityId", "job-1");
    selectedJob.put("jobName", "Java 大模型应用开发工程师");
    selectedJob.put("brandName", "上海示例科技");
    selectedJob.put("jobDescription", "负责 RAG、Agent、Java 服务工程化落地，以及生产环境评测、可观测和性能优化工作。");
    SseEmitter emitter = mock(SseEmitter.class);

    handler.handle(emitter, "session-1", state, "分析此岗位", selectedJob);

    assertTrue(state.lastSlots.get(SELECTED_JOB_CONTEXT_KEY) instanceof Map);
    Map<String, Object> context =
        (Map<String, Object>) state.lastSlots.get(SELECTED_JOB_CONTEXT_KEY);
    assertEquals("job-1", context.get("securityId"));
    assertEquals("Java 大模型应用开发工程师", context.get("jobName"));
    assertTrue(String.valueOf(context.get("description")).contains("生产环境评测"));
    verify(resumeFlowHandler)
        .handleSelectedJobMatch(
            eq(emitter), eq("session-1"), eq(state), eq("分析此岗位"), any(Map.class));
  }

  @Test
  @SuppressWarnings("unchecked")
  void shouldLoadMissingJobDescriptionBeforeRememberingContext() throws Exception {
    BossCliService bossCliService = mock(BossCliService.class);
    Map<String, Object> detail = new LinkedHashMap<String, Object>();
    detail.put("jobDescription", "负责大模型应用平台、Agent 工作流、Java 后端服务和线上稳定性治理，要求具备完整工程落地经验。");
    when(bossCliService.jobDetail("job-2", "https://www.zhipin.com/job_detail/job-2.html"))
        .thenReturn(new JsonCodec().toTree(detail));
    ResumeFlowHandler resumeFlowHandler = mock(ResumeFlowHandler.class);
    SelectedJobAnalysisHandler handler =
        new SelectedJobAnalysisHandler(
            mock(ChatSseEventSender.class),
            new SelectedJobContextResolver(bossCliService),
            resumeFlowHandler);
    ChatSessionState state = new ChatSessionState();
    Map<String, Object> selectedJob = new LinkedHashMap<String, Object>();
    selectedJob.put("securityId", "job-2");
    selectedJob.put("jobName", "大模型应用开发岗");
    selectedJob.put("originalUrl", "https://www.zhipin.com/job_detail/job-2.html");

    handler.handle(mock(SseEmitter.class), "session-2", state, "分析此岗位", selectedJob);

    Map<String, Object> context =
        (Map<String, Object>) state.lastSlots.get(SELECTED_JOB_CONTEXT_KEY);
    assertTrue(String.valueOf(context.get("jobDescription")).contains("线上稳定性治理"));
    verify(resumeFlowHandler)
        .handleSelectedJobMatch(
            any(SseEmitter.class), eq("session-2"), eq(state), eq("分析此岗位"), any(Map.class));
  }

  @Test
  void shouldNotRunMatchWhenJobDescriptionCannotBeResolved() throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    ResumeFlowHandler resumeFlowHandler = mock(ResumeFlowHandler.class);
    SelectedJobAnalysisHandler handler =
        new SelectedJobAnalysisHandler(
            sender, new SelectedJobContextResolver(mock(BossCliService.class)), resumeFlowHandler);
    Map<String, Object> selectedJob = new LinkedHashMap<String, Object>();
    selectedJob.put("jobName", "只有名称的岗位");

    handler.handle(
        mock(SseEmitter.class), "session-3", new ChatSessionState(), "分析此岗位", selectedJob);

    verify(resumeFlowHandler, never()).handleSelectedJobMatch(any(), any(), any(), any(), any());
    verify(sender)
        .sendAssistant(
            any(SseEmitter.class),
            eq("session-3"),
            any(ChatSessionState.class),
            org.mockito.ArgumentMatchers.contains("不会仅凭岗位名称"),
            any(Map.class));
  }
}
