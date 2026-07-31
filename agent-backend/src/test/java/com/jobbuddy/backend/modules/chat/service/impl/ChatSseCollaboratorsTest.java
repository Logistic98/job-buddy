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
import static org.mockito.Mockito.doNothing;
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
    request.setMessage("现在这个6年的简历呢");

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
    doNothing()
        .doNothing()
        .doThrow(new IOException("connection closed"))
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
   * 验证 ChatSseCollaborators 中岗位的检索、筛选与排序规则。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void jobRecommendationShouldReportAllSearchCandidatesBeforeQualityGateFiltering()
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
    List<Map<String, Object>> candidates = new java.util.ArrayList<Map<String, Object>>();
    for (int index = 1; index <= 23; index++) {
      candidates.add(Collections.<String, Object>singletonMap("jobName", "候选岗位 " + index));
    }
    List<Map<String, Object>> qualified = candidates.subList(0, 8);
    Map<String, Integer> rejectionReasons = new LinkedHashMap<String, Integer>();
    rejectionReasons.put("匹配置信度低", Integer.valueOf(10));
    rejectionReasons.put("未达到最低匹配分", Integer.valueOf(5));
    JobRecommendationResult quality =
        new JobRecommendationResult(
            qualified, 23, rejectionReasons, Collections.<String>emptyList());
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
    verify(sender, org.mockito.Mockito.times(4))
        .sendToolStatus(any(SseEmitter.class), eq("s1"), eq(state), statusCaptor.capture());
    Map<String, Object> searchCompleted =
        statusCaptor.getAllValues().stream()
            .filter(
                event ->
                    "job_search".equals(event.get("id")) && "success".equals(event.get("status")))
            .findFirst()
            .orElseThrow();
    Map<String, Object> searchDetail = (Map<String, Object>) searchCompleted.get("detail");
    assertEquals("累计检索到 23 个候选岗位。", searchCompleted.get("summary"));
    assertEquals(23, searchDetail.get("count"));
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
    assertEquals(23, qualityDetail.get("candidateCount"));
    assertEquals(8, qualityDetail.get("qualifiedCount"));
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
    parsedResume.put("targetRole", "Agent 应用研发");
    parsedResume.put("currentTitle", "AI 应用研发工程师");
    parsedResume.put("skills", Arrays.asList("Java", "Python", "Agent"));
    parsedResume.put(
        "projects",
        Arrays.asList(
            Collections.<String, Object>singletonMap(
                "description", "FULL_RESUME_PROJECT_SHOULD_NOT_ENTER_INTENT_PROMPT")));
    resume.setParsed(parsedResume);
    when(resumeStorageService.get("resume-6-years", "tenant-a", "user-a")).thenReturn(resume);
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("securityId", "job-1");
    job.put("jobName", "大模型应用开发岗");
    job.put("brandName", "上海示例科技");
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
    state.resumeId = "resume-6-years";
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
    assertEquals("Agent 应用研发", resumeRef.get("targetRole"));
    assertEquals("AI 应用研发工程师", resumeRef.get("current_title"));
    assertEquals(3, resumeRef.get("skills_count"));
    assertEquals(1, resumeRef.get("projects_count"));
    List<?> jobRefs = (List<?>) compact.get("current_job_refs");
    assertEquals("job-1", ((Map<?, ?>) jobRefs.get(0)).get("securityId"));
    assertEquals(Boolean.TRUE, ((Map<?, ?>) jobRefs.get(0)).get("has_job_description"));
    verifyNoInteractions(builder);
    verify(resumeStorageService).get("resume-6-years", "tenant-a", "user-a");
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
    state.resumeId = "resume-6-years";
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
    assertFalse(detail.containsKey("stdout"));
    assertFalse(detail.containsKey("stderr"));
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
    Map<String, Object> runtimeResult = new LinkedHashMap<String, Object>();
    runtimeResult.put("status", "fail");
    runtimeResult.put("tool_results", Collections.singletonList(toolResult));

    Map<String, Object> status = RuntimeManagedTaskHandler.sandboxExecutionStatus(runtimeResult);

    assertEquals("error", status.get("status"));
    Map<?, ?> detail = (Map<?, ?>) status.get("detail");
    assertEquals(Boolean.TRUE, detail.get("sandboxed"));
    assertEquals(7, detail.get("exitCode"));
    assertEquals("sandbox_execution_failed", detail.get("errorCategory"));
    assertFalse(detail.containsKey("stdout"));
    assertFalse(detail.containsKey("stderr"));
    assertFalse(detail.containsKey("error"));
    assertTrue(RuntimeManagedTaskHandler.explicitRuntimeFailure(runtimeResult));
  }

  /**
   * 验证 Runtime 的联网搜索结果会转换为独立过程事件，并只投影有界来源摘要。
   */
  @Test
  void webSearchShouldExposeAuditableToolStatus() {
    Map<String, Object> source = new LinkedHashMap<String, Object>();
    source.put("title", "OpenAI Models");
    source.put("url", "https://openai.com/models");
    source.put("snippet", "不应进入过程事件的长摘要");
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("query", "OpenAI latest models");
    output.put(
        "queries", List.of("OpenAI latest models", "site:openai.com OpenAI latest models 2026"));
    output.put("source", "bocha_web");
    output.put("raw_count", 3);
    output.put("deduplicated_count", 1);
    output.put("preferred_source_domains", List.of("openai.com"));
    output.put("preferred_source_found", true);
    output.put("results", Collections.singletonList(source));
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("tool_name", "web_search");
    toolResult.put("success", true);
    toolResult.put("output", output);
    toolResult.put("latency_ms", 128);
    Map<String, Object> runtimeResult = new LinkedHashMap<String, Object>();
    runtimeResult.put("tool_results", Collections.singletonList(toolResult));

    Map<String, Object> status = RuntimeManagedTaskHandler.webSearchExecutionStatus(runtimeResult);

    assertEquals("runtime_web_search", status.get("id"));
    assertEquals("联网搜索", status.get("title"));
    assertEquals("success", status.get("status"));
    Map<?, ?> detail = (Map<?, ?>) status.get("detail");
    assertEquals("OpenAI latest models", detail.get("query"));
    assertEquals(
        List.of("OpenAI latest models", "site:openai.com OpenAI latest models 2026"),
        detail.get("queries"));
    assertEquals("bocha_web", detail.get("provider"));
    assertEquals(3, detail.get("rawCount"));
    assertEquals(1, detail.get("deduplicatedCount"));
    assertEquals(List.of("openai.com"), detail.get("preferredSourceDomains"));
    assertEquals(true, detail.get("preferredSourceFound"));
    assertEquals(1, detail.get("sourceCount"));
    List<?> sources = (List<?>) detail.get("sources");
    assertEquals("OpenAI Models", ((Map<?, ?>) sources.get(0)).get("title"));
    assertEquals("https://openai.com/models", ((Map<?, ?>) sources.get(0)).get("url"));
    assertFalse(String.valueOf(status).contains("不应进入过程事件的长摘要"));
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
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", "  ");
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", null);
    verify(settings).writeLocalMemory("tenant-a", "user-a", "排除外包岗位", "chat");
    verify(settings).writeLocalMemory("tenant-a", "user-a", "记住我叫小明", "chat");
    verify(settings, never())
        .writeLocalMemory(eq("tenant-a"), eq("user-a"), eq("帮我看下这个岗位"), anyString());
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
