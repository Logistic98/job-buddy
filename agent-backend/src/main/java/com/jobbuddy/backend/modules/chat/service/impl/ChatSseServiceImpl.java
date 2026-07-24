package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.SELECTED_JOB_CONTEXT_KEY;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.directiveAction;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.intentFromRuntime;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.intentHint;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.matchesCapability;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.summarizeRuntimeResult;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.toolStatus;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.withSelectedJobContext;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.errorMessage;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.stringValue;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.security.AuthenticationScope;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.dto.request.ChatStreamRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunResult;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import com.jobbuddy.backend.modules.chat.service.ChatSseService;
import com.jobbuddy.backend.modules.chat.service.IntentService;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.chat.util.RuntimeRequestBuilder;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.prompt.service.PersonalContextBuilder;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** SSE 会话主编排：负责连接生命周期、意图分流与各业务链路的调度； 事件下发、持久化、记忆写入与各链路细节由同包协作类承载。 */
@Service
public class ChatSseServiceImpl implements ChatSseService {
  private static final Logger log = LoggerFactory.getLogger(ChatSseServiceImpl.class);
  private static final JsonCodec JSON = new JsonCodec();
  private final JobRuntimeService jobRuntimeService;
  private final ChatSessionStore sessionStore;
  private final AgentIntegrationService integrationService;
  private final IntentService intentService;
  private final JobBuddyProperties properties;
  private final AgentServiceProperties agentServiceProperties;
  // 每条 SSE 流的取消标记：连接超时、出错或完成后置位，send 前检查以便后台任务尽快停止无效工作。
  private final ConcurrentMap<SseEmitter, AtomicBoolean> emitterCancelled =
      new ConcurrentHashMap<SseEmitter, AtomicBoolean>();
  // SSE 长连接只在独立有界线程池执行，队列满时必须在提交阶段拒绝，禁止占用 servlet 请求线程。
  private final ExecutorService executor;
  private final ChatStreamAdmissionController admissionController;
  private final ChatPersistenceCoordinator persistence;
  private final ChatSseEventSender sender;
  private final ChatSseHeartbeatScheduler heartbeatScheduler;
  private final ChatMemoryWriter memoryWriter;
  private final ChatTaskContextBuilder taskContextBuilder;
  private final RuntimeManagedRequestFactory requestFactory;
  private final SelectedJobAnalysisHandler selectedJobAnalysisHandler;
  private final ResumeFlowHandler resumeFlowHandler;
  private final JobRecommendHandler jobRecommendHandler;
  private final RuntimeManagedTaskHandler runtimeManagedTaskHandler;

