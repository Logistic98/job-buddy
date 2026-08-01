package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.dto.request.ChatStreamRequest;
import com.jobbuddy.backend.modules.chat.dto.response.ChatMessageResponse;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunResult;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import com.jobbuddy.backend.modules.chat.service.IntentService;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.chat.service.impl.ChatSseServiceImpl;
import com.jobbuddy.backend.modules.chat.service.impl.ChatStreamAdmissionController;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.prompt.model.PersonalContext;
import com.jobbuddy.backend.modules.prompt.service.PersonalContextBuilder;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 验证 ChatSseLifecycle 的核心行为、异常路径与边界条件。
 */
class ChatSseLifecycleTest {

  /**
   * 换批质量门失败时，真实 SSE 入口必须同时恢复上一批岗位和上一批游标。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void flipQualityFailureShouldRestorePreviousSlotsThroughSseEntry() throws Exception {
    String sessionId = "s-flip-recovery";
    ChatSessionState state = new ChatSessionState();
    state.sessionId = sessionId;
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    state.jobs =
        new java.util.ArrayList<Map<String, Object>>(
            Collections.<Map<String, Object>>singletonList(
                Collections.<String, Object>singletonMap("securityId", "previous-job")));
    Map<String, Object> previousSlots = new LinkedHashMap<String, Object>();
    previousSlots.put("role", "大模型应用开发");
    previousSlots.put("boss_page", Integer.valueOf(1));
    previousSlots.put("candidate_offset", Integer.valueOf(5));
    state.lastSlots = new LinkedHashMap<String, Object>(previousSlots);

    ChatSessionStore sessionStore = mock(ChatSessionStore.class);
    when(sessionStore.getOrCreate(sessionId)).thenReturn(state);
    JobRuntimeService jobRuntimeService = mock(JobRuntimeService.class);
    when(jobRuntimeService.bossCandidatePoolTimeoutSeconds()).thenReturn(30);
    List<Map<String, Object>> candidates =
        Collections.<Map<String, Object>>singletonList(
            Collections.<String, Object>singletonMap("securityId", "new-job"));
    when(jobRuntimeService.recommendJobsFast(any(IntentResult.class), eq(sessionId), isNull()))
        .thenReturn(candidates);
    when(jobRuntimeService.prequalifyRecommendationsWithContinuation(
            isNull(), any(IntentResult.class), eq(candidates), eq(sessionId)))
        .thenThrow(new RuntimeException("岗位匹配结果不完整"));
    PersonalContextBuilder personalContextBuilder = mock(PersonalContextBuilder.class);
    when(personalContextBuilder.build(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(emptyPersonalContext());
    AgentServiceProperties agentProperties = new AgentServiceProperties();
    ChatSseServiceImpl service =
        new ChatSseServiceImpl(
            jobRuntimeService,
            sessionStore,
            mock(AgentIntegrationService.class),
            mock(IntentService.class),
            mock(com.jobbuddy.backend.modules.chat.service.ChatAttachmentService.class),
            mock(ResumeStorageService.class),
            mock(BossCliService.class),
            personalContextBuilder,
            mock(SystemSettingsService.class),
            new JobBuddyProperties(),
            agentProperties,
            new ChatStreamAdmissionController(agentProperties));
    ChatStreamRequest request = new ChatStreamRequest();
    request.setSessionId(sessionId);
    request.setMessage("换一批");
    request.setFlipJobs(true);
    request.setAuthenticatedTenantId("tenant-a");
    request.setAuthenticatedUserId("user-a");

    SseEmitter emitter = service.stream(request);

    assertTrue(waitUntilRemoved(cancelledMap(service), emitter, 3000));
    assertEquals("previous-job", state.jobs.get(0).get("securityId"));
    assertEquals(previousSlots, state.lastSlots);
    service.shutdownExecutors();
  }

  /**
   * 验证新会话的 SSE 主链路只向 Runtime 发送当前消息，不查询不存在的聊天历史。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void newSessionTaskUnderstandingShouldSkipHistoryRead() throws Exception {
    ChatSessionStore sessionStore = mock(ChatSessionStore.class);
    IntentService intentService = mock(IntentService.class);
    AgentIntegrationService integrationService = mock(AgentIntegrationService.class);
    ChatSseServiceImpl service = newService(sessionStore, intentService, integrationService);
    when(sessionStore.getOrCreate(anyString()))
        .thenAnswer(
            invocation -> {
              ChatSessionState state = new ChatSessionState();
              state.tenantId = "tenant-a";
              state.userId = "user-a";
              state.sessionId = invocation.getArgument(0);
              state.newlyCreated = true;
              return state;
            });
    when(sessionStore.appendUserMessageOnce(anyString(), anyString(), anyString()))
        .thenReturn(true);
    IntentResult preIntent =
        new IntentResult(
            "job",
            "resume.match",
            0.88,
            Collections.<String>emptyList(),
            "low",
            false,
            "run_resume_match",
            Collections.<String, Object>emptyMap());
    preIntent.setRouter("rule");
    when(intentService.classify(anyString())).thenReturn(preIntent);
    JsonNodeFactory nodes = JsonNodeFactory.instance;
    com.fasterxml.jackson.databind.node.ObjectNode result = nodes.objectNode();
    result.put("status", "success");
    com.fasterxml.jackson.databind.node.ObjectNode directive = result.putObject("directive");
    directive.put("domain", "job");
    directive.put("intent", "resume.match");
    directive.put("confidence", 0.95);
    directive.put("risk", "low");
    directive.put("needs_clarification", false);
    directive.put("next_action", "run_resume_match");
    directive.put("router", "validated_intent_hint");
    directive.set("slots", nodes.objectNode());
    when(integrationService.runRuntime(any(RuntimeRunRequest.class)))
        .thenReturn(RuntimeRunResult.fromJson(result));
    ChatStreamRequest request = new ChatStreamRequest();
    request.setTurnId("turn-new-session");
    request.setMessage("分析当前简历与目标岗位的匹配度");
    request.setAuthenticatedTenantId("tenant-a");
    request.setAuthenticatedUserId("user-a");

    SseEmitter emitter = service.stream(request);

    ArgumentCaptor<RuntimeRunRequest> runtimeRequest =
        ArgumentCaptor.forClass(RuntimeRunRequest.class);
    verify(integrationService, timeout(3000)).runRuntime(runtimeRequest.capture());
    assertEquals(1, runtimeRequest.getValue().messages().size());
    assertEquals("user", runtimeRequest.getValue().messages().get(0).role());
    assertEquals("分析当前简历与目标岗位的匹配度", runtimeRequest.getValue().messages().get(0).content().asText());
    verify(sessionStore, org.mockito.Mockito.never())
        .listMessages(anyString(), anyString(), anyString());
    assertTrue(waitUntilRemoved(cancelledMap(service), emitter, 3000));
    service.shutdownExecutors();
  }

  /**
   * 验证 checkpoint 续跑跳过用户消息写入和任务理解，并把来源 run 交给 Runtime。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void checkpointResumeShouldReuseTurnWithoutRepeatingUnderstanding() throws Exception {
    ChatSessionStore sessionStore = mock(ChatSessionStore.class);
    IntentService intentService = mock(IntentService.class);
    AgentIntegrationService integrationService = mock(AgentIntegrationService.class);
    ChatSseServiceImpl service = newService(sessionStore, intentService, integrationService);
    ChatSessionState state = new ChatSessionState();
    state.sessionId = "session-resume";
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    when(sessionStore.getOrCreate("session-resume")).thenReturn(state);
    ChatMessageResponse sourceMessage = new ChatMessageResponse();
    sourceMessage.setTurnId("turn-original");
    sourceMessage.setRole("user");
    sourceMessage.setContent("原始任务");
    when(sessionStore.listMessages("tenant-a", "user-a", "session-resume"))
        .thenReturn(List.of(sourceMessage));
    com.fasterxml.jackson.databind.node.ObjectNode runtimeResult =
        JsonNodeFactory.instance.objectNode();
    runtimeResult.put("run_id", "run-resumed");
    runtimeResult.put("status", "fail");
    runtimeResult.put("error", "still failing");
    runtimeResult.put("resumable", true);
    when(integrationService.runRuntimeStream(any(RuntimeRunRequest.class), any(), any()))
        .thenReturn(RuntimeRunResult.fromJson(runtimeResult));
    ChatStreamRequest request = new ChatStreamRequest();
    request.setSessionId("session-resume");
    request.setTurnId("turn-original");
    request.setMessage("客户端篡改内容");
    request.setResumeRunId("run-source");
    request.setAuthenticatedTenantId("tenant-a");
    request.setAuthenticatedUserId("user-a");

    SseEmitter emitter = service.stream(request);

    ArgumentCaptor<RuntimeRunRequest> runtimeRequest =
        ArgumentCaptor.forClass(RuntimeRunRequest.class);
    verify(integrationService, timeout(3000))
        .runRuntimeStream(runtimeRequest.capture(), any(), any());
    assertEquals("run-source", runtimeRequest.getValue().resumeFromRunId());
    assertEquals("原始任务", runtimeRequest.getValue().messages().get(0).content().asText());
    assertEquals("turn-original", runtimeRequest.getValue().metadata().path("turn_id").asText());
    verify(sessionStore, org.mockito.Mockito.never())
        .appendUserMessageOnce(anyString(), anyString(), anyString());
    verify(intentService, org.mockito.Mockito.never()).classify(anyString());
    verify(integrationService, org.mockito.Mockito.never())
        .runRuntime(any(RuntimeRunRequest.class));
    assertTrue(waitUntilRemoved(cancelledMap(service), emitter, 3000));
    service.shutdownExecutors();
  }

  /**
   * 换一批是新的聊天轮次：后端仍复用上一轮槽位短路执行，但必须按 turnId 幂等写入用户消息。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void flipJobsShouldPersistUserTurnAndDeduplicateByTurnId() throws Exception {
    String sessionId = "s-flip-turn";
    ChatSessionState state = new ChatSessionState();
    state.sessionId = sessionId;
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    state.jobs =
        new java.util.ArrayList<Map<String, Object>>(
            Collections.<Map<String, Object>>singletonList(
                Collections.<String, Object>singletonMap("securityId", "previous-job")));
    Map<String, Object> slots = new LinkedHashMap<String, Object>();
    slots.put("role", "大模型应用开发");
    slots.put("boss_page", Integer.valueOf(1));
    state.lastSlots = slots;
    ChatSessionStore sessionStore = mock(ChatSessionStore.class);
    when(sessionStore.getOrCreate(sessionId)).thenReturn(state);
    when(sessionStore.appendUserMessageOnce(sessionId, "turn-flip", "换一批")).thenReturn(true, false);
    JobRuntimeService jobRuntimeService = mock(JobRuntimeService.class);
    when(jobRuntimeService.bossCandidatePoolTimeoutSeconds()).thenReturn(30);
    List<Map<String, Object>> candidates =
        Collections.<Map<String, Object>>singletonList(
            Collections.<String, Object>singletonMap("securityId", "new-job"));
    when(jobRuntimeService.recommendJobsFast(any(IntentResult.class), eq(sessionId), isNull()))
        .thenReturn(candidates);
    when(jobRuntimeService.prequalifyRecommendationsWithContinuation(
            isNull(), any(IntentResult.class), eq(candidates), eq(sessionId)))
        .thenReturn(
            new com.jobbuddy.backend.modules.chat.service.JobRecommendationResult(
                candidates,
                1,
                Collections.<String, Integer>emptyMap(),
                Collections.<String>emptyList()));
    PersonalContextBuilder personalContextBuilder = mock(PersonalContextBuilder.class);
    when(personalContextBuilder.build(anyString(), anyString(), anyString(), any(), any()))
        .thenReturn(emptyPersonalContext());
    AgentServiceProperties agentProperties = new AgentServiceProperties();
    ChatSseServiceImpl service =
        new ChatSseServiceImpl(
            jobRuntimeService,
            sessionStore,
            mock(AgentIntegrationService.class),
            mock(IntentService.class),
            mock(com.jobbuddy.backend.modules.chat.service.ChatAttachmentService.class),
            mock(ResumeStorageService.class),
            mock(BossCliService.class),
            personalContextBuilder,
            mock(SystemSettingsService.class),
            new JobBuddyProperties(),
            agentProperties,
            new ChatStreamAdmissionController(agentProperties));
    ChatStreamRequest first = new ChatStreamRequest();
    first.setSessionId(sessionId);
    first.setTurnId("turn-flip");
    first.setMessage("换一批");
    first.setFlipJobs(true);
    first.setAuthenticatedTenantId("tenant-a");
    first.setAuthenticatedUserId("user-a");
    SseEmitter firstEmitter = service.stream(first);
    assertTrue(waitUntilRemoved(cancelledMap(service), firstEmitter, 3000));

    ChatStreamRequest duplicate = new ChatStreamRequest();
    duplicate.setSessionId(sessionId);
    duplicate.setTurnId("turn-flip");
    duplicate.setMessage("换一批");
    duplicate.setFlipJobs(true);
    duplicate.setAuthenticatedTenantId("tenant-a");
    duplicate.setAuthenticatedUserId("user-a");
    SseEmitter duplicateEmitter = service.stream(duplicate);
    assertTrue(waitUntilRemoved(cancelledMap(service), duplicateEmitter, 3000));

    verify(sessionStore, timeout(1000).times(2))
        .appendUserMessageOnce(sessionId, "turn-flip", "换一批");
    verify(jobRuntimeService, times(1))
        .recommendJobsFast(any(IntentResult.class), eq(sessionId), isNull());
    service.shutdownExecutors();
  }

  /**
   * 验证新建服务。
   *
   * @param intentService 意图服务
   * @param integrationService 集成服务
   * @return 服务
   */
  private ChatSseServiceImpl newService(
      IntentService intentService, AgentIntegrationService integrationService) {
    return newService(mock(ChatSessionStore.class), intentService, integrationService);
  }

