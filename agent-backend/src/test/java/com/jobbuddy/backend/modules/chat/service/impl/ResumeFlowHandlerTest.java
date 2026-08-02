package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.SELECTED_JOB_CONTEXT_KEY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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

/**
 * 验证 ResumeFlowHandler 的核心行为、异常路径与边界条件。
 */
class ResumeFlowHandlerTest {

  /**
   * 验证 ResumeFlowHandler 中简历的核心业务契约。
   */
  @Test
  void shouldRecognizeResumeSwitchFollowUpWithoutTreatingNewTargetAsDeictic() {
    assertTrue(ResumeFlowHandler.isSelectedJobResumeFollowUp("现在这个3年的简历呢"));
    assertTrue(ResumeFlowHandler.isSelectedJobResumeFollowUp("换这份简历再看一下"));
    assertFalse(ResumeFlowHandler.isSelectedJobResumeFollowUp("分析杭州 Go 云原生平台开发岗位"));
    assertFalse(ResumeFlowHandler.isSelectedJobResumeFollowUp("提供另一份岗位 JD"));
  }

  /**
   * 验证 ResumeFlowHandler 中简历的核心业务契约。
   */
  @Test
  void shouldPreferPreviouslySelectedJobForResumeSwitchFollowUp() {
    ResumeFlowHandler handler = handler();
    ChatSessionState state = new ChatSessionState();
    state.lastSlots = new LinkedHashMap<String, Object>();
    Map<String, Object> selectedJob = new LinkedHashMap<String, Object>();
    selectedJob.put("securityId", "example-job-1");
    selectedJob.put("jobName", "云原生后端工程师");
    selectedJob.put("company", "示例科技");
    selectedJob.put("description", "负责后端平台开发与工程化落地");
    state.lastSlots.put(SELECTED_JOB_CONTEXT_KEY, selectedJob);
    state.jobs = new ArrayList<Map<String, Object>>();
    state.jobs.add(Collections.<String, Object>singletonMap("jobName", "不应复评的批量岗位"));

    List<Map<String, Object>> jobs =
        handler.resolveTargetJobs(
            state,
            "现在这个5年经验的简历呢",
            "云原生后端开发负责人",
            "云原生后端开发负责人",
            "杭州示例科技，云原生后端开发负责人，3-5年经验，20-30K",
            Collections.<String, Object>emptyMap(),
            true);

    assertEquals(1, jobs.size());
    assertEquals("example-job-1", jobs.get(0).get("securityId"));
    assertEquals("示例科技", jobs.get(0).get("company"));
  }

  /**
   * 验证 ResumeFlowHandler 中岗位的核心业务契约。
   */
  @Test
  @SuppressWarnings("unchecked")
  void shouldRespectExplicitNewTargetInsteadOfReusingSelectedJob() {
    ResumeFlowHandler handler = handler();
    ChatSessionState state = new ChatSessionState();
    state.lastSlots = new LinkedHashMap<String, Object>();
    state.lastSlots.put(
        SELECTED_JOB_CONTEXT_KEY, Collections.<String, Object>singletonMap("jobName", "上一轮岗位"));
    state.jobs = new ArrayList<Map<String, Object>>();
    state.jobs.add(Collections.<String, Object>singletonMap("jobName", "旧岗位列表"));
    Map<String, Object> slots = new LinkedHashMap<String, Object>();
    slots.put("role", "杭州 Go 云原生平台开发岗");

    List<Map<String, Object>> jobs =
        handler.resolveTargetJobs(
            state, "分析杭州 Go 云原生平台开发岗", "杭州 Go 云原生平台开发岗", "杭州 Go 云原生平台开发岗", "", slots, true);

    assertTrue(jobs.isEmpty());
    assertFalse(
        ResumeFlowHandler.shouldReuseSelectedJob(
            (Map<String, Object>) state.lastSlots.get(SELECTED_JOB_CONTEXT_KEY),
            "现在用这个5年的简历分析另一个杭州 Go 云原生平台开发岗",
            "杭州 Go 云原生平台开发岗",
            "",
            true));
  }

  /**
   * 验证 ResumeFlowHandler 中岗位的核心业务契约。
   */
  @Test
  void shouldKeepCurrentJobListForPluralReference() {
    ResumeFlowHandler handler = handler();
    ChatSessionState state = new ChatSessionState();
    state.lastSlots = new LinkedHashMap<String, Object>();
    state.lastSlots.put(
        SELECTED_JOB_CONTEXT_KEY, Collections.<String, Object>singletonMap("jobName", "单个选中岗位"));
    state.jobs = new ArrayList<Map<String, Object>>();
    state.jobs.add(Collections.<String, Object>singletonMap("jobName", "岗位列表第一项"));

    List<Map<String, Object>> jobs =
        handler.resolveTargetJobs(
            state,
            "把这些岗位和我的简历做匹配",
            "大模型应用开发",
            "大模型应用开发",
            "",
            Collections.<String, Object>emptyMap(),
            false);

    assertEquals(1, jobs.size());
    assertEquals("岗位列表第一项", jobs.get(0).get("jobName"));
  }

