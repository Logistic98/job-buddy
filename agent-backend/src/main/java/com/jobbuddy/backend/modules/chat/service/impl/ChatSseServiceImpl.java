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
import com.jobbuddy.backend.modules.chat.dto.response.ChatMessageResponse;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunResult;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.service.ChatAttachmentService;
import com.jobbuddy.backend.modules.chat.service.ChatAttachmentTurnResult;
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
import java.util.Optional;
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

/**
 * SSE 会话主编排：负责连接生命周期、意图分流与各业务链路的调度； 事件下发、持久化、记忆写入与各链路细节由同包协作类承载。
 */
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
  private final ChatMemoryCommandHandler memoryCommandHandler;
  private final ChatTaskContextBuilder taskContextBuilder;
  private final RuntimeManagedRequestFactory requestFactory;
  private final SelectedJobAnalysisHandler selectedJobAnalysisHandler;
  private final ResumeFlowHandler resumeFlowHandler;
  private final JobRecommendHandler jobRecommendHandler;
  private final RuntimeManagedTaskHandler runtimeManagedTaskHandler;
  private final ChatAttachmentService attachmentService;

  /**
   * 创建具名线程工厂。
   *
   * @param prefix 名称前缀
   * @return 创建后的具名线程工厂
   */
  private static java.util.concurrent.ThreadFactory namedThreadFactory(final String prefix) {
    final AtomicInteger seq = new AtomicInteger(1);
    return new java.util.concurrent.ThreadFactory() {
      /**
       * 生成线程。
       *
       * @param runnable 待执行任务
       * @return 线程
       */
      @Override
      public Thread newThread(Runnable runnable) {
        Thread thread = new Thread(runnable, prefix + "-" + seq.getAndIncrement());
        thread.setDaemon(true);
        return thread;
      }
    };
  }

  /**
   * 创建对话 SSE 服务实例。
   *
   * @param jobRuntimeService 岗位运行时服务
   * @param sessionStore 会话存储
   * @param integrationService 集成服务
   * @param intentService 意图服务
   * @param resumeStorageService 简历存储服务
   * @param bossCliService Boss CLI 服务
   * @param personalContextBuilder 个人上下文构建器
   * @param settingsService 设置服务
   * @param properties 配置属性
   * @param agentServiceProperties Agent 服务配置属性
   * @param admissionController 准入控制器
   */
  public ChatSseServiceImpl(
      JobRuntimeService jobRuntimeService,
      ChatSessionStore sessionStore,
      AgentIntegrationService integrationService,
      IntentService intentService,
      ChatAttachmentService attachmentService,
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
    this.attachmentService = attachmentService;
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
    this.memoryCommandHandler = new ChatMemoryCommandHandler(settingsService);
    this.taskContextBuilder = new ChatTaskContextBuilder(sessionStore);
    this.requestFactory =
        new RuntimeManagedRequestFactory(
            integrationService, personalContextBuilder, resumeStorageService, properties);
    CurrentResumeLoader resumeLoader = new CurrentResumeLoader(resumeStorageService);
    SelectedJobContextResolver selectedJobContextResolver = new SelectedJobContextResolver();
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

  /**
   * 关闭任务执行器。
   */
  @PreDestroy
  public void shutdownExecutors() {
    executor.shutdownNow();
    heartbeatScheduler.shutdown();
    // 持久化队列允许已提交任务执行完毕，避免关停时丢失尚未落库的会话消息。
    persistence.shutdown();
  }

  /**
   * 建立聊天 SSE 流。
   *
   * @param request 请求对象
   * @return SSE 事件流
   */
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
          /**
           * 清理已正常结束连接占用的后台资源。
           */
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
          /**
           * 中断超时连接对应的后台任务并释放准入许可。
           */
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
          /**
           * 接收并处理输入。
           *
           * @param throwable 异常
           */
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
                /**
                 * 在认证上下文内执行完整 SSE 会话并统一释放资源。
                 */
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
              /**
               * 在心跳写入失败后取消主处理任务。
               */
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

  /**
   * 判断是否为客户端断开连接。
   *
   * @param error 异常
   * @return 是否为客户端断开连接
   */
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

  /**
   * 取消任务。
   *
   * @param taskRef 任务引用
   */
  private void cancelTask(AtomicReference<Future<?>> taskRef) {
    Future<?> task = taskRef.get();
    if (task != null && !task.isDone()) {
      // 中断后台线程：阻塞中的下游 HTTP/Runtime 读会收到中断异常并沿调用栈退出。
      task.cancel(true);
    }
  }

  /**
   * 处理已选岗位分析。
   *
   * @param request 请求对象
   * @param emitter SSE 事件发送器
   * @throws IOException 文件或网络读写失败时抛出
   */
  private void handle(ChatStreamRequest request, SseEmitter emitter) throws IOException {
    boolean sessionIdProvided =
        request.getSessionId() != null && !request.getSessionId().trim().isEmpty();
    String sessionId =
        !sessionIdProvided
            ? "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            : request.getSessionId();
    // 首包优先：先把会话反馈直接写入 SSE，不做任何 DB/文件 IO，避免用户看到长时间空白。
    sender.send(emitter, "session", Collections.singletonMap("sessionId", sessionId));

    ChatSessionState state = sessionStore.getOrCreate(sessionId);
    // 扫码续跑与换一批都是确定性动作：必须存在上一轮检索条件才能短路，否则优雅回退到正常意图管线。
    boolean resumeAfterAuthRequested = Boolean.TRUE.equals(request.getResumeAfterAuth());
    boolean checkpointResumeRequested =
        request.getResumeRunId() != null && !request.getResumeRunId().trim().isEmpty();
    boolean flipJobsRequested = Boolean.TRUE.equals(request.getFlipJobs());
    boolean hasLastSlots = state.lastSlots != null && !state.lastSlots.isEmpty();
    boolean resumeAfterAuth = resumeAfterAuthRequested && hasLastSlots;
    boolean flipJobs = flipJobsRequested && hasLastSlots;
    String turnId = request.getTurnId() == null ? "" : request.getTurnId().trim();
    if (checkpointResumeRequested) {
      restoreCheckpointTurn(request, sessionId, turnId);
    }
    boolean hasAttachments =
        request.getAttachmentIds() != null && !request.getAttachmentIds().isEmpty();
    state.attachments = new java.util.ArrayList<Map<String, Object>>();
    if ((resumeAfterAuthRequested || checkpointResumeRequested) && hasAttachments) {
      state.attachments =
          attachmentService.bindForTurn(
              request.getAttachmentIds(),
              request.getAuthenticatedTenantId(),
              request.getAuthenticatedUserId(),
              sessionId,
              turnId);
    }
    // 普通用户消息与换一批都在外部调用前按 turnId 原子落库；重复 turn 直接结束，不再次执行 Runtime/Boss。
    // 没有 turnId 的旧客户端保留顺序异步写入兼容。扫码续跑和 checkpoint 恢复属于既有动作，继续跳过写入。
    if (!resumeAfterAuthRequested && !checkpointResumeRequested) {
      if (!turnId.isEmpty()) {
        boolean accepted;
        if (hasAttachments) {
          ChatAttachmentTurnResult binding =
              attachmentService.bindAndAppendUserMessage(
                  request.getAttachmentIds(),
                  request.getAuthenticatedTenantId(),
                  request.getAuthenticatedUserId(),
                  sessionId,
                  turnId,
                  request.getMessage());
          state.attachments = binding.getAttachments();
          accepted = binding.isAccepted();
        } else {
          accepted = sessionStore.appendUserMessageOnce(sessionId, turnId, request.getMessage());
        }
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

    // Checkpoint 续跑是对既有 Runtime run 的确定性恢复：不重复写入用户消息、长期记忆和任务理解，
    // 仅用当前鉴权身份和上下文重建被脱敏剥离的请求数据，然后交给 Runtime 校验来源与原子领取。
    if (checkpointResumeRequested) {
      runtimeManagedTaskHandler.handleResume(
          emitter, sessionId, request.getMessage(), state, request.getResumeRunId().trim(), turnId);
      return;
    }

    // 聊天岗位卡片上的“分析此岗位”是确定性的单岗位分析入口，直接进入流式分析，
    // 不再走同步弹窗接口，也避免任务理解误把它扩展成整批岗位分析。
    if (isSelectedJobAnalysis(request)) {
      selectedJobAnalysisHandler.handle(
          emitter, sessionId, state, request.getMessage(), request.selectedJobMap());
      return;
    }

    // 兼容已进入 Boss 登录续跑的历史复评请求：恢复原 resume.match，不能落入岗位搜索续跑，
    // 否则会把已解析正确的省略追问改写成另一个业务动作。新复评仅复用检索快照，不再触发详情登录。
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

    Optional<ChatMemoryMutationResult> memoryMutation =
        memoryCommandHandler.handle(
            request.getAuthenticatedTenantId(),
            request.getAuthenticatedUserId(),
            request.getMessage());
    if (memoryMutation.isPresent()) {
      ChatMemoryMutationResult result = memoryMutation.get();
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus(
              "memory_" + result.action(),
              "长期记忆" + memoryActionLabel(result.action()),
              result.success() ? "success" : "error",
              result.summary(),
              null));
      sender.sendAssistant(emitter, sessionId, state, result.assistantMessage());
      return;
    }

    // 仅正常路径提示“任务理解中”：确定性短路（续跑/换一批）不再出现该过程框。
    // 该 running 状态只发流不落库，后续 success 状态会累积到内存状态并在本轮结束时统一落库。
    sender.send(
        emitter,
        "tool_status",
        toolStatus(
            "runtime_understanding", "Runtime 任务理解", "running", "已收到请求，正在理解你的问题并准备作答。", null));
    long understandingStartedNanos = System.nanoTime();

    // 保持原有记忆边界：选中岗位分析已在上方直接返回，不写入长期记忆；普通问答才进入记忆提取。
    if (!resumeAfterAuthRequested && !flipJobsRequested) {
      memoryWriter.captureLongTermMemoryAsync(
          request.getAuthenticatedTenantId(),
          request.getAuthenticatedUserId(),
          request.getMessage());
    }
    // 选中岗位分析：把岗位关键信息注入 Runtime 消息上下文，回答仍走常规问答持久化链路。
    String effectiveMessage =
        withSelectedJobContext(request.getMessage(), request.selectedJobMap());

    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus("request_init", "初始化会话", "success", "会话已建立，准备调用 Agent Runtime。", null));

    // 快速预分类：先经过 agent-intent 这层独立、廉价的意图与风险预判，再决定是否进入较重的 runtime 链路。
    // 预判结果作为提示注入 runtime（不替换权威路由），并通过 intent_precheck 事件透出用于观测。
    long precheckStartedNanos = System.nanoTime();
    IntentResult preIntent = intentService.classify(effectiveMessage);
    long precheckElapsedMillis = elapsedMillis(precheckStartedNanos);
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

    long runtimeUnderstandingStartedNanos = System.nanoTime();
    Map<String, Object> directive =
        runTaskUnderstanding(sessionId, effectiveMessage, state, preIntent, !state.newlyCreated);
    long runtimeUnderstandingElapsedMillis = elapsedMillis(runtimeUnderstandingStartedNanos);
    IntentResult intent = intentFromRuntime(directive);
    log.info(
        "智能引擎任务理解分段耗时 sessionId={} precheckMs={} runtimeStageMs={} totalMs={} preRouter={}"
            + " runtimeRouter={}",
        sessionId,
        precheckElapsedMillis,
        runtimeUnderstandingElapsedMillis,
        elapsedMillis(understandingStartedNanos),
        preIntent == null ? null : preIntent.getRouter(),
        intent.getRouter());
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

    handleDirective(emitter, sessionId, effectiveMessage, state, directive, intent, turnId);
  }

  /**
   * 从当前用户持久化的原始 turn 重建 checkpoint 续跑请求，拒绝信任客户端回传的消息正文和附件集合。
   *
   * @param request 当前请求
   * @param sessionId 会话标识
   * @param turnId 原始轮次标识
   */
  private void restoreCheckpointTurn(ChatStreamRequest request, String sessionId, String turnId) {
    if (turnId == null || turnId.isBlank()) {
      throw new IllegalArgumentException("checkpoint 续跑缺少原始 turnId");
    }
    ChatMessageResponse source =
        sessionStore
            .listMessages(
                request.getAuthenticatedTenantId(), request.getAuthenticatedUserId(), sessionId)
            .stream()
            .filter(item -> "user".equals(item.getRole()))
            .filter(item -> turnId.equals(item.getTurnId()))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("checkpoint 原始用户消息不存在或归属不匹配"));
    request.setMessage(source.getContent());
    java.util.List<String> attachmentIds = new java.util.ArrayList<String>();
    if (source.getAttachments() != null && source.getAttachments().isArray()) {
      source
          .getAttachments()
          .forEach(
              item -> {
                String attachmentId = item.path("attachmentId").asText("").trim();
                if (!attachmentId.isEmpty()) attachmentIds.add(attachmentId);
              });
    }
    request.setAttachmentIds(attachmentIds);
  }

  private String memoryActionLabel(String action) {
    if ("create".equals(action)) return "新增";
    if ("update".equals(action)) return "更新";
    if ("delete".equals(action)) return "删除";
    return "处理";
  }

  /**
   * 安全门控：仅当配置开关开启，且预判为高风险并建议拒绝时拦截。默认关闭，主链路行为与现状一致。
   *
   * @param preIntent 前置意图结果
   * @return 是否被安全门禁拦截
   */
  private boolean isSafetyGateBlocked(IntentResult preIntent) {
    if (!properties.isIntentSafetyGateEnabled() || preIntent == null) return false;
    return "high".equalsIgnoreCase(stringValue(preIntent.getRisk()))
        && "reject".equalsIgnoreCase(stringValue(preIntent.getNextAction()));
  }

  /**
   * 执行任务理解。
   *
   * @param sessionId 会话标识
   * @param message 消息内容
   * @param state 状态
   * @param preIntent 前置意图结果
   * @param loadHistory 是否读取既有会话历史
   * @return 任务理解结果
   */
  private Map<String, Object> runTaskUnderstanding(
      String sessionId,
      String message,
      ChatSessionState state,
      IntentResult preIntent,
      boolean loadHistory) {
    // 任务理解只需意图/能力路由/directive，这里短路 Runtime 图，跳过上下文装配、Tool Search、Planner、合成，
    // 把一次多余的 LLM/工具往返从首字延迟链路上移除；真正的答案合成由后续流式托管调用完成。
    long contextStartedNanos = System.nanoTime();
    java.util.List<Map<String, Object>> messages =
        loadHistory
            ? taskContextBuilder.build(state, message)
            : taskContextBuilder.buildCurrentMessageOnly(message);
    Map<String, Object> understandingContext =
        requestFactory.buildUnderstandingContext(message, preIntent, state);
    long contextElapsedMillis = elapsedMillis(contextStartedNanos);
    RuntimeRunRequest request =
        RuntimeRequestBuilder.forEntrypoint(sessionId, message, "chat.stream")
            .messages(messages)
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
                "attachments",
                state == null || state.attachments == null
                    ? Collections.emptyList()
                    : attachmentReferences(state.attachments))
            .metadata("personal_context", understandingContext)
            .build();
    long runtimeCallStartedNanos = System.nanoTime();
    RuntimeRunResult runtimeResult = integrationService.runRuntime(request);
    long runtimeCallElapsedMillis = elapsedMillis(runtimeCallStartedNanos);
    log.info(
        "Runtime 任务理解调用耗时 sessionId={} contextMs={} runtimeCallMs={}",
        sessionId,
        contextElapsedMillis,
        runtimeCallElapsedMillis);
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

  /**
   * 把单调时钟起点转换为毫秒耗时。
   *
   * @param startedNanos {@link System#nanoTime()} 起点
   * @return 非负毫秒耗时
   */
  private static long elapsedMillis(long startedNanos) {
    return Math.max(0L, TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos));
  }

  /**
   * 生成附件公开引用列表。
   *
   * @param attachments 运行时附件上下文
   * @return 公开引用
   */
  private java.util.List<Map<String, Object>> attachmentReferences(
      java.util.List<Map<String, Object>> attachments) {
    java.util.List<Map<String, Object>> result = new java.util.ArrayList<Map<String, Object>>();
    if (attachments == null) return result;
    for (Map<String, Object> attachment : attachments) {
      if (attachment == null) continue;
      Map<String, Object> ref = new LinkedHashMap<String, Object>(attachment);
      ref.remove("content");
      ref.remove("untrusted");
      ref.remove("injectionHits");
      result.add(ref);
    }
    return result;
  }

  /**
   * 处理运行时指令。
   *
   * @param emitter SSE 事件发送器
   * @param sessionId 会话标识
   * @param rawMessage 原始消息
   * @param state 状态
   * @param directive 运行时指令
   * @param intent 意图
   * @param turnId 当前轮次标识
   * @throws IOException 文件或网络读写失败时抛出
   */
  private void handleDirective(
      SseEmitter emitter,
      String sessionId,
      String rawMessage,
      ChatSessionState state,
      Map<String, Object> directive,
      IntentResult intent,
      String turnId)
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
    runtimeManagedTaskHandler.handle(
        emitter, sessionId, rawMessage, state, directive, intent, turnId);
  }

  /**
   * 判断认证后是否恢复岗位匹配。
   *
   * @param request 请求对象
   * @param state 状态
   * @return 认证后是否恢复岗位匹配是否成立
   */
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

  /**
   * 判断已选项岗位分析。
   *
   * @param request 请求对象
   * @return 已选项岗位分析是否成立
   */
  private boolean isSelectedJobAnalysis(ChatStreamRequest request) {
    return request != null
        && request.selectedJobMap() != null
        && !request.selectedJobMap().isEmpty()
        && !Boolean.TRUE.equals(request.getFlipJobs());
  }
}
