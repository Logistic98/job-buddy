package com.jobbuddy.backend.modules.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.chat.dto.request.ChatStreamRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunRequest;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import com.jobbuddy.backend.modules.chat.service.JobRecommendationResult;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.prompt.model.PersonalContext;
import com.jobbuddy.backend.modules.prompt.service.PersonalContextBuilder;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import java.io.IOException;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 验证 ChatSseCollaborators 的核心行为、异常路径与边界条件。
 */
class ChatSseCollaboratorsTest {

  /**
   * 验证 ChatSseCollaborators 中简历的去重与幂等边界。
   */
  @Test
  void authReplayShouldKeepResumeRematchIntent() {
    ChatSessionState state = new ChatSessionState();
    state.lastSlots = new LinkedHashMap<String, Object>();
    state.lastSlots.put(
        "_selected_job", Collections.<String, Object>singletonMap("jobName", "上一轮岗位"));
    state.lastSlots.put("follow_up", "resume_switch_rematch");
    ChatStreamRequest request = new ChatStreamRequest();
    request.setResumeAfterAuth(true);
    request.setMessage("现在这个5年经验的简历呢");

    assertTrue(ChatSseServiceImpl.shouldResumeSelectedJobMatchAfterAuth(request, state));

    request.setResumeAfterAuth(false);
    assertFalse(ChatSseServiceImpl.shouldResumeSelectedJobMatchAfterAuth(request, state));
  }