  /**
   * 验证 ResumeFlowHandler 中岗位的题目生成与作答判定规则。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldReuseRecommendedJobSnapshotForResumeSwitchWithoutReloadingBoss() throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    CurrentResumeLoader resumeLoader = mock(CurrentResumeLoader.class);
    JobRuntimeService jobRuntimeService = mock(JobRuntimeService.class);
    ResumeRecord resume = new ResumeRecord();
    resume.setResumeId("resume-5-years");
    resume.setOriginalName("杭州-Java方向-云原生后端开发岗-5年经验.pdf");
    resume.setParsed(Collections.<String, Object>singletonMap("summary", "5年 Java 与平台工程经验"));
    when(resumeLoader.loadCurrentResume(any(ChatSessionState.class))).thenReturn(resume);
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
            new SelectedJobContextResolver());
    ChatSessionState state = new ChatSessionState();
    state.resumeId = "resume-5-years";
    state.lastSlots = new LinkedHashMap<String, Object>();
    Map<String, Object> selectedJob = new LinkedHashMap<String, Object>();
    selectedJob.put("securityId", "job-legacy");
    selectedJob.put("jobName", "云原生平台开发岗");
    selectedJob.put("company", "星河云科（虚构）");
    state.lastSlots.put(SELECTED_JOB_CONTEXT_KEY, selectedJob);
    Map<String, Object> recommendedJob = new LinkedHashMap<String, Object>(selectedJob);
    recommendedJob.put("jobDescription", "负责 Java 与 Python 大模型应用平台、Agent 工作流和生产系统工程化，要求五年以上研发经验。");
    state.jobs = Collections.singletonList(recommendedJob);
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

    handler.handleResumeMatch(emitter, "session-1", state, intent, "现在这个5年经验的简历呢", directive);

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
            org.mockito.ArgumentMatchers.contains("星河云科（虚构） / 云原生平台开发岗"),
            any(Map.class));
  }

  /**
   * 换简历复评缺少完整 JD 时直接使用列表证据，不触发隐式详情加载。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldUseListEvidenceWhenResumeSwitchSnapshotLacksJobDescription() throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    CurrentResumeLoader resumeLoader = mock(CurrentResumeLoader.class);
    JobRuntimeService jobRuntimeService = mock(JobRuntimeService.class);
    ResumeRecord resume = new ResumeRecord();
    resume.setResumeId("resume-new");
    resume.setOriginalName("当前选择的简历.pdf");
    resume.setParsed(Collections.<String, Object>singletonMap("summary", "后端工程经验"));
    when(resumeLoader.loadCurrentResume(any(ChatSessionState.class))).thenReturn(resume);
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("id", "job-without-jd");
    row.put("score", 68);
    row.put("score_confidence", "medium");
    row.put("recommendation", "可尝试");
    row.put("reasoning", "岗位列表信息与简历后端经验有部分匹配。");
    Map<String, Object> match = new LinkedHashMap<String, Object>();
    match.put("matches", Collections.singletonList(row));
    match.put("evaluation_mode", "recommendation_list");
    when(jobRuntimeService.matchResumeListEvidenceSections(
            any(ResumeRecord.class), anyList(), eq("session-missing-jd"), anyList()))
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
            new SelectedJobContextResolver());
    ChatSessionState state = new ChatSessionState();
    state.resumeId = "resume-new";
    state.lastSlots = new LinkedHashMap<String, Object>();
    Map<String, Object> selectedJob = new LinkedHashMap<String, Object>();
    selectedJob.put("securityId", "job-without-jd");
    selectedJob.put("jobName", "云原生平台开发岗");
    selectedJob.put("jobDescription", "岗位摘要");
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
    Map<String, Object> metadata =
        Collections.<String, Object>singletonMap("reuse_previous_slots", true);
    Map<String, Object> task = Collections.<String, Object>singletonMap("metadata", metadata);
    Map<String, Object> directive = Collections.<String, Object>singletonMap("task", task);
    SseEmitter emitter = mock(SseEmitter.class);

    handler.handleResumeMatch(emitter, "session-missing-jd", state, intent, "换这份简历再看一下", directive);

    verify(jobRuntimeService, never())
        .matchResumeSections(any(ResumeRecord.class), anyList(), any(String.class), anyList());
    verify(jobRuntimeService)
        .matchResumeListEvidenceSections(
            eq(resume), anyList(), eq("session-missing-jd"), anyList());
    verify(sender)
        .sendAssistant(
            eq(emitter),
            eq("session-missing-jd"),
            eq(state),
            org.mockito.ArgumentMatchers.contains("重新评估上一轮岗位"),
            any(Map.class));
  }

  /**
   * 验证 ResumeFlowHandler 的数据转换与协议契约。
   */
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

  /**
   * 验证处理器。
   *
   * @return 待测试处理器
   */
  private ResumeFlowHandler handler() {
    return new ResumeFlowHandler(
        mock(ChatSseEventSender.class),
        mock(CurrentResumeLoader.class),
        mock(ResumeStorageService.class),
        mock(JobRuntimeService.class),
        mock(ChatSessionStore.class),
        mock(AgentIntegrationService.class),
        mock(RuntimeManagedRequestFactory.class),
        new SelectedJobContextResolver());
  }
}
