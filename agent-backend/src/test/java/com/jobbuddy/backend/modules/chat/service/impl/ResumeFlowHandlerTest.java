package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.SELECTED_JOB_CONTEXT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
  void shouldHydrateLegacySelectedJobAndExplainResolvedContextInAnswer() throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    CurrentResumeLoader resumeLoader = mock(CurrentResumeLoader.class);
    JobRuntimeService jobRuntimeService = mock(JobRuntimeService.class);
    BossCliService bossCliService = mock(BossCliService.class);
    ResumeRecord resume = new ResumeRecord();
    resume.setResumeId("resume-6-years");
    resume.setOriginalName("上海-Java方向-大模型应用开发岗-6年经验.pdf");
    resume.setParsed(Collections.<String, Object>singletonMap("summary", "6年 Java 与大模型应用经验"));
    when(resumeLoader.loadCurrentResume(any(ChatSessionState.class))).thenReturn(resume);
    Map<String, Object> detail = new LinkedHashMap<String, Object>();
    detail.put("jobDescription", "负责 Java 与 Python 大模型应用平台、Agent 工作流和生产系统工程化，要求五年以上研发经验。");
    when(bossCliService.jobDetail("job-legacy", "")).thenReturn(new JsonCodec().toTree(detail));
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("score", 82);
    row.put("score_confidence", "high");
    row.put("recommendation", "推荐");
    row.put("reasoning", "当前简历的工程经验覆盖岗位核心要求。");
    row.put("hits", java.util.Arrays.asList("Java 后端", "Agent 工程化"));
    row.put("gaps", java.util.Arrays.asList("行业背景需补充"));
    Map<String, Object> match = new LinkedHashMap<String, Object>();
    match.put("matches", java.util.Arrays.asList(row));
    when(jobRuntimeService.matchResumeSections(any(), anyList(), eq("session-1"), anyList()))
        .thenReturn(match);
    ResumeFlowHandler handler =
        new ResumeFlowHandler(
            sender,
            resumeLoader,
            mock(ResumeStorageService.class),
            jobRuntimeService,
            mock(ChatSessionStore.class),
            mock(AgentIntegrationService.class),
            mock(RuntimeManagedRequestFactory.class),
            new SelectedJobContextResolver(bossCliService));
    ChatSessionState state = new ChatSessionState();
    state.resumeId = "resume-6-years";
    state.lastSlots = new LinkedHashMap<String, Object>();
    Map<String, Object> selectedJob = new LinkedHashMap<String, Object>();
    selectedJob.put("securityId", "job-legacy");
    selectedJob.put("jobName", "大模型应用开发岗");
    selectedJob.put("company", "上海示例科技");
    state.lastSlots.put(SELECTED_JOB_CONTEXT_KEY, selectedJob);
    IntentResult intent =
        new IntentResult(
            "job",
            "resume.match",
            0.99,
            Collections.<String>emptyList(),
            "low",
            false,
            "run_resume_match",
            Collections.<String, Object>emptyMap());
    Map<String, Object> taskMetadata =
        Collections.<String, Object>singletonMap("reuse_previous_slots", true);
    Map<String, Object> task = Collections.<String, Object>singletonMap("metadata", taskMetadata);
    Map<String, Object> directive = Collections.<String, Object>singletonMap("task", task);
    SseEmitter emitter = mock(SseEmitter.class);

    handler.handleResumeMatch(emitter, "session-1", state, intent, "现在这个6年的简历呢", directive);

    ArgumentCaptor<List<Map<String, Object>>> jobsCaptor = ArgumentCaptor.forClass(List.class);
    verify(jobRuntimeService)
        .matchResumeSections(eq(resume), jobsCaptor.capture(), eq("session-1"), anyList());
    assertTrue(
        String.valueOf(jobsCaptor.getValue().get(0).get("jobDescription")).contains("Agent 工作流"));
    verify(sender)
        .sendAssistant(
            eq(emitter),
            eq("session-1"),
            eq(state),
            org.mockito.ArgumentMatchers.contains("重新评估上一轮岗位"),
            any(Map.class));
    verify(sender)
        .sendAssistant(
            eq(emitter),
            eq("session-1"),
            eq(state),
            org.mockito.ArgumentMatchers.contains("上海示例科技 / 大模型应用开发岗"),
            any(Map.class));
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
        mock(RuntimeManagedRequestFactory.class),
        new SelectedJobContextResolver(mock(BossCliService.class)));
  }
}