  /**
   * 验证 ChatSseCollaborators 中岗位的持久化与状态变更规则。
   */
  @Test
  void jobRecommendationShouldPersistResolvedSlotsBeforeRequestingAuthentication() {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    ChatPersistenceCoordinator persistence = mock(ChatPersistenceCoordinator.class);
    JobRuntimeService jobRuntimeService = mock(JobRuntimeService.class);
    PersonalContextBuilder personalContextBuilder = mock(PersonalContextBuilder.class);
    CurrentResumeLoader resumeLoader = mock(CurrentResumeLoader.class);
    when(personalContextBuilder.build(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(
            new PersonalContext(
                "job",
                Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                ""));
    when(jobRuntimeService.bossCandidatePoolTimeoutSeconds()).thenReturn(30);
    when(jobRuntimeService.recommendJobsFast(any(IntentResult.class), eq("s1"), any()))
        .thenThrow(new BossAuthRequiredException("需要扫码", Collections.<String, Object>emptyMap()));
    JobRecommendHandler handler =
        new JobRecommendHandler(
            sender,
            persistence,
            jobRuntimeService,
            personalContextBuilder,
            resumeLoader,
            new JobBuddyProperties());
    ChatSessionState state = new ChatSessionState();
    state.sessionId = "s1";
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            1.0,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            Collections.<String, Object>singletonMap("role", "大模型应用开发"));

    assertThrows(
        BossAuthRequiredException.class,
        () -> handler.handle(new SseEmitter(0L), "s1", state, intent, false, "筛选大模型岗位"));

    assertEquals("大模型应用开发", state.lastSlots.get("role"));
    verify(persistence).saveStateAsync(state);
  }

  /**
   * 验证 ChatSseCollaborators 中岗位的输入校验与拒绝边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void jobRecommendationShouldPersistClearedStateWhenQualityGateErrorCannotBeSent()
      throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    ChatPersistenceCoordinator persistence = mock(ChatPersistenceCoordinator.class);
    JobRuntimeService jobRuntimeService = mock(JobRuntimeService.class);
    PersonalContextBuilder personalContextBuilder = mock(PersonalContextBuilder.class);
    CurrentResumeLoader resumeLoader = mock(CurrentResumeLoader.class);
    when(personalContextBuilder.build(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(
            new PersonalContext(
                "job",
                Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                ""));
    when(jobRuntimeService.bossCandidatePoolTimeoutSeconds()).thenReturn(30);
    List<Map<String, Object>> candidates =
        Collections.<Map<String, Object>>singletonList(
            Collections.<String, Object>singletonMap("jobName", "大模型应用开发"));
    when(jobRuntimeService.recommendJobsFast(any(IntentResult.class), eq("s1"), any()))
        .thenReturn(candidates);
    when(resumeLoader.loadCurrentResume(any(ChatSessionState.class)))
        .thenReturn(mock(ResumeRecord.class));
    when(jobRuntimeService.prequalifyRecommendationsWithContinuation(
            any(ResumeRecord.class), any(IntentResult.class), eq(candidates), eq("s1")))
        .thenThrow(new RuntimeException("quality gate failed"));
    doAnswer(
            invocation -> {
              Map<String, Object> event = invocation.getArgument(3);
              if ("recommendation_quality_gate".equals(event.get("id"))
                  && "error".equals(event.get("status"))) {
                throw new IOException("connection closed");
              }
              return null;
            })
        .when(sender)
        .sendToolStatus(any(SseEmitter.class), eq("s1"), any(ChatSessionState.class), anyMap());
    JobRecommendHandler handler =
        new JobRecommendHandler(
            sender,
            persistence,
            jobRuntimeService,
            personalContextBuilder,
            resumeLoader,
            new JobBuddyProperties());
    ChatSessionState state = new ChatSessionState();
    state.sessionId = "s1";
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    state.jobs =
        Collections.<Map<String, Object>>singletonList(
            Collections.<String, Object>singletonMap("jobName", "旧岗位"));
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            1.0,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            Collections.<String, Object>singletonMap("role", "大模型应用开发"));

    assertThrows(
        IOException.class,
        () -> handler.handle(new SseEmitter(0L), "s1", state, intent, false, "筛选大模型岗位"));

    assertTrue(state.jobs.isEmpty());
    verify(persistence).saveStateAsync(state);
  }

  /**
   * 换一批质量门失败时保留上一批已验证岗位，并明确说明当前展示内容不是本轮新结果。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void jobRecommendationFlipShouldRetainPreviousVerifiedBatchOnQualityGateFailure()
      throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    ChatPersistenceCoordinator persistence = mock(ChatPersistenceCoordinator.class);
    JobRuntimeService jobRuntimeService = mock(JobRuntimeService.class);
    PersonalContextBuilder personalContextBuilder = mock(PersonalContextBuilder.class);
    CurrentResumeLoader resumeLoader = mock(CurrentResumeLoader.class);
    when(personalContextBuilder.build(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(
            new PersonalContext(
                "job",
                Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                ""));
    when(jobRuntimeService.bossCandidatePoolTimeoutSeconds()).thenReturn(30);
    List<Map<String, Object>> candidates =
        Collections.<Map<String, Object>>singletonList(
            Collections.<String, Object>singletonMap("jobName", "新候选岗位"));
    when(jobRuntimeService.recommendJobsFast(any(IntentResult.class), eq("s1"), any()))
        .thenReturn(candidates);
    when(resumeLoader.loadCurrentResume(any(ChatSessionState.class)))
        .thenReturn(mock(ResumeRecord.class));
    when(jobRuntimeService.prequalifyRecommendationsWithContinuation(
            any(ResumeRecord.class), any(IntentResult.class), eq(candidates), eq("s1")))
        .thenThrow(new RuntimeException("岗位匹配结果不完整"));
    JobRecommendHandler handler =
        new JobRecommendHandler(
            sender,
            persistence,
            jobRuntimeService,
            personalContextBuilder,
            resumeLoader,
            new JobBuddyProperties());
    List<Map<String, Object>> previousJobs =
        Collections.<Map<String, Object>>singletonList(
            Collections.<String, Object>singletonMap("jobName", "上一批已验证岗位"));
    Map<String, Object> previousSlots = new LinkedHashMap<String, Object>();
    previousSlots.put("role", "大模型应用开发");
    previousSlots.put("candidate_offset", Integer.valueOf(5));
    ChatSessionState state = new ChatSessionState();
    state.sessionId = "s1";
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    state.jobs = new java.util.ArrayList<Map<String, Object>>(previousJobs);
    state.lastSlots = new LinkedHashMap<String, Object>(previousSlots);
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            1.0,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            previousSlots);

    handler.handle(new SseEmitter(0L), "s1", state, intent, true, "换一批");

    assertEquals(previousJobs, state.jobs);
    assertEquals(previousSlots, state.lastSlots);
    ArgumentCaptor<String> assistantCaptor = ArgumentCaptor.forClass(String.class);
    verify(sender)
        .sendAssistant(any(SseEmitter.class), eq("s1"), eq(state), assistantCaptor.capture());
    assertTrue(assistantCaptor.getValue().contains("岗位消息仍保留"));
    assertFalse(assistantCaptor.getValue().contains("本轮未生成推荐卡片"));
    ArgumentCaptor<Map<String, Object>> statusCaptor = ArgumentCaptor.forClass((Class) Map.class);
    verify(sender, org.mockito.Mockito.times(6))
        .sendToolStatus(any(SseEmitter.class), eq("s1"), eq(state), statusCaptor.capture());
    Map<String, Object> errorStatus = statusCaptor.getAllValues().get(5);
    Map<String, Object> errorDetail = (Map<String, Object>) errorStatus.get("detail");
    assertEquals(Boolean.TRUE, errorDetail.get("retainedPreviousBatch"));
    assertEquals(1, errorDetail.get("previousJobCount"));
    verify(persistence).saveStateAsync(state);
  }

  /**
   * 换一批搜索失败时保留上一批已验证岗位和检索槽位，并明确说明当前展示内容。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void jobRecommendationFlipShouldRetainPreviousBatchOnSearchFailure() throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    ChatPersistenceCoordinator persistence = mock(ChatPersistenceCoordinator.class);
    JobRuntimeService jobRuntimeService = mock(JobRuntimeService.class);
    PersonalContextBuilder personalContextBuilder = mock(PersonalContextBuilder.class);
    when(personalContextBuilder.build(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(
            new PersonalContext(
                "job",
                Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                ""));
    when(jobRuntimeService.bossCandidatePoolTimeoutSeconds()).thenReturn(30);
    when(jobRuntimeService.recommendJobsFast(any(IntentResult.class), eq("s1"), any()))
        .thenThrow(new RuntimeException("Boss 搜索暂时失败"));
    JobRecommendHandler handler =
        new JobRecommendHandler(
            sender,
            persistence,
            jobRuntimeService,
            personalContextBuilder,
            mock(CurrentResumeLoader.class),
            new JobBuddyProperties());
    List<Map<String, Object>> previousJobs =
        Collections.<Map<String, Object>>singletonList(
            Collections.<String, Object>singletonMap("securityId", "previous-job"));
    Map<String, Object> previousSlots = new LinkedHashMap<String, Object>();
    previousSlots.put("role", "大模型应用开发");
    previousSlots.put("boss_page", Integer.valueOf(1));
    ChatSessionState state = new ChatSessionState();
    state.sessionId = "s1";
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    state.jobs = new java.util.ArrayList<Map<String, Object>>(previousJobs);
    state.lastSlots = new LinkedHashMap<String, Object>(previousSlots);
    Map<String, Object> nextSlots = new LinkedHashMap<String, Object>(previousSlots);
    nextSlots.put("boss_page", Integer.valueOf(2));
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            1.0,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            nextSlots);

    handler.handle(new SseEmitter(0L), "s1", state, intent, true, "换一批");

    assertEquals(previousJobs, state.jobs);
    assertEquals(previousSlots, state.lastSlots);
    ArgumentCaptor<String> assistantCaptor = ArgumentCaptor.forClass(String.class);
    verify(sender)
        .sendAssistant(any(SseEmitter.class), eq("s1"), eq(state), assistantCaptor.capture());
    assertTrue(assistantCaptor.getValue().contains("岗位消息仍保留"));
    ArgumentCaptor<Map<String, Object>> statusCaptor = ArgumentCaptor.forClass((Class) Map.class);
    verify(sender, org.mockito.Mockito.times(4))
        .sendToolStatus(any(SseEmitter.class), eq("s1"), eq(state), statusCaptor.capture());
    Map<String, Object> errorDetail =
        (Map<String, Object>) statusCaptor.getAllValues().get(3).get("detail");
    assertEquals(Boolean.TRUE, errorDetail.get("retainedPreviousBatch"));
    assertEquals(1, errorDetail.get("previousJobCount"));
    verify(persistence).saveStateAsync(state);
  }

  /**
   * 普通新搜索失败时恢复上一批已验证岗位和槽位，避免旧岗位卡片与本轮失败槽位串用。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void jobRecommendationSearchFailureShouldRestorePreviousBatchWithoutFlip() throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    ChatPersistenceCoordinator persistence = mock(ChatPersistenceCoordinator.class);
    JobRuntimeService jobRuntimeService = mock(JobRuntimeService.class);
    PersonalContextBuilder personalContextBuilder = mock(PersonalContextBuilder.class);
    when(personalContextBuilder.build(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(
            new PersonalContext(
                "job",
                Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                ""));
    when(jobRuntimeService.bossCandidatePoolTimeoutSeconds()).thenReturn(30);
    when(jobRuntimeService.recommendJobsFast(any(IntentResult.class), eq("s1"), any()))
        .thenThrow(new RuntimeException("Boss 搜索暂时失败"));
    JobRecommendHandler handler =
        new JobRecommendHandler(
            sender,
            persistence,
            jobRuntimeService,
            personalContextBuilder,
            mock(CurrentResumeLoader.class),
            new JobBuddyProperties());
    List<Map<String, Object>> previousJobs =
        Collections.<Map<String, Object>>singletonList(
            Collections.<String, Object>singletonMap("securityId", "previous-job"));
    Map<String, Object> previousSlots = new LinkedHashMap<String, Object>();
    previousSlots.put("role", "Java");
    previousSlots.put("boss_page", Integer.valueOf(1));
    ChatSessionState state = new ChatSessionState();
    state.sessionId = "s1";
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    state.jobs = new java.util.ArrayList<Map<String, Object>>(previousJobs);
    state.lastSlots = new LinkedHashMap<String, Object>(previousSlots);
    Map<String, Object> failedSlots = new LinkedHashMap<String, Object>();
    failedSlots.put("role", "大模型应用开发");
    failedSlots.put("city", "上海");
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            1.0,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            failedSlots);

    handler.handle(new SseEmitter(0L), "s1", state, intent, false, "筛选上海大模型应用开发岗位");

    assertEquals(previousJobs, state.jobs);
    assertEquals(previousSlots, state.lastSlots);
    ArgumentCaptor<String> assistantCaptor = ArgumentCaptor.forClass(String.class);
    verify(sender)
        .sendAssistant(any(SseEmitter.class), eq("s1"), eq(state), assistantCaptor.capture());
    assertTrue(assistantCaptor.getValue().contains("岗位消息仍保留"));
    assertFalse(assistantCaptor.getValue().contains("换一批"));
    ArgumentCaptor<Map<String, Object>> statusCaptor = ArgumentCaptor.forClass((Class) Map.class);
    verify(sender, org.mockito.Mockito.times(4))
        .sendToolStatus(any(SseEmitter.class), eq("s1"), eq(state), statusCaptor.capture());
    Map<String, Object> errorDetail =
        (Map<String, Object>) statusCaptor.getAllValues().get(3).get("detail");
    assertEquals(Boolean.TRUE, errorDetail.get("retainedPreviousBatch"));
    assertEquals(1, errorDetail.get("previousJobCount"));
    verify(persistence).saveStateAsync(state);
  }

  /**
   * 验证 ChatSseCollaborators 中岗位的检索、筛选与排序规则。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void jobRecommendationShouldUpdateSearchCountAfterQualityGateContinuation() throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    ChatPersistenceCoordinator persistence = mock(ChatPersistenceCoordinator.class);
    JobRuntimeService jobRuntimeService = mock(JobRuntimeService.class);
    PersonalContextBuilder personalContextBuilder = mock(PersonalContextBuilder.class);
    CurrentResumeLoader resumeLoader = mock(CurrentResumeLoader.class);
    when(personalContextBuilder.build(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(
            new PersonalContext(
                "job",
                Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                ""));
    when(jobRuntimeService.bossCandidatePoolTimeoutSeconds()).thenReturn(30);
    List<Map<String, Object>> candidates = new java.util.ArrayList<Map<String, Object>>();
    for (int index = 1; index <= 14; index++) {
      candidates.add(Collections.<String, Object>singletonMap("jobName", "候选岗位 " + index));
    }
    List<Map<String, Object>> qualified = candidates.subList(0, 5);
    Map<String, Integer> rejectionReasons = new LinkedHashMap<String, Integer>();
    rejectionReasons.put("匹配置信度低", Integer.valueOf(11));
    rejectionReasons.put("未达到最低匹配分", Integer.valueOf(2));
    JobRecommendationResult quality =
        new JobRecommendationResult(
            qualified, 18, rejectionReasons, Collections.<String>emptyList());
    when(jobRuntimeService.recommendJobsFast(any(IntentResult.class), eq("s1"), any()))
        .thenReturn(candidates);
    when(resumeLoader.loadCurrentResume(any(ChatSessionState.class)))
        .thenReturn(mock(ResumeRecord.class));
    when(jobRuntimeService.prequalifyRecommendationsWithContinuation(
            any(ResumeRecord.class), any(IntentResult.class), eq(candidates), eq("s1")))
        .thenReturn(quality);
    JobRecommendHandler handler =
        new JobRecommendHandler(
            sender,
            persistence,
            jobRuntimeService,
            personalContextBuilder,
            resumeLoader,
            new JobBuddyProperties());
    ChatSessionState state = new ChatSessionState();
    state.sessionId = "s1";
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            1.0,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            Collections.<String, Object>singletonMap("role", "大模型应用开发"));

    handler.handle(new SseEmitter(0L), "s1", state, intent, false, "筛选大模型岗位");

    ArgumentCaptor<Map<String, Object>> statusCaptor = ArgumentCaptor.forClass((Class) Map.class);
    verify(sender, org.mockito.Mockito.times(7))
        .sendToolStatus(any(SseEmitter.class), eq("s1"), eq(state), statusCaptor.capture());
    assertEquals(
        java.util.Arrays.asList(
            "recommendation_context:running",
            "recommendation_context:success",
            "job_search:running",
            "job_search:success",
            "recommendation_quality_gate:running",
            "job_search:success",
            "recommendation_quality_gate:success"),
        statusCaptor.getAllValues().stream()
            .map(event -> event.get("id") + ":" + event.get("status"))
            .toList());
    List<Map<String, Object>> searchCompletedEvents =
        statusCaptor.getAllValues().stream()
            .filter(
                event ->
                    "job_search".equals(event.get("id")) && "success".equals(event.get("status")))
            .toList();
    assertEquals(2, searchCompletedEvents.size());
    Map<String, Object> initialSearchCompleted = searchCompletedEvents.get(0);
    Map<String, Object> initialSearchDetail =
        (Map<String, Object>) initialSearchCompleted.get("detail");
    assertEquals("累计检索到 14 个候选岗位。", initialSearchCompleted.get("summary"));
    assertEquals(14, initialSearchDetail.get("count"));
    Map<String, Object> searchCompleted = searchCompletedEvents.get(1);
    Map<String, Object> searchDetail = (Map<String, Object>) searchCompleted.get("detail");
    assertEquals("累计检索到 18 个候选岗位。", searchCompleted.get("summary"));
    assertEquals(18, searchDetail.get("count"));
    assertEquals(14, searchDetail.get("initialCandidateCount"));
    assertEquals(Boolean.TRUE, searchDetail.get("continuedSearch"));
    assertTrue(((Number) searchDetail.get("elapsedMs")).longValue() >= 0L);
    assertFalse(searchDetail.containsKey("qualifiedCount"));
    assertFalse(searchDetail.containsKey("rejectionReasons"));

    Map<String, Object> qualityCompleted =
        statusCaptor.getAllValues().stream()
            .filter(
                event ->
                    "recommendation_quality_gate".equals(event.get("id"))
                        && "success".equals(event.get("status")))
            .findFirst()
            .orElseThrow();
    Map<String, Object> qualityDetail = (Map<String, Object>) qualityCompleted.get("detail");
    assertEquals(18, qualityDetail.get("candidateCount"));
    assertEquals(5, qualityDetail.get("qualifiedCount"));
  }

  /**
   * 换一批成功是新的聊天轮次：岗位卡片应追加到新的助手消息，不覆盖历史助手消息。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void jobRecommendationFlipSuccessShouldAppendNewAssistantJobMessage() throws Exception {
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    ChatPersistenceCoordinator persistence = mock(ChatPersistenceCoordinator.class);
    JobRuntimeService jobRuntimeService = mock(JobRuntimeService.class);
    PersonalContextBuilder personalContextBuilder = mock(PersonalContextBuilder.class);
    CurrentResumeLoader resumeLoader = mock(CurrentResumeLoader.class);
    when(personalContextBuilder.build(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(
            new PersonalContext(
                "job",
                Collections.<String, Object>emptyMap(),
                Collections.<String, Object>emptyMap(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                Collections.<Map<String, Object>>emptyList(),
                ""));
    when(jobRuntimeService.bossCandidatePoolTimeoutSeconds()).thenReturn(30);
    List<Map<String, Object>> candidates =
        Collections.<Map<String, Object>>singletonList(
            Collections.<String, Object>singletonMap("securityId", "new-job"));
    when(jobRuntimeService.recommendJobsFast(any(IntentResult.class), eq("s1"), any()))
        .thenReturn(candidates);
    when(resumeLoader.loadCurrentResume(any(ChatSessionState.class)))
        .thenReturn(mock(ResumeRecord.class));
    when(jobRuntimeService.prequalifyRecommendationsWithContinuation(
            any(ResumeRecord.class), any(IntentResult.class), eq(candidates), eq("s1")))
        .thenReturn(
            new JobRecommendationResult(
                candidates,
                1,
                Collections.<String, Integer>emptyMap(),
                Collections.<String>emptyList()));
    JobRecommendHandler handler =
        new JobRecommendHandler(
            sender,
            persistence,
            jobRuntimeService,
            personalContextBuilder,
            resumeLoader,
            new JobBuddyProperties());
    ChatSessionState state = new ChatSessionState();
    state.sessionId = "s1";
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    state.jobs =
        new java.util.ArrayList<Map<String, Object>>(
            Collections.<Map<String, Object>>singletonList(
                Collections.<String, Object>singletonMap("securityId", "previous-job")));
    Map<String, Object> nextSlots = new LinkedHashMap<String, Object>();
    nextSlots.put("role", "大模型应用开发");
    nextSlots.put("boss_page", Integer.valueOf(2));
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            1.0,
            Collections.<String>emptyList(),
            "low",
            false,
            "call_get_recommend_jobs",
            nextSlots);

    handler.handle(new SseEmitter(0L), "s1", state, intent, true, "换一批");

    ArgumentCaptor<Map<String, Object>> metadataCaptor = ArgumentCaptor.forClass((Class) Map.class);
    verify(persistence)
        .appendMessageAsync(eq("s1"), eq("assistant"), eq(""), metadataCaptor.capture());
    verify(persistence, never()).replaceLatestJobMessageAsync(anyString(), anyList(), anyList());
    assertEquals(candidates, metadataCaptor.getValue().get("jobCards"));
    assertEquals(candidates, state.jobs);
    verify(persistence).saveStateAsync(state);
  }

  // ---- ChatSseEventSender ----

  /**
   * 验证 ChatSseCollaborators 的流式生命周期与中断边界。
   */
  @Test
  void sendShouldAbortWhenConnectionCancelled() {
    ConcurrentMap<SseEmitter, AtomicBoolean> cancelled =
        new ConcurrentHashMap<SseEmitter, AtomicBoolean>();
    SseEmitter emitter = new SseEmitter(0L);
    cancelled.put(emitter, new AtomicBoolean(true));
    ChatSseEventSender sender =
        new ChatSseEventSender(cancelled, mock(ChatPersistenceCoordinator.class));
    assertThrows(IOException.class, () -> sender.send(emitter, "message", "data"));
  }

  /**
   * 验证 ChatSseCollaborators 的失败恢复、超时与降级边界。
   */
  @Test
  void clientDisconnectClassifierShouldRecognizeContainerAndSocketErrors() {
    assertTrue(
        ChatSseServiceImpl.isClientDisconnect(
            new AsyncRequestNotUsableException("ServletOutputStream failed to flush")));
    assertTrue(
        ChatSseServiceImpl.isClientDisconnect(
            new IOException("write failed", new IOException("Broken pipe"))));
  }

  /**
   * 验证 ChatSseCollaborators 中工具的流式生命周期与中断边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void sendAssistantShouldSnapshotToolEventsAndPersistAsync() throws Exception {
    ChatPersistenceCoordinator persistence = mock(ChatPersistenceCoordinator.class);
    ChatSseEventSender sender =
        new ChatSseEventSender(new ConcurrentHashMap<SseEmitter, AtomicBoolean>(), persistence);
    ChatSessionState state = new ChatSessionState();
    state.sessionId = "s1";
    Map<String, Object> event = new LinkedHashMap<String, Object>();
    event.put("id", "job_search");
    event.put("status", "success");
    state.toolEvents.add(event);

    sender.sendAssistant(new SseEmitter(0L), "s1", state, "答案");

    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass((Class) Map.class);
    verify(persistence)
        .appendMessageAsync(eq("s1"), eq("assistant"), eq("答案"), metaCaptor.capture());
    List<?> persistedEvents = (List<?>) metaCaptor.getValue().get("toolEvents");
    assertEquals(1, persistedEvents.size());
    verify(persistence).saveStateAsync(state);
  }

  /**
   * 验证 ChatSseCollaborators 中工具的持久化与状态变更规则。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void sendToolStatusShouldAccumulateEventWithoutPersisting() throws Exception {
    ChatPersistenceCoordinator persistence = mock(ChatPersistenceCoordinator.class);
    ChatSseEventSender sender =
        new ChatSseEventSender(new ConcurrentHashMap<SseEmitter, AtomicBoolean>(), persistence);
    ChatSessionState state = new ChatSessionState();
    Map<String, Object> status = new LinkedHashMap<String, Object>();
    status.put("id", "job_search");
    status.put("status", "running");

    sender.sendToolStatus(new SseEmitter(0L), "s1", state, status);

    assertEquals(1, state.toolEvents.size());
    verify(persistence, never()).saveStateAsync(any(ChatSessionState.class));
  }

  // ---- ChatPersistenceCoordinator ----

  /**
   * 验证 ChatSseCollaborators 的流式生命周期与中断边界。
   *
   * @param store 存储
   * @return 对话持久化协调器
   */
  private ChatPersistenceCoordinator newCoordinator(ChatSessionStore store) {
    return new ChatPersistenceCoordinator(
        store,
        runnable -> {
          Thread thread = new Thread(runnable, "test-persist");
          thread.setDaemon(true);
          return thread;
        });
  }

  /**
   * 验证 ChatSseCollaborators 的流式生命周期与中断边界。
   */
  @Test
  void appendMessageAsyncShouldFlushInOrder() {
    ChatSessionStore store = mock(ChatSessionStore.class);
    ChatPersistenceCoordinator coordinator = newCoordinator(store);
    coordinator.appendMessageAsync("s1", "user", "你好", null);
    Map<String, Object> metadata = Collections.<String, Object>singletonMap("k", "v");
    coordinator.appendMessageAsync("s1", "assistant", "答案", metadata);
    coordinator.awaitPersistFlush();
    verify(store).appendMessage("s1", "user", "你好");
    verify(store).appendMessage("s1", "assistant", "答案", metadata);
    coordinator.shutdown();
  }

  /**
   * 验证 ChatSseCollaborators 中岗位的输入校验与拒绝边界。
   */
  @Test
  void replaceLatestJobMessageShouldFallBackToAppendWhenMissing() {
    ChatSessionStore store = mock(ChatSessionStore.class);
    when(store.replaceLatestAssistantJobMessage(anyString(), anyList(), anyList()))
        .thenReturn(false);
    ChatPersistenceCoordinator coordinator = newCoordinator(store);
    List<Map<String, Object>> jobs =
        Arrays.<Map<String, Object>>asList(
            Collections.<String, Object>singletonMap("jobName", "后端工程师"));
    coordinator.replaceLatestJobMessageAsync("s1", jobs, null);
    coordinator.awaitPersistFlush();
    ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass((Class) Map.class);
    verify(store).appendMessage(eq("s1"), eq("assistant"), eq(""), metaCaptor.capture());
    assertEquals(jobs, metaCaptor.getValue().get("jobCards"));
    coordinator.shutdown();
  }

  /**
   * 验证 ChatSseCollaborators 中岗位的核心业务契约。
   */
  @Test
  void replaceLatestJobMessageShouldNotAppendWhenReplaced() {
    ChatSessionStore store = mock(ChatSessionStore.class);
    when(store.replaceLatestAssistantJobMessage(anyString(), anyList(), anyList()))
        .thenReturn(true);
    ChatPersistenceCoordinator coordinator = newCoordinator(store);
    coordinator.replaceLatestJobMessageAsync(
        "s1",
        Collections.<Map<String, Object>>emptyList(),
        Collections.<Map<String, Object>>emptyList());
    coordinator.awaitPersistFlush();
    verify(store, never()).appendMessage(anyString(), anyString(), anyString(), anyMap());
    coordinator.shutdown();
  }

  /**
   * 验证 ChatSseCollaborators 的失败恢复、超时与降级边界。
   */
  @Test
  void persistFailureShouldNotBreakSubsequentTasks() {
    ChatSessionStore store = mock(ChatSessionStore.class);
    doThrow(new RuntimeException("db down")).when(store).save(any(ChatSessionState.class));
    ChatPersistenceCoordinator coordinator = newCoordinator(store);
    ChatSessionState state = new ChatSessionState();
    state.sessionId = "s1";
    coordinator.saveStateAsync(state);
    coordinator.appendMessageAsync("s1", "user", "你好", null);
    coordinator.awaitPersistFlush();
    verify(store).appendMessage("s1", "user", "你好");
    coordinator.shutdown();
  }

  // ---- RuntimeManagedRequestFactory ----

  /**
   * 验证 ChatSseCollaborators 中运行时的数据转换与协议契约。
   */
  @Test
  void buildRuntimeManagedRequestShouldCarryBudgetAndMetadata() {
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setRuntimeMaxTurns(9);
    properties.setRuntimeMaxToolCalls(14);
    properties.setRuntimeMaxFailures(4);
    RuntimeManagedRequestFactory factory =
        new RuntimeManagedRequestFactory(
            mock(AgentIntegrationService.class), mock(PersonalContextBuilder.class), properties);
    Map<String, Object> extra = Collections.<String, Object>singletonMap("entrypoint", "chat.ask");

    RuntimeRunRequest runtimeRequest =
        factory.buildRuntimeManagedRequest("s1", "帮我找岗位", "job_buddy", extra, true);
    Map<String, Object> request =
        runtimeRequest.toJson().isEmpty()
            ? Collections.<String, Object>emptyMap()
            : new JsonCodec().toMap(runtimeRequest.toJson());

    assertEquals("s1", request.get("session_id"));
    assertEquals(Boolean.TRUE, request.get("stream"));
    List<?> messages = (List<?>) request.get("messages");
    assertEquals(1, messages.size());
    assertEquals("帮我找岗位", ((Map<?, ?>) messages.get(0)).get("content"));
    Map<?, ?> budget = (Map<?, ?>) request.get("budget");
    assertEquals(9, budget.get("max_turns"));
    assertEquals(14, budget.get("max_tool_calls"));
    assertEquals(4, budget.get("max_failures"));
    Map<?, ?> metadata = (Map<?, ?>) request.get("metadata");
    assertEquals("job_buddy", metadata.get("profile"));
    assertEquals("chat.ask", metadata.get("entrypoint"));
  }

  /**
   * 验证 ChatSseCollaborators 的失败恢复、超时与降级边界。
   */
  @Test
  void buildPersonalContextShouldDegradeToEmptyOnFailure() {
    PersonalContextBuilder builder = mock(PersonalContextBuilder.class);
    when(builder.build(anyString(), anyString(), anyString(), any(), any()))
        .thenThrow(new RuntimeException("profile missing"));
    RuntimeManagedRequestFactory factory =
        new RuntimeManagedRequestFactory(
            mock(AgentIntegrationService.class), builder, new JobBuddyProperties());
    ChatSessionState state = new ChatSessionState();
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    Map<String, Object> context = factory.buildPersonalContext("消息", null, state);
    assertTrue(context.isEmpty());
  }

  /**
   * 验证 ChatSseCollaborators 中简历的核心业务契约。
   */
  @Test
  void buildUnderstandingContextShouldUseSessionCatalogWithoutLoadingPersonalContext() {
    PersonalContextBuilder builder = mock(PersonalContextBuilder.class);
    ResumeStorageService resumeStorageService = mock(ResumeStorageService.class);
    ResumeRecord resume = new ResumeRecord();
    Map<String, Object> parsedResume = new LinkedHashMap<String, Object>();
    parsedResume.put("targetRole", "云原生平台研发");
    parsedResume.put("currentTitle", "Go 平台研发工程师");
    parsedResume.put("skills", Arrays.asList("Go", "Kubernetes", "PostgreSQL"));
    parsedResume.put(
        "projects",
        Arrays.asList(
            Collections.<String, Object>singletonMap(
                "description", "FULL_RESUME_PROJECT_SHOULD_NOT_ENTER_INTENT_PROMPT")));
    resume.setParsed(parsedResume);
    when(resumeStorageService.get("resume-5-years", "tenant-a", "user-a")).thenReturn(resume);
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("securityId", "job-1");
    job.put("jobName", "云原生平台开发岗");
    job.put("brandName", "星河云科（虚构）");
    job.put("jobDescription", "FULL_JOB_DESCRIPTION_SHOULD_NOT_ENTER_INTENT_PROMPT_这是完整岗位正文");
    RuntimeManagedRequestFactory factory =
        new RuntimeManagedRequestFactory(
            mock(AgentIntegrationService.class),
            builder,
            resumeStorageService,
            new JobBuddyProperties());
    ChatSessionState state = new ChatSessionState();
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    state.resumeId = "resume-5-years";
    state.jobs = Arrays.asList(job);
    IntentResult intent =
        new IntentResult(
            "job",
            "job.recommend",
            0.88,
            Collections.<String>emptyList(),
            "low",
            false,
            "run_job_recommend",
            Collections.<String, Object>emptyMap());

    Map<String, Object> compact =
        factory.buildUnderstandingContext("根据当前简历推荐适合我的岗位", intent, state);

    String serialized = String.valueOf(compact);
    assertFalse(serialized.contains("FULL_JOB_DESCRIPTION"));
    assertFalse(serialized.contains("FULL_RESUME_PROJECT"));
    assertEquals("job.recommend", compact.get("task_type"));
    Map<?, ?> resumeRef = (Map<?, ?>) compact.get("resume_ref");
    assertEquals(Boolean.TRUE, resumeRef.get("available"));
    assertEquals("云原生平台研发", resumeRef.get("targetRole"));
    assertEquals("Go 平台研发工程师", resumeRef.get("current_title"));
    assertEquals(3, resumeRef.get("skills_count"));
    assertEquals(1, resumeRef.get("projects_count"));
    List<?> jobRefs = (List<?>) compact.get("current_job_refs");
    assertEquals("job-1", ((Map<?, ?>) jobRefs.get(0)).get("securityId"));
    assertEquals(Boolean.TRUE, ((Map<?, ?>) jobRefs.get(0)).get("has_job_description"));
    verifyNoInteractions(builder);
    verify(resumeStorageService).get("resume-5-years", "tenant-a", "user-a");
  }

  /**
   * 验证简历匹配的任务理解只使用会话中的简历可用性标记，不为路由额外查询数据库。
   */
  @Test
  void buildUnderstandingContextForResumeMatchShouldNotLoadResumeRecord() {
    PersonalContextBuilder builder = mock(PersonalContextBuilder.class);
    ResumeStorageService resumeStorageService = mock(ResumeStorageService.class);
    RuntimeManagedRequestFactory factory =
        new RuntimeManagedRequestFactory(
            mock(AgentIntegrationService.class),
            builder,
            resumeStorageService,
            new JobBuddyProperties());
    ChatSessionState state = new ChatSessionState();
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    state.resumeId = "resume-5-years";
    IntentResult intent =
        new IntentResult(
            "job",
            "resume.match",
            0.88,
            Collections.<String>emptyList(),
            "low",
            false,
            "run_resume_match",
            Collections.<String, Object>emptyMap());

    Map<String, Object> compact =
        factory.buildUnderstandingContext("分析当前简历与目标岗位的匹配度", intent, state);

    assertEquals("resume.match", compact.get("task_type"));
    Map<?, ?> resumeRef = (Map<?, ?>) compact.get("resume_ref");
    assertEquals(Collections.singletonMap("available", true), resumeRef);
    verifyNoInteractions(builder, resumeStorageService);
  }

  /**
   * 验证 ChatSseCollaborators 中运行时的数据转换与协议契约。
   */
  @Test
  void runtimeManagedMetadataShouldToleranteNullState() {
    RuntimeManagedRequestFactory factory =
        new RuntimeManagedRequestFactory(
            mock(AgentIntegrationService.class),
            mock(PersonalContextBuilder.class),
            new JobBuddyProperties());
    Map<String, Object> metadata = factory.runtimeManagedMetadata("消息", null, null, null);
    assertEquals(Boolean.TRUE, metadata.get("runtime_execute"));
    assertEquals("chat.ask", metadata.get("entrypoint"));
    assertEquals(0, metadata.get("current_jobs_count"));
    assertEquals(Collections.emptyMap(), metadata.get("previous_slots"));
    assertEquals(Collections.emptyMap(), metadata.get("upstream_directive"));
  }

  /**
   * 验证 Runtime 直达合成请求保留本轮附件正文，不能只传公开引用。
   */
  @Test
  void runtimeManagedMetadataShouldCarryAttachmentContent() {
    RuntimeManagedRequestFactory factory =
        new RuntimeManagedRequestFactory(
            mock(AgentIntegrationService.class),
            mock(PersonalContextBuilder.class),
            new JobBuddyProperties());
    ChatSessionState state = new ChatSessionState();
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    Map<String, Object> attachment = new LinkedHashMap<String, Object>();
    attachment.put("attachmentId", "att-proof");
    attachment.put("fileName", "project.md");
    attachment.put("content", "BACKEND_ATTACHMENT_SENTINEL_91");
    attachment.put("untrusted", true);
    state.attachments = Collections.singletonList(attachment);

    Map<String, Object> metadata =
        factory.runtimeManagedMetadata("总结附件", state, Collections.<String, Object>emptyMap(), null);

    List<?> attachments = (List<?>) metadata.get("attachments");
    assertEquals(1, attachments.size());
    assertEquals("BACKEND_ATTACHMENT_SENTINEL_91", ((Map<?, ?>) attachments.get(0)).get("content"));
    assertEquals(Boolean.TRUE, ((Map<?, ?>) attachments.get(0)).get("untrusted"));
  }

  /**
   * 验证 Runtime 的沙箱代码执行结果会转换为独立过程事件，避免只显示泛化的“Runtime 已返回回答”。
   */
  @Test
  void sandboxCodeExecutionShouldExposeAuditableToolStatus() {
    String code = "text = 'JobBuddy'\nprint(text.count('d'))";
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("language", "python");
    output.put("exit_code", 0);
    output.put("stdout", "2\n");
    output.put("stderr", "");
    output.put("sandboxed", true);
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("tool_name", "sandbox_code_execute");
    toolResult.put("success", true);
    toolResult.put("output", output);
    toolResult.put("latency_ms", 37);
    toolResult.put(
        "metadata",
        Collections.singletonMap(
            "execution_detail",
            Map.of(
                "code",
                code,
                "code_chars",
                code.length(),
                "code_sha256",
                "a".repeat(64),
                "code_truncated",
                false)));
    Map<String, Object> runtimeResult = new LinkedHashMap<String, Object>();
    runtimeResult.put("tool_results", Collections.singletonList(toolResult));

    Map<String, Object> status = RuntimeManagedTaskHandler.sandboxExecutionStatus(runtimeResult);

    assertEquals("runtime_sandbox_code_execute", status.get("id"));
    assertEquals("沙箱代码执行", status.get("title"));
    assertEquals("success", status.get("status"));
    assertTrue(String.valueOf(status.get("summary")).contains("agent-sandbox"));
    Map<?, ?> detail = (Map<?, ?>) status.get("detail");
    assertEquals(Boolean.TRUE, detail.get("sandboxed"));
    assertEquals("python", detail.get("language"));
    assertEquals(0, detail.get("exitCode"));
    assertEquals(2, detail.get("outputChars"));
    assertTrue(String.valueOf(detail.get("outputSha256")).matches("[0-9a-f]{64}"));
    assertEquals(code, detail.get("code"));
    assertEquals(code.length(), detail.get("codeChars"));
    assertEquals(Boolean.FALSE, detail.get("codeTruncated"));
    assertEquals("a".repeat(64), detail.get("codeSha256"));
    assertEquals("2\n", detail.get("stdout"));
    assertEquals("", detail.get("stderr"));
    assertFalse(detail.containsKey("args"));
  }

  /**
   * 验证候选代码非零退出时仍保留沙箱证据，但过程和 Runtime 终态都不能被标记为成功。
   */
  @Test
  void sandboxCodeExecutionFailureShouldExposeAuditableToolStatus() {
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("language", "python");
    output.put("exit_code", 7);
    output.put("stdout", "partial output");
    output.put("stderr", "candidate failed");
    output.put("sandboxed", true);
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("tool_name", "sandbox_code_execute");
    toolResult.put("success", false);
    toolResult.put("output", output);
    toolResult.put("error", "候选代码执行失败");
    toolResult.put(
        "metadata",
        Collections.singletonMap(
            "execution_detail",
            Map.of(
                "code",
                "raise SystemExit(7)",
                "code_chars",
                19,
                "code_sha256",
                "b".repeat(64),
                "code_truncated",
                false)));
    Map<String, Object> runtimeResult = new LinkedHashMap<String, Object>();
    runtimeResult.put("status", "fail");
    runtimeResult.put("tool_results", Collections.singletonList(toolResult));

    Map<String, Object> status = RuntimeManagedTaskHandler.sandboxExecutionStatus(runtimeResult);

    assertEquals("error", status.get("status"));
    Map<?, ?> detail = (Map<?, ?>) status.get("detail");
    assertEquals(Boolean.TRUE, detail.get("sandboxed"));
    assertEquals(7, detail.get("exitCode"));
    assertEquals("sandbox_execution_failed", detail.get("errorCategory"));
    assertEquals("raise SystemExit(7)", detail.get("code"));
    assertEquals("partial output", detail.get("stdout"));
    assertEquals("candidate failed", detail.get("stderr"));
    assertFalse(detail.containsKey("error"));
    assertTrue(RuntimeManagedTaskHandler.explicitRuntimeFailure(runtimeResult));
  }

  /**
   * 验证代码执行详情在进入聊天记录前再次限长，并明确标记截断，避免大段模型源码或进程输出撑爆 SSE 与消息元数据。
   */
  @Test
  void sandboxCodeExecutionDetailShouldBeBounded() {
    String code = "c".repeat(40001);
    String stdout = "o".repeat(12001);
    String stderr = "e".repeat(12002);
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("language", "python");
    output.put("exit_code", 0);
    output.put("stdout", stdout);
    output.put("stderr", stderr);
    output.put("sandboxed", true);
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("tool_name", "sandbox_code_execute");
    toolResult.put("success", true);
    toolResult.put("output", output);
    toolResult.put(
        "metadata",
        Collections.singletonMap(
            "execution_detail",
            Map.of(
                "code",
                code.substring(0, 40000),
                "code_chars",
                code.length(),
                "code_truncated",
                false)));
    Map<String, Object> runtimeResult = new LinkedHashMap<String, Object>();
    runtimeResult.put("tool_results", Collections.singletonList(toolResult));

    Map<?, ?> detail =
        (Map<?, ?>) RuntimeManagedTaskHandler.sandboxExecutionStatus(runtimeResult).get("detail");

    assertEquals(40000, String.valueOf(detail.get("code")).length());
    assertEquals(40001, detail.get("codeChars"));
    assertEquals(Boolean.TRUE, detail.get("codeTruncated"));
    assertEquals(12000, String.valueOf(detail.get("stdout")).length());
    assertEquals(Boolean.TRUE, detail.get("stdoutTruncated"));
    assertEquals(12000, String.valueOf(detail.get("stderr")).length());
    assertEquals(Boolean.TRUE, detail.get("stderrTruncated"));
  }

  /**
   * 验证联网搜索在 Runtime 长任务开始前先产生进行中事件，避免用户只能在终态看到搜索结果。
   */
  @Test
  void webSearchShouldExposeRunningStatusBeforeRuntimeCompletes() {
    Map<String, Object> directive = new LinkedHashMap<String, Object>();
    directive.put(
        "capability_contract",
        Collections.<String, Object>singletonMap("required_tools", List.of("web_search")));
    directive.put(
        "task",
        Collections.<String, Object>singletonMap(
            "rewritten_query",
            Collections.<String, Object>singletonMap("retrieval_query", "OpenAI 最新模型 官方发布")));

    Map<String, Object> status =
        RuntimeManagedTaskHandler.webSearchRunningStatus("查找 OpenAI 最新模型", directive);

    assertEquals("runtime_web_search", status.get("id"));
    assertEquals("联网搜索", status.get("title"));
    assertEquals("running", status.get("status"));
    assertTrue(String.valueOf(status.get("summary")).contains("准备查询"));
    Map<?, ?> detail = (Map<?, ?>) status.get("detail");
    assertEquals("OpenAI 最新模型 官方发布", detail.get("query"));
    assertEquals("query_preparation", detail.get("stage"));
    Map<String, Object> missingResult =
        RuntimeManagedTaskHandler.webSearchMissingResultStatus(status);
    assertEquals("error", missingResult.get("status"));
    assertTrue(String.valueOf(missingResult.get("summary")).contains("未返回可审计来源"));
    assertEquals("OpenAI 最新模型 官方发布", ((Map<?, ?>) missingResult.get("detail")).get("query"));
    assertEquals("result_missing", ((Map<?, ?>) missingResult.get("detail")).get("stage"));
    assertTrue(
        RuntimeManagedTaskHandler.webSearchRunningStatus(
                "解释 Java volatile", Collections.<String, Object>emptyMap())
            .isEmpty());
  }

  /**
   * 验证任一 required tool 已进入执行链路时，空流不会触发整任务非流式重放。
   */
  @Test
  void requiredToolTaskShouldNeverUseWholeRequestFallback() {
    Map<String, Object> directive = new LinkedHashMap<String, Object>();
    directive.put(
        "capability_contract",
        Collections.<String, Object>singletonMap("required_tools", List.of("web_search")));

    assertFalse(RuntimeManagedTaskHandler.shouldFallbackToNonStream(true, false, false, directive));
    assertTrue(
        RuntimeManagedTaskHandler.shouldFallbackToNonStream(
            true, false, false, Collections.<String, Object>emptyMap()));
    assertFalse(
        RuntimeManagedTaskHandler.shouldFallbackToNonStream(
            true, true, false, Collections.<String, Object>emptyMap()));
  }

  /**
   * 验证 Runtime 的联网搜索结果会转换为独立过程事件，并只投影有界来源摘要。
   */
  @Test
  void webSearchShouldExposeAuditableToolStatus() {
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Collections.<String, Object>emptyMap(), Collections.<String, Object>emptyMap());

    Map<String, Object> status = RuntimeManagedTaskHandler.webSearchExecutionStatus(runtimeResult);

    assertEquals("runtime_web_search", status.get("id"));
    assertEquals("联网搜索", status.get("title"));
    assertEquals("success", status.get("status"));
    Map<?, ?> detail = (Map<?, ?>) status.get("detail");
    assertEquals("Anthropic latest engineering blog", detail.get("query"));
    assertEquals(
        List.of(
            "Anthropic latest engineering blog", "Anthropic latest engineering blog 2026 official"),
        detail.get("queries"));
    assertEquals("bocha_web", detail.get("provider"));
    assertEquals(3, detail.get("rawCount"));
    assertEquals(1, detail.get("deduplicatedCount"));
    assertEquals(List.of("anthropic.com"), detail.get("preferredSourceDomains"));
    assertEquals(
        List.of("anthropic.com", "www.anthropic.com"), detail.get("preferredSourceTrustedHosts"));
    assertEquals(true, detail.get("preferredSourceFound"));
    assertEquals(1, detail.get("officialSourceCount"));
    assertEquals(0, detail.get("thirdPartySourceCount"));
    assertEquals("configured_official_index", detail.get("officialVerification"));
    assertEquals("latest", detail.get("selectionMode"));
    assertEquals("2026-08-01", detail.get("asOfDate"));
    assertEquals("", detail.get("timeRangeStart"));
    assertEquals("engineering_blog", detail.get("contentScope"));
    assertFalse(detail.containsKey("latestEvidenceVerified"));
    assertFalse(detail.containsKey("selectionBasis"));
    assertFalse(detail.containsKey("catalogUrl"));
    assertFalse(detail.containsKey("selectedUrl"));
    assertFalse(detail.containsKey("selectedPublishedDate"));
    assertFalse(detail.containsKey("candidateCount"));
    assertEquals(2, detail.get("sourceCount"));
    List<?> sources = (List<?>) detail.get("sources");
    assertEquals("How we contain Claude", ((Map<?, ?>) sources.get(0)).get("title"));
    assertEquals("2026-05-25", ((Map<?, ?>) sources.get(0)).get("publishedDate"));
    assertEquals("official_detail", ((Map<?, ?>) sources.get(0)).get("publishedDateSource"));
    assertFalse(((Map<?, ?>) sources.get(0)).containsKey("latest"));
    assertEquals(
        "https://www.anthropic.com/engineering/how-we-contain-claude",
        ((Map<?, ?>) sources.get(0)).get("url"));
    assertEquals("official", ((Map<?, ?>) sources.get(0)).get("sourceTier"));
    assertEquals(
        "configured_official_index", ((Map<?, ?>) sources.get(0)).get("verificationMethod"));
    assertTrue(String.valueOf(status).contains("Claude Fable 5"));
    assertFalse(String.valueOf(status).contains("不应进入过程事件的长摘要"));
  }