  /**
   * 验证新建服务。
   *
   * @param sessionStore 会话存储
   * @param intentService 意图服务
   * @param integrationService 集成服务
   * @return 服务
   */
  private ChatSseServiceImpl newService(
      ChatSessionStore sessionStore,
      IntentService intentService,
      AgentIntegrationService integrationService) {
    return newService(
        sessionStore, intentService, integrationService, new AgentServiceProperties());
  }

  /**
   * 验证新建服务。
   *
   * @param sessionStore 会话存储
   * @param intentService 意图服务
   * @param integrationService 集成服务
   * @param agentServiceProperties Agent 服务配置属性
   * @return 服务
   */
  private ChatSseServiceImpl newService(
      ChatSessionStore sessionStore,
      IntentService intentService,
      AgentIntegrationService integrationService,
      AgentServiceProperties agentServiceProperties) {
    when(sessionStore.getOrCreate(anyString()))
        .thenAnswer(
            inv -> {
              ChatSessionState state = new ChatSessionState();
              state.sessionId = inv.getArgument(0);
              return state;
            });
    return new ChatSseServiceImpl(
        mock(JobRuntimeService.class),
        sessionStore,
        integrationService,
        intentService,
        mock(com.jobbuddy.backend.modules.chat.service.ChatAttachmentService.class),
        mock(ResumeStorageService.class),
        mock(BossCliService.class),
        mock(PersonalContextBuilder.class),
        mock(SystemSettingsService.class),
        new JobBuddyProperties(),
        agentServiceProperties,
        new ChatStreamAdmissionController(agentServiceProperties));
  }

