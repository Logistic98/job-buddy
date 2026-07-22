package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.SELECTED_JOB_CONTEXT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class SelectedJobAnalysisHandlerTest {

  @Test
  @SuppressWarnings("unchecked")
  void shouldKeepSelectedJobContextForResumeSwitchFollowUp() throws Exception {
    SelectedJobAnalysisHandler handler =
        new SelectedJobAnalysisHandler(
            mock(ChatSseEventSender.class),
            mock(CurrentResumeLoader.class),
            mock(ResumeStorageService.class),
            mock(AgentIntegrationService.class),
            mock(RuntimeManagedRequestFactory.class));
    ChatSessionState state = new ChatSessionState();
    Map<String, Object> selectedJob = new LinkedHashMap<String, Object>();
    selectedJob.put("securityId", "job-1");
    selectedJob.put("jobName", "Java 大模型应用开发工程师");
    selectedJob.put("jobDescription", "负责 RAG、Agent 与 Java 服务工程化落地");

    handler.handle(mock(SseEmitter.class), "session-1", state, "分析此岗位", selectedJob);

    assertTrue(state.lastSlots.get(SELECTED_JOB_CONTEXT_KEY) instanceof Map);
    Map<String, Object> context =
        (Map<String, Object>) state.lastSlots.get(SELECTED_JOB_CONTEXT_KEY);
    assertEquals("job-1", context.get("securityId"));
    assertEquals("Java 大模型应用开发工程师", context.get("jobName"));
  }
}