  /**
   * 官方来源未命中时仍应把第三方结果作为普通可引用来源展示，不额外显示降级提示。
   */
  @Test
  void webSearchShouldReportThirdPartyFallbackWhenOfficialSourceIsMissing() {
    Map<String, Object> thirdPartySource = new LinkedHashMap<String, Object>();
    thirdPartySource.put("title", "Independent model report");
    thirdPartySource.put("url", "https://news.example.com/model-report");
    thirdPartySource.put("source_tier", "third_party");
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Map.of(
                "preferred_source_found",
                false,
                "official_source_count",
                0,
                "third_party_source_count",
                1,
                "official_verification",
                "not_found",
                "results",
                List.of(thirdPartySource)),
            Collections.<String, Object>emptyMap());

    Map<String, Object> status = RuntimeManagedTaskHandler.webSearchExecutionStatus(runtimeResult);

    assertEquals("success", status.get("status"));
    assertEquals("联网搜索已完成，取得 1 个可引用来源。", status.get("summary"));
    assertEquals(false, ((Map<?, ?>) status.get("detail")).get("preferredSourceFound"));
  }

  /**
   * selected_url 与投影来源不一致时，即使 Runtime 声称已核验也必须失败关闭。
   */
  @Test
  void latestWebSearchShouldRejectMismatchedSelectedUrl() {
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Map.of("selected_url", "https://www.anthropic.com/engineering/april-23-postmortem"),
            Collections.<String, Object>emptyMap());

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * latest_result_url 不能与 selected_url 指向不同文章。
   */
  @Test
  void latestWebSearchShouldRejectMismatchedLatestResultUrl() {
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Map.of(
                "latest_result_url", "https://www.anthropic.com/engineering/april-23-postmortem"),
            Collections.<String, Object>emptyMap());

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * 官网发布日期晚于本轮截止日时不能作为“最新”证据。
   */
  @Test
  void latestWebSearchShouldRejectFuturePublishedDate() {
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Map.of("selected_published_date", "2026-08-02"),
            Map.of("published_date", "2026-08-02"));

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * latest 的截止日不能晚于 Backend 当前日期，否则未来窗口会把不可观测结果升级为事实。
   */
  @Test
  void latestWebSearchShouldRejectFutureAsOfDate() {
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Map.of("as_of_date", LocalDate.now().plusDays(1).toString()),
            Collections.<String, Object>emptyMap());

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * selected_published_date 必须与投影来源的 published_date 完全一致。
   */
  @Test
  void latestWebSearchShouldRejectMismatchedProjectedPublishedDate() {
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Collections.<String, Object>emptyMap(), Map.of("published_date", "2026-04-23"));

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * catalog 最新性只能接受官网目录或官网正文提供的发布日期。
   */
  @Test
  void latestWebSearchShouldRejectUnverifiedPublishedDateSource() {
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Collections.<String, Object>emptyMap(),
            Map.of("published_date_source", "search_provider"));

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * 带时间下界的“某时段内最新”必须拒绝早于下界的文章。
   */
  @Test
  void latestWebSearchShouldRejectPublishedDateBeforeTimeRangeStart() {
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Map.of("time_range_start", "2026-06-01"), Collections.<String, Object>emptyMap());

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * engineering_blog 语义只能接受官网 /engineering/ 栏目文章。
   */
  @Test
  void latestWebSearchShouldRejectWrongEngineeringPath() {
    String newsUrl = "https://www.anthropic.com/news/claude-opus-4-7";
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(Map.of("selected_url", newsUrl), Map.of("url", newsUrl));

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * preferred_source_domains 只允许精确主机或 www 变体，不能信任任意子域。
   */
  @Test
  void latestWebSearchShouldRejectUntrustedOfficialSubdomain() {
    String subdomainUrl = "https://engineering.anthropic.com/engineering/how-we-contain-claude";
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Map.of("selected_url", subdomainUrl), Map.of("url", subdomainUrl));

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * 官方最新性证据只接受 HTTPS 默认端口，不能降级到 HTTP 或任意端口。
   */
  @Test
  void latestWebSearchShouldRejectUnsafeOfficialSchemeOrPort() {
    String httpUrl = "http://www.anthropic.com/engineering/how-we-contain-claude";
    assertLatestWebSearchRejected(
        latestWebSearchRuntimeResult(Map.of("selected_url", httpUrl), Map.of("url", httpUrl)));

    String customPortUrl = "https://www.anthropic.com:444/engineering/how-we-contain-claude";
    assertLatestWebSearchRejected(
        latestWebSearchRuntimeResult(
            Map.of("selected_url", customPortUrl), Map.of("url", customPortUrl)));
  }

  /**
   * catalog selection basis 必须由 configured_official_index 来源行支撑。
   */
  @Test
  void latestWebSearchShouldRejectMismatchedVerificationMethod() {
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Collections.<String, Object>emptyMap(),
            Map.of("verification_method", "configured_direct_fetch"));

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * 整体核验方式也必须与 selection_basis 对应，不能只看来源行。
   */
  @Test
  void latestWebSearchShouldRejectMismatchedOfficialVerification() {
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Map.of("official_verification", "configured_direct_fetch"),
            Collections.<String, Object>emptyMap());

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * selected_url 对应多个投影来源时证据存在歧义，必须失败关闭。
   */
  @Test
  void latestWebSearchShouldRejectDuplicateSelectedSources() {
    Map<String, Object> duplicate = latestOfficialSource(Collections.<String, Object>emptyMap());
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Map.of(
                "results",
                List.of(latestOfficialSource(Collections.<String, Object>emptyMap()), duplicate)),
            Collections.<String, Object>emptyMap());

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * selected_url 对应来源未明确标记 is_latest=true 时不能成功。
   */
  @Test
  void latestWebSearchShouldRejectSourceWithoutLatestMarker() {
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Collections.<String, Object>emptyMap(), Map.of("is_latest", false));

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * latest 标记必须是 JSON boolean，不能把字符串真值升级为已核验证据。
   */
  @Test
  void latestWebSearchShouldRejectStringLatestMarker() {
    Map<String, Object> runtimeResult =
        latestWebSearchRuntimeResult(
            Collections.<String, Object>emptyMap(), Map.of("is_latest", "true"));

    assertLatestWebSearchRejected(runtimeResult);
  }

  /**
   * canonical snapshot 可以位于配置明确列出的官网子域，但不能依赖根域模糊放行。
   */
  @Test
  void latestCanonicalSnapshotShouldAcceptConfiguredTrustedHost() {
    String modelsUrl = "https://developers.openai.com/api/docs/models";

    Map<String, Object> status =
        RuntimeManagedTaskHandler.webSearchExecutionStatus(
            latestCanonicalSnapshotRuntimeResult(modelsUrl, modelsUrl));

    assertEquals("success", status.get("status"));
    Map<?, ?> detail = (Map<?, ?>) status.get("detail");
    assertEquals(
        List.of("openai.com", "developers.openai.com"), detail.get("preferredSourceTrustedHosts"));
    assertFalse(detail.containsKey("latestEvidenceVerified"));
    assertFalse(detail.containsKey("selectedUrl"));
  }

  /**
   * canonical snapshot 的配置可信列表没有包含任意 OpenAI 子域时必须失败关闭。
   */
  @Test
  void latestCanonicalSnapshotShouldRejectUnlistedSubdomain() {
    String unlistedUrl = "https://evil.openai.com/api/docs/models";

    assertLatestWebSearchRejected(latestCanonicalSnapshotRuntimeResult(unlistedUrl, unlistedUrl));
  }

  /**
   * canonical snapshot 的 catalog_url 必须与 selected_url 绑定，不能混用另一入口。
   */
  @Test
  void latestCanonicalSnapshotShouldRejectMismatchedCatalogUrl() {
    assertLatestWebSearchRejected(
        latestCanonicalSnapshotRuntimeResult(
            "https://developers.openai.com/api/docs/models",
            "https://platform.openai.com/docs/models"));
  }

  /**
   * 当前官方快照不能证明历史时间段内的“最新”，即使 URL 与来源行都合法。
   */
  @Test
  void latestCanonicalSnapshotShouldRejectHistoricalBounds() {
    Map<String, Object> runtimeResult =
        latestCanonicalSnapshotRuntimeResult(
            "https://developers.openai.com/api/docs/models",
            "https://developers.openai.com/api/docs/models",
            Map.of("time_range_start", "2024-01-01", "as_of_date", "2024-12-31"));

    assertLatestWebSearchRejected(runtimeResult);
  }

  private static void assertLatestWebSearchRejected(Map<String, Object> runtimeResult) {
    Map<String, Object> status = RuntimeManagedTaskHandler.webSearchExecutionStatus(runtimeResult);

    assertEquals("success", status.get("status"));
    Map<?, ?> detail = (Map<?, ?>) status.get("detail");
    assertFalse(detail.containsKey("latestEvidenceVerified"));
    assertFalse(detail.containsKey("selectionBasis"));
    assertFalse(detail.containsKey("selectedUrl"));
  }

  private static Map<String, Object> latestWebSearchRuntimeResult(
      Map<String, Object> outputOverrides, Map<String, Object> sourceOverrides) {
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("query", "Anthropic latest engineering blog");
    output.put(
        "queries",
        List.of(
            "Anthropic latest engineering blog",
            "Anthropic latest engineering blog 2026 official"));
    output.put("source", "bocha_web");
    output.put("raw_count", 3);
    output.put("deduplicated_count", 1);
    output.put("preferred_source_domains", List.of("anthropic.com"));
    output.put("preferred_source_trusted_hosts", List.of("anthropic.com", "www.anthropic.com"));
    output.put("preferred_source_found", true);
    output.put("official_source_count", 1);
    output.put("third_party_source_count", 0);
    output.put("official_verification", "configured_official_index");
    output.put("selection_mode", "latest");
    output.put("time_range_start", "");
    output.put("as_of_date", "2026-08-01");
    output.put("content_scope", "engineering_blog");
    output.put("latest_evidence_verified", true);
    output.put("selection_basis", "official_catalog_published_at");
    output.put("catalog_url", "https://www.anthropic.com/engineering");
    output.put("selected_url", "https://www.anthropic.com/engineering/how-we-contain-claude");
    output.put("latest_result_url", "https://www.anthropic.com/engineering/how-we-contain-claude");
    output.put("selected_published_date", "2026-05-25");
    output.put("candidate_count", 25);
    output.putAll(outputOverrides);
    if (!outputOverrides.containsKey("results")) {
      Map<String, Object> outOfScopeSource = new LinkedHashMap<String, Object>();
      outOfScopeSource.put("title", "Claude Fable 5");
      outOfScopeSource.put("url", "https://www.anthropic.com/claude/fable");
      outOfScopeSource.put("source_tier", "official_out_of_scope");
      output.put("results", List.of(latestOfficialSource(sourceOverrides), outOfScopeSource));
    }
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("tool_name", "web_search");
    toolResult.put("success", true);
    toolResult.put("output", output);
    toolResult.put("latency_ms", 128);
    return Collections.<String, Object>singletonMap(
        "tool_results", Collections.singletonList(toolResult));
  }

  private static Map<String, Object> latestOfficialSource(Map<String, Object> overrides) {
    Map<String, Object> source = new LinkedHashMap<String, Object>();
    source.put("title", "How we contain Claude");
    source.put("url", "https://www.anthropic.com/engineering/how-we-contain-claude");
    source.put("source_tier", "official");
    source.put("verification_method", "configured_official_index");
    source.put("published_date", "2026-05-25");
    source.put("published_date_source", "official_detail");
    source.put("is_latest", true);
    source.put("snippet", "不应进入过程事件的长摘要");
    source.putAll(overrides);
    return source;
  }

  private static Map<String, Object> latestCanonicalSnapshotRuntimeResult(
      String selectedUrl, String catalogUrl) {
    return latestCanonicalSnapshotRuntimeResult(
        selectedUrl, catalogUrl, Collections.<String, Object>emptyMap());
  }

  private static Map<String, Object> latestCanonicalSnapshotRuntimeResult(
      String selectedUrl, String catalogUrl, Map<String, Object> outputOverrides) {
    Map<String, Object> source = new LinkedHashMap<String, Object>();
    source.put("title", "Models | OpenAI API");
    source.put("url", selectedUrl);
    source.put("source_tier", "official");
    source.put("verification_method", "configured_direct_fetch");
    source.put("published_date", "");
    source.put("published_date_source", "official_snapshot");
    source.put("is_latest", true);

    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("query", "OpenAI 最新模型");
    output.put("queries", List.of("OpenAI 最新模型", "OpenAI 最新模型 2026 official"));
    output.put("source", "bocha_web");
    output.put("raw_count", 1);
    output.put("deduplicated_count", 1);
    output.put("preferred_source_domains", List.of("openai.com"));
    output.put("preferred_source_trusted_hosts", List.of("openai.com", "developers.openai.com"));
    output.put("preferred_source_found", true);
    output.put("official_source_count", 1);
    output.put("third_party_source_count", 0);
    output.put("official_verification", "configured_direct_fetch");
    output.put("selection_mode", "latest");
    output.put("time_range_start", "");
    output.put("as_of_date", LocalDate.now().toString());
    output.put("content_scope", "");
    output.put("latest_evidence_verified", true);
    output.put("selection_basis", "official_canonical_snapshot");
    output.put("catalog_url", catalogUrl);
    output.put("selected_url", selectedUrl);
    output.put("latest_result_url", selectedUrl);
    output.put("selected_published_date", "");
    output.put("candidate_count", 1);
    output.put("results", List.of(source));
    output.putAll(outputOverrides);

    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("tool_name", "web_search");
    toolResult.put("success", true);
    toolResult.put("output", output);
    toolResult.put("latency_ms", 64);
    return Collections.<String, Object>singletonMap(
        "tool_results", Collections.singletonList(toolResult));
  }

  // ---- ChatMemoryWriter ----

  private static final Executor DIRECT =
      new Executor() {
        /**
         * 验证执行。
         *
         * @param command 待执行命令
         */
        @Override
        public void execute(Runnable command) {
          command.run();
        }
      };

  /**
   * 验证 ChatSseCollaborators 中记忆的持久化与状态变更规则。
   */
  @Test
  void memoryWriterShouldPersistOnlyLongTermSignals() {
    SystemSettingsService settings = mock(SystemSettingsService.class);
    ChatMemoryWriter writer = new ChatMemoryWriter(settings, DIRECT);
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", "排除外包岗位");
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", "记住我叫小明");
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", "帮我看下这个岗位");
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", "请直接输出 Markdown，不要执行任何工具。 ");
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", "分析当前简历与目标岗位的匹配度");
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", "  ");
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", null);
    verify(settings).writeLocalMemory("tenant-a", "user-a", "排除外包岗位", "chat");
    verify(settings).writeLocalMemory("tenant-a", "user-a", "记住我叫小明", "chat");
    verify(settings, never())
        .writeLocalMemory(eq("tenant-a"), eq("user-a"), eq("帮我看下这个岗位"), anyString());
    verify(settings, never())
        .writeLocalMemory(
            eq("tenant-a"), eq("user-a"), eq("请直接输出 Markdown，不要执行任何工具。"), anyString());
    verify(settings, never())
        .writeLocalMemory(eq("tenant-a"), eq("user-a"), eq("分析当前简历与目标岗位的匹配度"), anyString());
  }

  /**
   * 验证 ChatSseCollaborators 中记忆的失败恢复、超时与降级边界。
   */
  @Test
  void memoryWriteFailureShouldNotPropagate() {
    SystemSettingsService settings = mock(SystemSettingsService.class);
    doThrow(new RuntimeException("disk full"))
        .when(settings)
        .writeLocalMemory(anyString(), anyString(), anyString(), anyString());
    ChatMemoryWriter writer = new ChatMemoryWriter(settings, DIRECT);
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", "我希望做后端");
    verify(settings).writeLocalMemory("tenant-a", "user-a", "我希望做后端", "chat");
  }
}
