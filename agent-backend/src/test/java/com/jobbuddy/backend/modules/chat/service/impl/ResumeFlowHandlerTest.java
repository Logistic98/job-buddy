package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.SELECTED_JOB_CONTEXT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResumeFlowHandlerTest {

  @Test
  void shouldRecognizeResumeSwitchFollowUpWithoutTreatingNewTargetAsDeictic() {
    assertTrue(ResumeFlowHandler.isSelectedJobResumeFollowUp("现在这个3年的简历呢"));
    assertTrue(ResumeFlowHandler.isSelectedJobResumeFollowUp("换这份简历再看一下"));
    assertFalse(ResumeFlowHandler.isSelectedJobResumeFollowUp("分析上海 Java 大模型应用开发岗位"));
    assertFalse(ResumeFlowHandler.isSelectedJobResumeFollowUp("提供另一份岗位 JD"));
  }

  @Test
  void shouldPreferPreviouslySelectedJobForResumeSwitchFollowUp() {
    ResumeFlowHandler handler = handler();
    ChatSessionState state = new ChatSessionState();
    state.lastSlots = new LinkedHashMap<String, Object>();
    Map<String, Object> selectedJob = new LinkedHashMap<String, Object>();
    selectedJob.put("securityId", "meituan-job-1");
    selectedJob.put("jobName", "AI大模型应用工程师");
    selectedJob.put("company", "美团");
    selectedJob.put("description", "负责大模型应用开发与工程化落地");
    state.lastSlots.put(SELECTED_JOB_CONTEXT_KEY, selectedJob);
    state.jobs = new ArrayList<Map<String, Object>>();
    state.jobs.add(Collections.<String, Object>singletonMap("jobName", "不应复评的批量岗位"));

    List<Map<String, Object>> jobs =
        handler.resolveTargetJobs(
            state,
            "现在这个6年的简历呢",
            "AI大模型应用开发组长",
            "AI大模型应用开发组长",
            "上海九点智投投资顾问，AI大模型应用开发组长，3-5年经验，35-50K",
            Collections.<String, Object>emptyMap(),
            true);

    assertEquals(1, jobs.size());
    assertEquals("meituan-job-1", jobs.get(0).get("securityId"));
    assertEquals("美团", jobs.get(0).get("company"));
  }

  @Test
  void shouldRespectExplicitNewTargetInsteadOfReusingSelectedJob() {
    ResumeFlowHandler handler = handler();
    ChatSessionState state = new ChatSessionState();
    state.lastSlots = new LinkedHashMap<String, Object>();
    state.lastSlots.put(
        SELECTED_JOB_CONTEXT_KEY, Collections.<String, Object>singletonMap("jobName", "上一轮岗位"));
    Map<String, Object> slots = new LinkedHashMap<String, Object>();
    slots.put("role", "上海 Java 大模型应用开发岗");

    List<Map<String, Object>> jobs =
        handler.resolveTargetJobs(
            state, "分析上海 Java 大模型应用开发岗", "上海 Java 大模型应用开发岗", "上海 Java 大模型应用开发岗", "", slots, false);

    assertTrue(jobs.isEmpty());
  }

  @Test
  void shouldReadReusePreviousSlotsFromTaskMetadata() {
    Map<String, Object> metadata = new LinkedHashMap<String, Object>();
    metadata.put("reuse_previous_slots", true);
    Map<String, Object> task = new LinkedHashMap<String, Object>();
    task.put("metadata", metadata);
    Map<String, Object> directive = new LinkedHashMap<String, Object>();
    directive.put("task", task);

    assertTrue(ResumeFlowHandler.shouldReusePreviousSlots(directive));
    assertFalse(ResumeFlowHandler.shouldReusePreviousSlots(Collections.<String, Object>emptyMap()));
  }

  private ResumeFlowHandler handler() {
    return new ResumeFlowHandler(
        mock(ChatSseEventSender.class),
        mock(CurrentResumeLoader.class),
        mock(ResumeStorageService.class),
        mock(JobRuntimeService.class),
        mock(ChatSessionStore.class),
        mock(AgentIntegrationService.class),
        mock(RuntimeManagedRequestFactory.class));
  }
}
