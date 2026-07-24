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

/** SSE 主链路协作类单元测试：覆盖事件下发的取消检查与"先推前端、再异步落库"顺序、 持久化协调器的顺序落库与降级回退、Runtime 托管请求组装以及记忆分层写入。 */
class ChatSseCollaboratorsTest {

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

  @Test
  void clientDisconnectClassifierShouldRecognizeContainerAndSocketErrors() {
    assertTrue(
        ChatSseServiceImpl.isClientDisconnect(
            new AsyncRequestNotUsableException("ServletOutputStream failed to flush")));
    assertTrue(
        ChatSseServiceImpl.isClientDisconnect(
            new IOException("write failed", new IOException("Broken pipe"))));
  }

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

  private ChatPersistenceCoordinator newCoordinator(ChatSessionStore store) {
    return new ChatPersistenceCoordinator(
        store,
        runnable -> {
          Thread thread = new Thread(runnable, "test-persist");
          thread.setDaemon(true);
          return thread;
        });
  }

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

  @Test
  void buildUnderstandingContextShouldKeepReferencesWithoutFullResumeOrJd() {
    PersonalContextBuilder builder = mock(PersonalContextBuilder.class);
    Map<String, Object> resume = new LinkedHashMap<String, Object>();
    resume.put("name", "6年经验简历");
    resume.put("skills", Arrays.asList("Java", "Python", "Agent"));
    resume.put(
        "experiences",
        Arrays.asList(
            Collections.<String, Object>singletonMap(
                "description", "FULL_RESUME_BODY_SHOULD_NOT_ENTER_INTENT_PROMPT")));
    Map<String, Object> job = new LinkedHashMap<String, Object>();
    job.put("securityId", "job-1");
    job.put("jobName", "大模型应用开发岗");
    job.put("brandName", "上海示例科技");
    job.put("jobDescription", "FULL_JOB_DESCRIPTION_SHOULD_NOT_ENTER_INTENT_PROMPT_这是完整岗位正文");
    PersonalContext context =
        new PersonalContext(
            "general",
            Collections.<String, Object>emptyMap(),
            resume,
            Arrays.asList(job),
            Collections.<Map<String, Object>>emptyList(),
            Collections.<Map<String, Object>>emptyList(),
            Collections.<Map<String, Object>>emptyList(),
            Collections.<Map<String, Object>>emptyList(),
            "任务：general。已读取当前简历摘要。当前会话岗位 1 个。");
    when(builder.build(anyString(), anyString(), anyString(), any(), any())).thenReturn(context);
    RuntimeManagedRequestFactory factory =
        new RuntimeManagedRequestFactory(
            mock(AgentIntegrationService.class), builder, new JobBuddyProperties());
    ChatSessionState state = new ChatSessionState();
    state.tenantId = "tenant-a";
    state.userId = "user-a";

    Map<String, Object> compact = factory.buildUnderstandingContext("现在这个6年的简历呢", null, state);

    String serialized = String.valueOf(compact);
    assertFalse(serialized.contains("FULL_RESUME_BODY"));
    assertFalse(serialized.contains("FULL_JOB_DESCRIPTION"));
    Map<?, ?> resumeRef = (Map<?, ?>) compact.get("resume_ref");
    assertEquals("6年经验简历", resumeRef.get("name"));
    assertEquals(3, resumeRef.get("skills_count"));
    List<?> jobRefs = (List<?>) compact.get("current_job_refs");
    assertEquals("job-1", ((Map<?, ?>) jobRefs.get(0)).get("securityId"));
    assertEquals(Boolean.TRUE, ((Map<?, ?>) jobRefs.get(0)).get("has_job_description"));
  }

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

  // ---- ChatMemoryWriter ----

  private static final Executor DIRECT =
      new Executor() {
        @Override
        public void execute(Runnable command) {
          command.run();
        }
      };

  @Test
  void memoryWriterShouldPersistOnlyLongTermSignals() {
    SystemSettingsService settings = mock(SystemSettingsService.class);
    ChatMemoryWriter writer = new ChatMemoryWriter(settings, DIRECT);
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", "排除外包岗位");
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", "帮我看下这个岗位");
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", "  ");
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", null);
    verify(settings).writeLocalMemory("tenant-a", "user-a", "constraint", "排除外包岗位", "chat");
    verify(settings, never())
        .writeLocalMemory(eq("tenant-a"), eq("user-a"), eq("preference"), anyString(), anyString());
  }

  @Test
  void memoryWriteFailureShouldNotPropagate() {
    SystemSettingsService settings = mock(SystemSettingsService.class);
    doThrow(new RuntimeException("disk full"))
        .when(settings)
        .writeLocalMemory(anyString(), anyString(), anyString(), anyString(), anyString());
    ChatMemoryWriter writer = new ChatMemoryWriter(settings, DIRECT);
    writer.captureLongTermMemoryAsync("tenant-a", "user-a", "我希望做后端");
    verify(settings).writeLocalMemory("tenant-a", "user-a", "preference", "我希望做后端", "chat");
  }
}