  /**
   * 构造不包含业务数据的个人上下文。
   *
   * @return 空个人上下文
   */
  private PersonalContext emptyPersonalContext() {
    return new PersonalContext(
        "job",
        Collections.<String, Object>emptyMap(),
        Collections.<String, Object>emptyMap(),
        Collections.<Map<String, Object>>emptyList(),
        Collections.<Map<String, Object>>emptyList(),
        Collections.<Map<String, Object>>emptyList(),
        Collections.<Map<String, Object>>emptyList(),
        Collections.<Map<String, Object>>emptyList(),
        "");
  }

  /**
   * 构造已取消状态映射。
   *
   * @param service 服务
   * @return 已取消状态映射
   * @throws Exception 处理失败时抛出
   */
  @SuppressWarnings("unchecked")
  private ConcurrentMap<SseEmitter, AtomicBoolean> cancelledMap(ChatSseServiceImpl service)
      throws Exception {
    Field field = ChatSseServiceImpl.class.getDeclaredField("emitterCancelled");
    field.setAccessible(true);
    return (ConcurrentMap<SseEmitter, AtomicBoolean>) field.get(service);
  }

  /**
   * 等待 SSE 发送器从注册表移除。
   *
   * @param map 数据映射
   * @param emitter SSE 事件发送器
   * @param timeoutMillis 超时毫秒数
   * @return 会话是否已从活动任务中移除
   * @throws InterruptedException 等待过程被中断时抛出
   */
  private boolean waitUntilRemoved(
      ConcurrentMap<SseEmitter, AtomicBoolean> map, SseEmitter emitter, long timeoutMillis)
      throws InterruptedException {
    long deadline = System.currentTimeMillis() + timeoutMillis;
    while (System.currentTimeMillis() < deadline) {
      if (!map.containsKey(emitter)) return true;
      Thread.sleep(20);
    }
    return !map.containsKey(emitter);
  }