  private static java.util.concurrent.ThreadFactory namedThreadFactory(final String prefix) {
    final AtomicInteger seq = new AtomicInteger(1);
    return new java.util.concurrent.ThreadFactory() {
      @Override
      public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + "-" + seq.getAndIncrement());
        thread.setDaemon(true);
        return thread;
      }
    };
  }

  public ChatSseServiceImpl(
      JobRuntimeService jobRuntimeService,
      ChatSessionStore sessionStore,
      AgentIntegrationService integrationService,
      IntentService intentService,
      ResumeStorageService resumeStorageService,
      BossCliService bossCliService,
      PersonalContextBuilder personalContextBuilder,
      SystemSettingsService settingsService,
      JobBuddyProperties properties,
      AgentServiceProperties agentServiceProperties,
      ChatStreamAdmissionController admissionController) {
    this.jobRuntimeService = jobRuntimeService;
    this.sessionStore = sessionStore;
    this.integrationService = integrationService;
    this.intentService = intentService;
    this.properties = properties;
    this.agentServiceProperties = agentServiceProperties;
    this.admissionController = admissionController;
    int coreThreads = Math.max(1, agentServiceProperties.getStreamCoreThreads());
    int maxThreads = Math.max(coreThreads, agentServiceProperties.getStreamMaxThreads());
    this.executor =
        new ThreadPoolExecutor(
            coreThreads,
            maxThreads,
            60L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(
                Math.max(1, agentServiceProperties.getStreamQueueCapacity())),
            namedThreadFactory("chat-sse"),
            new ThreadPoolExecutor.AbortPolicy());
    // 会话持久化（Postgres/Redis 读写）从 SSE 主线程剥离，统一交给单线程顺序执行，
    // 既保证用户消息/助手消息/工具事件的落库顺序，又避免每次 tool_status 的 DB 写阻塞首包与答案流式。
    this.persistence =
        new ChatPersistenceCoordinator(sessionStore, namedThreadFactory("chat-persist"));
    this.sender = new ChatSseEventSender(emitterCancelled, persistence);
    this.heartbeatScheduler =
        new ChatSseHeartbeatScheduler(namedThreadFactory("chat-sse-heartbeat"));
    this.memoryWriter = new ChatMemoryWriter(settingsService, executor);
    this.taskContextBuilder = new ChatTaskContextBuilder(sessionStore);
    this.requestFactory =
        new RuntimeManagedRequestFactory(integrationService, personalContextBuilder, properties);
    CurrentResumeLoader resumeLoader = new CurrentResumeLoader(resumeStorageService);
    SelectedJobContextResolver selectedJobContextResolver =
        new SelectedJobContextResolver(bossCliService);
    this.resumeFlowHandler =
        new ResumeFlowHandler(
            sender,
            resumeLoader,
            resumeStorageService,
            jobRuntimeService,
            sessionStore,
            integrationService,
            requestFactory,
            selectedJobContextResolver);
    this.selectedJobAnalysisHandler =
        new SelectedJobAnalysisHandler(sender, selectedJobContextResolver, resumeFlowHandler);
    this.jobRecommendHandler =
        new JobRecommendHandler(
            sender,
            persistence,
            jobRuntimeService,
            personalContextBuilder,
            resumeLoader,
            properties);
    this.runtimeManagedTaskHandler =
        new RuntimeManagedTaskHandler(sender, integrationService, requestFactory);
  }

  @PreDestroy
  public void shutdownExecutors() {
    executor.shutdownNow();
    heartbeatScheduler.shutdown();
    // 持久化队列允许已提交任务执行完毕，避免关停时丢失尚未落库的会话消息。
    persistence.shutdown();
  }

  public SseEmitter stream(final ChatStreamRequest request) {
    final ChatStreamAdmissionController.Lease admissionLease =
        admissionController.acquire(
            request.getAuthenticatedTenantId(), request.getAuthenticatedUserId());
    // SSE 总生命周期与单次 Runtime 流式读取解耦；同时保留 10s 余量，保证单次读取超时错误来得及下发。
    final long emitterTimeoutMillis =
        Math.max(
            agentServiceProperties.getStreamSessionTimeout().toMillis(),
            agentServiceProperties.getStreamReadTimeout().toMillis() + 10000L);
    final SseEmitter emitter = new SseEmitter(emitterTimeoutMillis);
    final AtomicBoolean cancelled = new AtomicBoolean(false);
    final AtomicReference<Future<?>> taskRef = new AtomicReference<Future<?>>();
    final AtomicReference<ScheduledFuture<?>> heartbeatRef =
        new AtomicReference<ScheduledFuture<?>>();
    emitterCancelled.put(emitter, cancelled);
    emitter.onCompletion(
        new Runnable() {
          @Override
          public void run() {
            // 正常完成或容器侧关闭连接后，阻止后台任务继续向该连接写事件。
            cancelled.set(true);
            heartbeatScheduler.stop(heartbeatRef.get());
            admissionLease.close();
          }
        });
    emitter.onTimeout(
        new Runnable() {
          @Override
          public void run() {
            cancelled.set(true);
            heartbeatScheduler.stop(heartbeatRef.get());
            log.warn(
                "SSE 连接超时（{}ms），取消后台任务 sessionId={}", emitterTimeoutMillis, request.getSessionId());
            cancelTask(taskRef);
            admissionLease.close();
          }
        });
    emitter.onError(
        new java.util.function.Consumer<Throwable>() {
          @Override
          public void accept(Throwable throwable) {
            cancelled.set(true);
            heartbeatScheduler.stop(heartbeatRef.get());
            // 客户端断开是常态路径，debug 留痕即可；同时中断后台任务，释放线程池与下游 Runtime 连接。
            log.debug(
                "SSE 连接异常（客户端可能已断开）sessionId={}: {}",
                request.getSessionId(),
                throwable.getMessage());
            cancelTask(taskRef);
            admissionLease.close();
          }
        });
    final Future<?> future;
    try {
      future =
          executor.submit(
              new Runnable() {
                @Override
                public void run() {
                  AuthenticationScope.set(
                      request.getAuthenticatedTenantId(), request.getAuthenticatedUserId());
                  try {
                    handle(request, emitter);
                    // done 之前先把本轮助手消息与会话状态（含推理过程）落库完成，
                    // 确保前端收到 done 后从服务端重载时能拿到完整推理过程，不会被未完成的异步落库覆盖丢失。
                    persistence.awaitPersistFlush();
                    sender.send(emitter, "done", Collections.singletonMap("ok", true));
                    sender.completeQuietly(emitter);
                  } catch (BossAuthRequiredException e) {
                    try {
                      // 登录续跑依赖处理器刚写入的槽位和工具状态；先排空持久化队列，再通知前端扫码。
                      persistence.awaitPersistFlush();
                      sender.send(emitter, "auth_required", e.getAuthData());
                      sender.send(emitter, "done", Collections.singletonMap("ok", false));
                    } catch (Exception sendError) {
                      // 客户端可能已断开，写 SSE 失败属预期，debug 留痕即可。
                      log.debug("下发 auth_required 事件失败（客户端可能已断开）: {}", sendError.getMessage());
                    }
                    sender.completeQuietly(emitter);
                  } catch (Exception e) {
                    if (cancelled.get() || isClientDisconnect(e)) {
                      // Broken pipe / connection reset 表示浏览器刷新、切换会话或主动取消，属于正常生命周期。
                      // 某些容器会先让 send 抛异常、稍后才触发 emitter.onError，因此不能只依赖 cancelled 标记。
                      cancelled.set(true);
                      log.debug(
                          "SSE 客户端已断开，终止后台任务 sessionId={}: {}",
                          request.getSessionId(),
                          e.getMessage());
                    } else {
                      String message = errorMessage(e, "智能引擎处理失败，请稍后重试。");
                      log.warn("SSE 会话处理异常: {}", message, e);
                      try {
                        sender.send(emitter, "error", Collections.singletonMap("message", message));
                        sender.send(emitter, "done", Collections.singletonMap("ok", false));
                      } catch (Exception sendError) {
                        // 客户端可能已断开，写 SSE 失败属预期，debug 留痕即可。
                        log.debug("下发 error 事件失败（客户端可能已断开）: {}", sendError.getMessage());
                      }
                    }
                    sender.completeQuietly(emitter);
                  } finally {
                    // 任务结束后停止保活并清理取消标记与线程身份，避免连接泄漏或线程池复用时串用其他用户凭据。
                    cancelled.set(true);
                    heartbeatScheduler.stop(heartbeatRef.get());
                    emitterCancelled.remove(emitter);
                    AuthenticationScope.clear();
                    admissionLease.close();
                  }
                }
              });
    } catch (RejectedExecutionException exception) {
      cancelled.set(true);
      emitterCancelled.remove(emitter);
      admissionLease.close();
      throw new com.jobbuddy.backend.modules.chat.exception.ChatStreamRejectedException(
          "流式任务执行队列已满，请稍后重试", true);
    }
    taskRef.set(future);
    long heartbeatIntervalMillis =
        agentServiceProperties.getStreamHeartbeatInterval() == null
            ? 0L
            : agentServiceProperties.getStreamHeartbeatInterval().toMillis();
    ScheduledFuture<?> heartbeat =
        heartbeatScheduler.start(
            emitter,
            sender,
            cancelled,
            heartbeatIntervalMillis,
            request.getSessionId(),
            new Runnable() {
              @Override
              public void run() {
                cancelTask(taskRef);
              }
            });
    heartbeatRef.set(heartbeat);
    // 极短请求可能在心跳任务登记前已经结束；登记后再次检查，避免留下永不发送但持续调度的任务。
    if (cancelled.get()) heartbeatScheduler.stop(heartbeat);
    return emitter;
  }

  static boolean isClientDisconnect(Throwable error) {
    Throwable current = error;
    for (int depth = 0; current != null && depth < 8; depth++) {
      if (current instanceof AsyncRequestNotUsableException
          || current.getClass().getName().contains("ClientAbortException")) {
        return true;
      }
      String message = current.getMessage() == null ? "" : current.getMessage().toLowerCase();
      if (message.contains("broken pipe")
          || message.contains("connection reset by peer")
          || message.contains("disconnected client")
          || message.contains("connection aborted")) {
        return true;
      }
      current = current.getCause();
    }
    return false;
  }

  private void cancelTask(AtomicReference<Future<?>> taskRef) {
    Future<?> task = taskRef.get();
    if (task != null && !task.isDone()) {
      // 中断后台线程：阻塞中的下游 HTTP/Runtime 读会收到中断异常并沿调用栈退出。
      task.cancel(true);
    }
  }

  private void handle(ChatStreamRequest request, SseEmitter emitter) throws IOException {
    String sessionId =
        request.getSessionId() == null || request.getSessionId().isEmpty()
            ? "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            : request.getSessionId();
    // 首包优先：先把会话反馈直接写入 SSE，不做任何 DB/文件 IO，避免用户看到长时间空白。
    sender.send(emitter, "session", Collections.singletonMap("sessionId", sessionId));

    ChatSessionState state = sessionStore.getOrCreate(sessionId);
    // 扫码续跑与换一批都是确定性动作：必须存在上一轮检索条件才能短路，否则优雅回退到正常意图管线。
    boolean resumeAfterAuthRequested = Boolean.TRUE.equals(request.getResumeAfterAuth());
    boolean flipJobsRequested = Boolean.TRUE.equals(request.getFlipJobs());
    boolean hasLastSlots = state.lastSlots != null && !state.lastSlots.isEmpty();
    boolean resumeAfterAuth = resumeAfterAuthRequested && hasLastSlots;
    boolean flipJobs = flipJobsRequested && hasLastSlots;
    // 普通用户消息在任务理解和外部调用前按 turnId 原子落库；重复 turn 直接结束，不再次执行 Runtime/Boss。
    // 没有 turnId 的旧客户端保留顺序异步写入兼容。扫码续跑和换一批属于既有动作，继续跳过写入。
    if (!resumeAfterAuthRequested && !flipJobsRequested) {
      String turnId = request.getTurnId() == null ? "" : request.getTurnId().trim();
      if (!turnId.isEmpty()) {
        boolean accepted =
            sessionStore.appendUserMessageOnce(sessionId, turnId, request.getMessage());
        if (!accepted) return;
      } else {
        persistence.appendMessageAsync(sessionId, "user", request.getMessage(), null);
      }
    }
    // 每轮（含扫码续跑、换一批）都重置本轮过程事件与匹配结果，避免上一轮过程被二次累积重复展示。
    state.toolEvents = new java.util.ArrayList<Map<String, Object>>();
    state.resumeMatch = null;
    if (request.getResumeId() != null && !request.getResumeId().isEmpty()) {
      state.resumeId = request.getResumeId();
    }

    // 聊天岗位卡片上的“分析此岗位”是确定性的单岗位分析入口，直接进入流式分析，
    // 不再走同步弹窗接口，也避免任务理解误把它扩展成整批岗位分析。
    if (isSelectedJobAnalysis(request)) {
      selectedJobAnalysisHandler.handle(
          emitter, sessionId, state, request.getMessage(), request.getSelectedJob());
      return;
    }

    // 换简历复评在按需加载 JD 时也可能触发登录引导。扫码后必须恢复原 resume.match，
    // 不能落入岗位搜索续跑，否则会把已解析正确的省略追问改写成另一个业务动作。
    if (resumeAfterAuth && shouldResumeSelectedJobMatchAfterAuth(request, state)) {
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus(
              "auth_resume",
              "登录后继续复评",
              "success",
              "Boss 登录完成，继续使用当前简历复评上一轮选中岗位。",
              state.lastSlots));
      IntentResult resumedIntent =
          new IntentResult(
              "job",
              "resume.match",
              1.0,
              Collections.<String>emptyList(),
              "low",
              false,
              "run_resume_match",
              new LinkedHashMap<String, Object>(state.lastSlots));
      Map<String, Object> taskMetadata =
          Collections.<String, Object>singletonMap("reuse_previous_slots", true);
      Map<String, Object> task = Collections.<String, Object>singletonMap("metadata", taskMetadata);
      Map<String, Object> resumedDirective = Collections.<String, Object>singletonMap("task", task);
      resumeFlowHandler.handleResumeMatch(
          emitter, sessionId, state, resumedIntent, request.getMessage(), resumedDirective);
      return;
    }

    // 岗位搜索扫码登录后的续跑：复用上一轮检索条件，跳过任务理解直接继续搜索，与登录提示合并为同一段连续过程。
    if (resumeAfterAuth) {
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus("auth_resume", "登录后继续执行", "success", "Boss 登录完成，继续岗位搜索。", state.lastSlots));
      IntentResult resumedIntent =
          new IntentResult(
              "job",
              "job.recommend",
              1.0,
              Collections.<String>emptyList(),
              "low",
              false,
              "call_get_recommend_jobs",
              state.lastSlots);
      jobRecommendHandler.handle(emitter, sessionId, state, resumedIntent);
      return;
    }
    // 换一批：复用上一轮检索条件并翻到候选池下一批，跳过意图预判与任务理解的模型往返，命中缓存即时刷新。
    if (flipJobs) {
      int nextPage = jobRecommendHandler.currentBossPage(state.lastSlots) + 1;
      Map<String, Object> flipSlots = new LinkedHashMap<String, Object>(state.lastSlots);
      flipSlots.put("boss_page", nextPage);
      state.lastSlots = flipSlots;
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus(
              "job_flip", "换一批", "success", "复用上一轮检索条件，直接翻到第 " + nextPage + " 批岗位。", flipSlots));
      IntentResult flipIntent =
          new IntentResult(
              "job",
              "job.recommend",
              1.0,
              Collections.<String>emptyList(),
              "low",
              false,
              "call_get_recommend_jobs",
              flipSlots);
      jobRecommendHandler.handle(emitter, sessionId, state, flipIntent, true);
      return;
    }

    // 仅正常路径提示“任务理解中”：确定性短路（续跑/换一批）不再出现该过程框。
    // 该 running 状态只发流不落库，后续 success 状态会累积到内存状态并在本轮结束时统一落库。
    sender.send(
        emitter,
        "tool_status",
        toolStatus(
            "runtime_understanding", "Runtime 任务理解", "running", "已收到请求，正在理解你的问题并准备作答。", null));

    // 保持原有记忆边界：选中岗位分析已在上方直接返回，不写入长期记忆；普通问答才进入记忆提取。
    if (!resumeAfterAuthRequested && !flipJobsRequested) {
      memoryWriter.captureLongTermMemoryAsync(
          request.getAuthenticatedTenantId(),
          request.getAuthenticatedUserId(),
          request.getMessage());
    }
    // 选中岗位分析：把岗位关键信息注入 Runtime 消息上下文，回答仍走常规问答持久化链路。
    String effectiveMessage =
        withSelectedJobContext(request.getMessage(), request.getSelectedJob());

    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus("request_init", "初始化会话", "success", "会话已建立，准备调用 Agent Runtime。", null));

    // 快速预分类：先经过 agent-intent 这层独立、廉价的意图与风险预判，再决定是否进入较重的 runtime 链路。
    // 预判结果作为提示注入 runtime（不替换权威路由），并通过 intent_precheck 事件透出用于观测。
    IntentResult preIntent = intentService.classify(effectiveMessage);
    sender.send(emitter, "intent_precheck", preIntent);
    if (isSafetyGateBlocked(preIntent)) {
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus("intent_safety_gate", "高风险拦截", "error", "该请求被独立安全门控判定为高风险并拒绝执行。", preIntent));
      sender.sendAssistant(
          emitter,
          sessionId,
          state,
          "抱歉，该请求被判定为高风险，已被安全策略拒绝，无法继续执行。",
          Collections.<String, Object>singletonMap("intentPrecheck", preIntent));
      return;
    }

    Map<String, Object> directive =
        runTaskUnderstanding(sessionId, effectiveMessage, state, preIntent);
    IntentResult intent = intentFromRuntime(directive);
    Object selectedJobContext =
        state.lastSlots == null ? null : state.lastSlots.get(SELECTED_JOB_CONTEXT_KEY);
    state.lastSlots =
        intent.getSlots() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(intent.getSlots());
    if (selectedJobContext instanceof Map) {
      // Runtime 每轮会产生新的业务槽位，但上一轮明确选中的岗位需要跨轮保留，供“换简历再看”复评。
      state.lastSlots.put(SELECTED_JOB_CONTEXT_KEY, selectedJobContext);
    }
    sender.send(emitter, "intent", intent);
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "runtime_understanding",
            "Runtime 任务理解",
            "success",
            intent.getDomain() + "/" + intent.getIntent() + "，置信度 " + intent.getConfidence(),
            directive));

    handleDirective(emitter, sessionId, effectiveMessage, state, directive, intent);
  }

  /** 安全门控：仅当配置开关开启，且预判为高风险并建议拒绝时拦截。默认关闭，主链路行为与现状一致。 */
  private boolean isSafetyGateBlocked(IntentResult preIntent) {
    if (!properties.isIntentSafetyGateEnabled() || preIntent == null) return false;
    return "high".equalsIgnoreCase(stringValue(preIntent.getRisk()))
        && "reject".equalsIgnoreCase(stringValue(preIntent.getNextAction()));
  }

  private Map<String, Object> runTaskUnderstanding(
      String sessionId, String message, ChatSessionState state, IntentResult preIntent) {
    // 任务理解只需意图/能力路由/directive，这里短路 Runtime 图，跳过上下文装配、Tool Search、Planner、合成，
    // 把一次多余的 LLM/工具往返从首字延迟链路上移除；真正的答案合成由后续流式托管调用完成。
    RuntimeRunRequest request =
        RuntimeRequestBuilder.forEntrypoint(sessionId, message, "chat.stream")
            .messages(taskContextBuilder.build(state, message))
            .budget(1, 0, 1, Math.min(properties.getRuntimeMaxTokens(), 4096))
            .metadata("understanding_only", true)
            .metadata("intent_hint", intentHint(preIntent))
            .metadata("resume_id", state == null ? null : state.resumeId)
            .metadata(
                "previous_slots",
                state == null || state.lastSlots == null ? Collections.emptyMap() : state.lastSlots)
            .metadata(
                "current_jobs_count", state == null || state.jobs == null ? 0 : state.jobs.size())
            .metadata(
                "personal_context", requestFactory.buildUnderstandingContext(message, null, state))
            .build();
    RuntimeRunResult runtimeResult = integrationService.runRuntime(request);
    Map<String, Object> result =
        runtimeResult == null ? Collections.<String, Object>emptyMap() : runtimeResult.toMap(JSON);
    Map<String, Object> directive = RuntimeRequestBuilder.extractDirective(result);
    if (directive == null || directive.isEmpty()) {
      // 区分两种失败：result 为空说明 Runtime 不可达或返回空响应；result 非空但缺 directive
      // 说明 Runtime 应答但任务理解结构异常。统一报 "不可用" 会掩盖真实根因，影响排障。
      if (result == null || result.isEmpty()) {
        throw new IllegalStateException("Agent Runtime 未返回结果，请检查服务可用性与 runtime-url 配置。");
      }
      throw new IllegalStateException(
          "Agent Runtime 任务理解结果缺少 directive：" + summarizeRuntimeResult(result));
    }
    directive.put("runtime_result", result == null ? Collections.emptyMap() : result);
    return directive;
  }

  private void handleDirective(
      SseEmitter emitter,
      String sessionId,
      String rawMessage,
      ChatSessionState state,
      Map<String, Object> directive,
      IntentResult intent)
      throws IOException {
    String action = directiveAction(directive, intent);
    if (matchesCapability(action, intent, "call_login", "trigger_boss_login", "auth.login")) {
      Map<String, Object> login = jobRuntimeService.startBossLogin(sessionId);
      if (!Boolean.TRUE.equals(login.get("authRequired"))) {
        sender.sendAssistant(
            emitter,
            sessionId,
            state,
            "Boss 登录态有效，可继续筛选岗位或查看详情。",
            Collections.<String, Object>singletonMap("runtimeDirective", directive));
        return;
      }
      throw new BossAuthRequiredException("Boss 直聘未登录，请先完成二维码登录。", login);
    }
    if (matchesCapability(
        action, intent, "call_get_recommend_jobs", "run_job_recommend", "job.recommend")) {
      jobRecommendHandler.handle(emitter, sessionId, state, intent, false, rawMessage);
      return;
    }
    if (matchesCapability(
        action, intent, "call_resume_match", "run_resume_match", "resume.match")) {
      resumeFlowHandler.handleResumeMatch(emitter, sessionId, state, intent, rawMessage, directive);
      return;
    }
    if (matchesCapability(
        action, intent, "call_resume_analyze", "run_resume_analyze", "resume.analyze")) {
      resumeFlowHandler.handleResumeAnalyze(emitter, sessionId, state);
      return;
    }
    runtimeManagedTaskHandler.handle(emitter, sessionId, rawMessage, state, directive, intent);
  }

  static boolean shouldResumeSelectedJobMatchAfterAuth(
      ChatStreamRequest request, ChatSessionState state) {
    if (request == null
        || !Boolean.TRUE.equals(request.getResumeAfterAuth())
        || state == null
        || state.lastSlots == null
        || !state.lastSlots.containsKey(SELECTED_JOB_CONTEXT_KEY)) return false;
    return ResumeFlowHandler.isSelectedJobResumeFollowUp(request.getMessage())
        || "resume_switch_rematch".equals(stringValue(state.lastSlots.get("follow_up")));
  }

  private boolean isSelectedJobAnalysis(ChatStreamRequest request) {
    return request != null
        && request.getSelectedJob() != null
        && !request.getSelectedJob().isEmpty()
        && !Boolean.TRUE.equals(request.getFlipJobs());
  }
}