  /**
   * 验证 ChatSseLifecycle 中流式响应的失败恢复、超时与降级边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void streamUsesConfiguredSessionLifecycleTimeout() throws Exception {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setStreamReadTimeout(Duration.ofSeconds(180));
    ChatSessionStore sessionStore = mock(ChatSessionStore.class);
    ChatSseServiceImpl service =
        newService(
            sessionStore,
            mock(IntentService.class),
            mock(AgentIntegrationService.class),
            properties);

    ChatStreamRequest request = new ChatStreamRequest();
    request.setSessionId("s-session-timeout");
    request.setTurnId("turn-session-timeout");
    request.setMessage("帮我找岗位");
    request.setAuthenticatedTenantId("tenant-a");
    request.setAuthenticatedUserId("user-a");
    SseEmitter emitter = service.stream(request);

    assertEquals(Duration.ofMinutes(15), properties.getStreamSessionTimeout());
    assertEquals(Long.valueOf(Duration.ofMinutes(15).toMillis()), emitter.getTimeout());
    assertTrue(waitUntilRemoved(cancelledMap(service), emitter, 1000));
    service.shutdownExecutors();
  }

  /**
   * 验证 ChatSseLifecycle 中运行时的失败恢复、超时与降级边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void streamKeepsRuntimeReadTimeoutMarginWhenItExceedsSessionTimeout() throws Exception {
    AgentServiceProperties properties = new AgentServiceProperties();
    properties.setStreamReadTimeout(Duration.ofSeconds(180));
    properties.setStreamSessionTimeout(Duration.ofMinutes(2));
    ChatSessionStore sessionStore = mock(ChatSessionStore.class);
    ChatSseServiceImpl service =
        newService(
            sessionStore,
            mock(IntentService.class),
            mock(AgentIntegrationService.class),
            properties);

    ChatStreamRequest request = new ChatStreamRequest();
    request.setSessionId("s-runtime-timeout-margin");
    request.setTurnId("turn-runtime-timeout-margin");
    request.setMessage("帮我找岗位");
    request.setAuthenticatedTenantId("tenant-a");
    request.setAuthenticatedUserId("user-a");
    SseEmitter emitter = service.stream(request);

    assertEquals(Long.valueOf(Duration.ofSeconds(190).toMillis()), emitter.getTimeout());
    assertTrue(waitUntilRemoved(cancelledMap(service), emitter, 1000));
    service.shutdownExecutors();
  }

  /**
   * 验证 ChatSseLifecycle 中用户的流式生命周期与中断边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void userMessageIsQueuedBeforeTaskUnderstandingCanBeInterrupted() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    IntentService intentService = mock(IntentService.class);
    when(intentService.classify(anyString()))
        .thenAnswer(
            inv -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return new IntentResult(
                  "job",
                  "job.recommend",
                  1.0,
                  Collections.<String>emptyList(),
                  "low",
                  false,
                  "call_get_recommend_jobs",
                  Collections.<String, Object>emptyMap());
            });
    ChatSessionStore sessionStore = mock(ChatSessionStore.class);
    ChatSseServiceImpl service =
        newService(sessionStore, intentService, mock(AgentIntegrationService.class));

    ChatStreamRequest request = new ChatStreamRequest();
    request.setSessionId("s-early-persist");
    request.setMessage("切换会话也要保留");
    request.setAuthenticatedTenantId("tenant-a");
    request.setAuthenticatedUserId("user-a");
    service.stream(request);

    assertTrue(entered.await(3, TimeUnit.SECONDS), "后台任务应已进入可中断的意图分类阶段");
    verify(sessionStore, timeout(1000)).appendMessage("s-early-persist", "user", "切换会话也要保留");
    release.countDown();
    service.shutdownExecutors();
  }

  /**
   * 验证 ChatSseLifecycle 的去重与幂等边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void duplicateTurnIdMustPersistAndExecuteOnlyOnce() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    IntentService intentService = mock(IntentService.class);
    when(intentService.classify(anyString()))
        .thenAnswer(
            inv -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return new IntentResult(
                  "job",
                  "job.recommend",
                  1.0,
                  Collections.<String>emptyList(),
                  "low",
                  false,
                  "call_get_recommend_jobs",
                  Collections.<String, Object>emptyMap());
            });
    ChatSessionStore sessionStore = mock(ChatSessionStore.class);
    when(sessionStore.appendUserMessageOnce("s-idempotent", "turn-same", "筛选杭州 Go 后端岗位"))
        .thenReturn(true, false);
    ChatSseServiceImpl service =
        newService(sessionStore, intentService, mock(AgentIntegrationService.class));

    ChatStreamRequest first = new ChatStreamRequest();
    first.setSessionId("s-idempotent");
    first.setTurnId("turn-same");
    first.setMessage("筛选杭州 Go 后端岗位");
    first.setAuthenticatedTenantId("tenant-a");
    first.setAuthenticatedUserId("user-a");
    service.stream(first);
    assertTrue(entered.await(3, TimeUnit.SECONDS));

    ChatStreamRequest duplicate = new ChatStreamRequest();
    duplicate.setSessionId("s-idempotent");
    duplicate.setTurnId("turn-same");
    duplicate.setMessage("筛选杭州 Go 后端岗位");
    duplicate.setAuthenticatedTenantId("tenant-a");
    duplicate.setAuthenticatedUserId("user-a");
    service.stream(duplicate);

    verify(sessionStore, timeout(1000).times(2))
        .appendUserMessageOnce("s-idempotent", "turn-same", "筛选杭州 Go 后端岗位");
    verify(intentService, times(1)).classify(anyString());
    release.countDown();
    service.shutdownExecutors();
  }

  /**
   * 验证 ChatSseLifecycle 中流式响应的流式生命周期与中断边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void streamStopsPromptlyAfterConnectionClosed() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    IntentService intentService = mock(IntentService.class);
    when(intentService.classify(anyString()))
        .thenAnswer(
            inv -> {
              entered.countDown();
              release.await(5, TimeUnit.SECONDS);
              return new IntentResult(
                  "job",
                  "job.recommend",
                  1.0,
                  Collections.<String>emptyList(),
                  "low",
                  false,
                  "call_get_recommend_jobs",
                  Collections.<String, Object>emptyMap());
            });
    AgentIntegrationService integrationService = mock(AgentIntegrationService.class);
    ChatSseServiceImpl service = newService(intentService, integrationService);

    ChatStreamRequest request = new ChatStreamRequest();
    request.setMessage("帮我找岗位");
    request.setAuthenticatedTenantId("tenant-a");
    request.setAuthenticatedUserId("user-a");
    SseEmitter emitter = service.stream(request);
    ConcurrentMap<SseEmitter, AtomicBoolean> map = cancelledMap(service);
    assertTrue(entered.await(3, TimeUnit.SECONDS), "后台任务应已进入意图分类阶段");
    AtomicBoolean cancelled = map.get(emitter);
    assertNotNull(cancelled, "在途流应存在取消标记");

    // 模拟容器 onError/onTimeout：连接已关闭。
    cancelled.set(true);
    release.countDown();

    // 任务应在下一次事件下发时感知取消并快速终止（远小于 180s 的流超时），并清理标记防止泄漏。
    assertTrue(waitUntilRemoved(map, emitter, 3000), "连接关闭后后台任务应及时终止并清理取消标记");
  }

  /**
   * 验证 ChatSseLifecycle 中流式响应的失败恢复、超时与降级边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void streamCleansUpAfterErrorPath() throws Exception {
    IntentService intentService = mock(IntentService.class);
    when(intentService.classify(anyString()))
        .thenReturn(
            new IntentResult(
                "job",
                "job.recommend",
                1.0,
                Collections.<String>emptyList(),
                "low",
                false,
                "call_get_recommend_jobs",
                Collections.<String, Object>emptyMap()));
    AgentIntegrationService integrationService = mock(AgentIntegrationService.class);
    // Runtime 返回空结果触发任务理解失败的异常路径。
    when(integrationService.runRuntime(any(RuntimeRunRequest.class)))
        .thenReturn(RuntimeRunResult.empty());
    ChatSseServiceImpl service = newService(intentService, integrationService);

    ChatStreamRequest request = new ChatStreamRequest();
    request.setMessage("帮我找岗位");
    request.setAuthenticatedTenantId("tenant-a");
    request.setAuthenticatedUserId("user-a");
    SseEmitter emitter = service.stream(request);
    ConcurrentMap<SseEmitter, AtomicBoolean> map = cancelledMap(service);

    assertTrue(waitUntilRemoved(map, emitter, 3000), "异常路径结束后应清理取消标记");
    assertFalse(map.containsKey(emitter));
  }
}
