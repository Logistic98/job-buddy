package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.toolStatus;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.security.AuthenticationScope;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.JobRecommendationResult;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.prompt.model.PersonalContext;
import com.jobbuddy.backend.modules.prompt.service.PersonalContextBuilder;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 岗位推荐链路：Boss 岗位搜索、卡片下发与岗位消息持久化（普通推荐追加、换一批替换最近岗位消息）。
 */
class JobRecommendHandler {
  private static final String CANDIDATE_OFFSET_SLOT = "candidate_offset";

  private final ChatSseEventSender sender;
  private final ChatPersistenceCoordinator persistence;
  private final JobRuntimeService jobRuntimeService;
  private final PersonalContextBuilder personalContextBuilder;
  private final CurrentResumeLoader resumeLoader;
  private final JobBuddyProperties properties;
  private final Executor recommendationPreparationExecutor;

  /**
   * 创建岗位推荐处理器实例。
   *
   * @param sender SSE 事件发送器
   * @param persistence 持久化协调器
   * @param jobRuntimeService 岗位运行时服务
   * @param personalContextBuilder 个人上下文构建器
   * @param resumeLoader 简历加载器
   * @param properties 配置属性
   */
  JobRecommendHandler(
      ChatSseEventSender sender,
      ChatPersistenceCoordinator persistence,
      JobRuntimeService jobRuntimeService,
      PersonalContextBuilder personalContextBuilder,
      CurrentResumeLoader resumeLoader,
      JobBuddyProperties properties) {
    this(
        sender,
        persistence,
        jobRuntimeService,
        personalContextBuilder,
        resumeLoader,
        properties,
        Runnable::run);
  }

  /**
   * 创建支持简历预取的岗位推荐处理器。
   *
   * @param sender SSE 事件发送器
   * @param persistence 持久化协调器
   * @param jobRuntimeService 岗位运行时服务
   * @param personalContextBuilder 个人上下文构建器
   * @param resumeLoader 简历加载器
   * @param properties 配置属性
   * @param recommendationPreparationExecutor 推荐准备执行器
   */
  JobRecommendHandler(
      ChatSseEventSender sender,
      ChatPersistenceCoordinator persistence,
      JobRuntimeService jobRuntimeService,
      PersonalContextBuilder personalContextBuilder,
      CurrentResumeLoader resumeLoader,
      JobBuddyProperties properties,
      Executor recommendationPreparationExecutor) {
    this.sender = sender;
    this.persistence = persistence;
    this.jobRuntimeService = jobRuntimeService;
    this.personalContextBuilder = personalContextBuilder;
    this.resumeLoader = resumeLoader;
    this.properties = properties;
    this.recommendationPreparationExecutor = recommendationPreparationExecutor;
  }

  /**
   * 读取上一轮检索条件中的候选池页码，缺省或非法时视为第 1 批，供换一批确定性翻页递增使用。
   *
   * @param slots 候选槽位
   * @return 当前 Boss 页码
   */
  int currentBossPage(Map<String, Object> slots) {
    if (slots == null) return 1;
    Object value = slots.get("boss_page");
    if (value instanceof Number) return Math.max(1, ((Number) value).intValue());
    if (value != null) {
      try {
        return Math.max(1, Integer.parseInt(String.valueOf(value).trim()));
      } catch (NumberFormatException ignored) {
        return 1;
      }
    }
    return 1;
  }

  /**
   * 处理已选岗位分析。
   *
   * @param emitter SSE 事件发送器
   * @param sessionId 会话标识
   * @param state 状态
   * @param intent 意图
   * @throws IOException 文件或网络读写失败时抛出
   */
  void handle(SseEmitter emitter, String sessionId, ChatSessionState state, IntentResult intent)
      throws IOException {
    handle(emitter, sessionId, state, intent, false, "");
  }

  /**
   * 处理已选岗位分析。
   *
   * @param emitter SSE 事件发送器
   * @param sessionId 会话标识
   * @param state 状态
   * @param intent 意图
   * @param retainPreviousBatchOnFailure 失败时是否恢复上一批已验证岗位与槽位
   * @throws IOException 文件或网络读写失败时抛出
   */
  void handle(
      SseEmitter emitter,
      String sessionId,
      ChatSessionState state,
      IntentResult intent,
      boolean retainPreviousBatchOnFailure)
      throws IOException {
    handle(emitter, sessionId, state, intent, retainPreviousBatchOnFailure, "");
  }

  /**
   * 处理已选岗位分析。
   *
   * @param emitter SSE 事件发送器
   * @param sessionId 会话标识
   * @param state 状态
   * @param intent 意图
   * @param retainPreviousBatchOnFailure 失败时是否恢复上一批已验证岗位与槽位
   * @param rawMessage 原始消息
   * @throws IOException 文件或网络读写失败时抛出
   */
  void handle(
      SseEmitter emitter,
      String sessionId,
      ChatSessionState state,
      IntentResult intent,
      boolean retainPreviousBatchOnFailure,
      String rawMessage)
      throws IOException {
    List<Map<String, Object>> previousJobs = copyJobs(state.jobs);
    Map<String, Object> previousSlots =
        state.lastSlots != null
            ? new LinkedHashMap<String, Object>(state.lastSlots)
            : Collections.<String, Object>emptyMap();
    long contextStartedAt = System.nanoTime();
    Map<String, Object> contextPayload = new LinkedHashMap<String, Object>();
    contextPayload.put("stage", "load_profile_resume");
    contextPayload.put(
        "slots",
        intent == null || intent.getSlots() == null
            ? Collections.<String, Object>emptyMap()
            : intent.getSlots());
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "recommendation_context",
            "读取画像与简历",
            "running",
            "正在并行读取求职画像、当前简历和岗位筛选约束。",
            contextPayload));
    PersonalContext personalContext;
    try {
      personalContext =
          personalContextBuilder.build(state.tenantId, state.userId, rawMessage, intent, state);
    } catch (RuntimeException exception) {
      Map<String, Object> detail = new LinkedHashMap<String, Object>(contextPayload);
      detail.put("elapsedMs", elapsedMillis(contextStartedAt));
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus(
              "recommendation_context",
              "画像与简历读取失败",
              "error",
              conciseMessage(exception, "画像与简历上下文读取失败。"),
              detail));
      throw exception;
    }
    Map<String, Object> contextDetail = new LinkedHashMap<String, Object>();
    contextDetail.put("contextSources", personalContext.sources());
    contextDetail.put("elapsedMs", elapsedMillis(contextStartedAt));
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "recommendation_context",
            "画像与简历已就绪",
            "success",
            "已完成岗位筛选上下文准备，开始搜索候选岗位。",
            contextDetail));
    IntentResult effectiveIntent =
        JobRecommendationCriteriaBuilder.enrich(intent, personalContext, rawMessage);
    int candidateOffset = currentCandidateOffset(effectiveIntent.getSlots());
    state.lastSlots = new LinkedHashMap<String, Object>(effectiveIntent.getSlots());
    // 完整简历准备不参与 Boss 搜索参数计算，可在槽位补全后与串行候选搜索安全重叠。
    // 质量门等待同一 Future，避免搜索结束后再同步读库和下载简历证据。
    CompletableFuture<ResumePreparation> resumePreparation = prepareResumeAsync(state);
    Map<String, Object> searchPayload = new LinkedHashMap<String, Object>();
    searchPayload.put("stage", "prepare_cli");
    searchPayload.put("slots", effectiveIntent.getSlots());
    searchPayload.put("contextSources", personalContext.sources());
    searchPayload.put("timeoutSeconds", jobRuntimeService.bossCandidatePoolTimeoutSeconds());
    searchPayload.put("liveEnabled", true);
    long searchStartedAt = System.nanoTime();
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus("job_search", "开始搜索岗位", "running", "正在搜索 Boss 岗位，登录失效时会弹出扫码。", searchPayload));
    List<Map<String, Object>> jobs;
    try {
      jobs = jobRuntimeService.recommendJobsFast(effectiveIntent, sessionId, null);
    } catch (BossAuthRequiredException e) {
      resumePreparation.cancel(true);
      String reason =
          e.getMessage() == null || e.getMessage().trim().isEmpty()
              ? "Boss 登录态失效。"
              : e.getMessage();
      Map<String, Object> authData =
          e.getAuthData() == null ? Collections.<String, Object>emptyMap() : e.getAuthData();
      Map<String, Object> detail = new LinkedHashMap<String, Object>();
      detail.put("reason", reason);
      detail.put("authData", authData);
      detail.put("elapsedMs", elapsedMillis(searchStartedAt));
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus("job_search", "需要登录 Boss 直聘", "error", reason, detail));
      // 登录墙出现前保存本轮已解析槽位，扫码完成后才能续跑原任务，而不是重新提交同一条用户消息。
      persistence.saveStateAsync(state);
      throw e;
    } catch (RuntimeException e) {
      resumePreparation.cancel(true);
      String reason =
          e.getMessage() == null || e.getMessage().trim().isEmpty() ? "岗位搜索失败" : e.getMessage();
      boolean retainedPreviousBatch = !previousJobs.isEmpty();
      if (retainedPreviousBatch) {
        state.jobs = copyJobs(previousJobs);
        state.lastSlots = new LinkedHashMap<String, Object>(previousSlots);
      } else {
        state.jobs = new java.util.ArrayList<Map<String, Object>>();
        state.lastSlots = new LinkedHashMap<String, Object>();
      }
      Map<String, Object> detail = new LinkedHashMap<String, Object>(searchPayload);
      detail.put("elapsedMs", elapsedMillis(searchStartedAt));
      detail.put("retainedPreviousBatch", retainedPreviousBatch);
      detail.put("previousJobCount", retainedPreviousBatch ? previousJobs.size() : 0);
      try {
        sender.sendToolStatus(
            emitter, sessionId, state, toolStatus("job_search", "岗位搜索失败", "error", reason, detail));
        sender.sendAssistant(
            emitter,
            sessionId,
            state,
            retainedPreviousBatch
                ? retainedPreviousSearchFailureMessage(retainPreviousBatchOnFailure)
                : reason);
      } finally {
        persistence.saveStateAsync(state);
      }
      return;
    }
    List<Map<String, Object>> initialCandidates = jobs;
    int candidateCount = initialCandidates.size();
    Map<String, Object> jobSearchDetail = new LinkedHashMap<String, Object>();
    jobSearchDetail.put("count", candidateCount);
    jobSearchDetail.put("candidateCount", candidateCount);
    jobSearchDetail.put("mode", "initial_candidate_pool");
    jobSearchDetail.put("elapsedMs", elapsedMillis(searchStartedAt));
    jobSearchDetail.put(
        "sample",
        initialCandidates.isEmpty()
            ? Collections.emptyList()
            : initialCandidates.subList(0, Math.min(3, initialCandidates.size())));
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "job_search",
            "岗位搜索完成",
            "success",
            "累计检索到 " + candidateCount + " 个候选岗位。",
            jobSearchDetail));
    Map<String, Object> gateStart = new LinkedHashMap<String, Object>();
    gateStart.put("candidateCount", candidateCount);
    gateStart.put("minimumScore", properties.getMinimumRecommendedMatchScore());
    gateStart.put("contextSources", personalContext.sources());
    gateStart.put("resumePrefetchStatus", resumePreparation.isDone() ? "ready" : "running");
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "recommendation_quality_gate",
            "画像与简历预筛",
            "running",
            "正在使用求职画像和当前简历验证候选岗位。",
            gateStart));
    long qualityStartedAt = System.nanoTime();
    JobRecommendationResult quality;
    long resumePreparationElapsedMs = 0L;
    try {
      ResumePreparation preparedResume = awaitResumePreparation(resumePreparation);
      ResumeRecord resume = preparedResume.resume;
      resumePreparationElapsedMs = preparedResume.elapsedMs;
      gateStart.put("resumePreparationElapsedMs", resumePreparationElapsedMs);
      quality =
          jobRuntimeService.prequalifyRecommendationsWithContinuation(
              resume, effectiveIntent, jobs, sessionId);
    } catch (BossAuthRequiredException e) {
      String reason =
          e.getMessage() == null || e.getMessage().trim().isEmpty()
              ? "Boss 登录态失效。"
              : e.getMessage();
      Map<String, Object> detail = new LinkedHashMap<String, Object>();
      detail.put("reason", reason);
      detail.put(
          "authData",
          e.getAuthData() == null ? Collections.<String, Object>emptyMap() : e.getAuthData());
      detail.put("elapsedMs", elapsedMillis(qualityStartedAt));
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus("recommendation_quality_gate", "继续检索需要登录 Boss 直聘", "error", reason, detail));
      persistence.saveStateAsync(state);
      throw e;
    } catch (RuntimeException e) {
      String reason =
          e.getMessage() == null || e.getMessage().trim().isEmpty()
              ? "岗位续搜或个性化推荐预筛失败。"
              : e.getMessage();
      boolean retainedPreviousBatch = retainPreviousBatchOnFailure && !previousJobs.isEmpty();
      if (retainedPreviousBatch) {
        state.jobs = copyJobs(previousJobs);
        state.lastSlots = new LinkedHashMap<String, Object>(previousSlots);
      } else {
        state.jobs = new java.util.ArrayList<Map<String, Object>>();
      }
      try {
        Map<String, Object> detail = new LinkedHashMap<String, Object>(gateStart);
        detail.put("elapsedMs", elapsedMillis(qualityStartedAt));
        detail.put("retainedPreviousBatch", retainedPreviousBatch);
        detail.put("previousJobCount", retainedPreviousBatch ? previousJobs.size() : 0);
        sender.sendToolStatus(
            emitter,
            sessionId,
            state,
            toolStatus("recommendation_quality_gate", "推荐质量门未通过", "error", reason, detail));
        sender.sendAssistant(
            emitter,
            sessionId,
            state,
            retainedPreviousBatch
                ? "换一批未能完成，上一批通过画像与简历预筛的岗位消息仍保留，请稍后重试。"
                : "岗位已经召回，但画像与简历匹配预筛未能完成。为避免展示未经验证的岗位，本轮未生成推荐卡片。请稍后重试。");
      } finally {
        // SSE 已超时或客户端断开时，仍需保存与页面一致的岗位状态，避免历史恢复后出现卡片与失败文案矛盾。
        persistence.saveStateAsync(state);
      }
      return;
    }
    jobs = quality.getJobs();
    // 只推进实际完成评分的候选数。质量门提前凑够展示数量时，尚未评分的缓存候选留给下一次“换一批”；
    // 既不重新请求 Boss，也不会重复评分已经消费的岗位。
    state.lastSlots.put(
        CANDIDATE_OFFSET_SLOT, candidateOffset + Math.max(0, quality.getCandidateCount()));
    Map<String, Object> gateDetail = new LinkedHashMap<String, Object>();
    gateDetail.put("candidateCount", quality.getCandidateCount());
    gateDetail.put("requestedMatchCount", quality.getCandidateCount());
    gateDetail.put("returnedMatchCount", quality.getCandidateCount());
    gateDetail.put("missingMatchCount", 0);
    gateDetail.put("scoredCount", quality.getCandidateCount());
    gateDetail.put("unscoredCount", 0);
    gateDetail.put("initialCandidateCount", candidateCount);
    gateDetail.put("continuedSearch", quality.getCandidateCount() > candidateCount);
    gateDetail.put("qualifiedCount", quality.getQualifiedCount());
    gateDetail.put("rejectedCount", quality.getRejectedCount());
    gateDetail.put(
        "funnelAccountedCount", quality.getQualifiedCount() + quality.getRejectedCount());
    gateDetail.put("rejectionReasons", quality.getRejectionReasons());
    gateDetail.put("warnings", quality.getWarnings());
    gateDetail.put("resumePreparationElapsedMs", resumePreparationElapsedMs);
    gateDetail.put("elapsedMs", elapsedMillis(qualityStartedAt));
    boolean continuedSearch = quality.getCandidateCount() > candidateCount;
    if (continuedSearch) {
      // 质量门可能在首批候选不足时继续向后检索。job_search 已在首批返回后闭合，
      // 此处用相同事件 ID 回写最终累计数，前端和持久化层会原位合并，避免搜索数与评估数口径冲突。
      Map<String, Object> completedSearchDetail =
          new LinkedHashMap<String, Object>(jobSearchDetail);
      completedSearchDetail.put("count", quality.getCandidateCount());
      completedSearchDetail.put("candidateCount", quality.getCandidateCount());
      completedSearchDetail.put("initialCandidateCount", candidateCount);
      completedSearchDetail.put("continuedSearch", true);
      completedSearchDetail.put("mode", "continued_candidate_pool");
      completedSearchDetail.put("elapsedMs", elapsedMillis(searchStartedAt));
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus(
              "job_search",
              "岗位搜索完成",
              "success",
              "累计检索到 " + quality.getCandidateCount() + " 个候选岗位。",
              completedSearchDetail));
    }
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "recommendation_quality_gate",
            jobs.isEmpty() ? "当前批次无合格岗位" : "画像与简历预筛完成",
            "success",
            continuedSearch
                ? "首批候选不足后已继续检索，累计评估 "
                    + quality.getCandidateCount()
                    + " 个候选，其中 "
                    + jobs.size()
                    + " 个通过推荐门槛。"
                : jobs.isEmpty() ? "没有岗位同时达到薪资、方向、匹配分和置信度门槛。" : "已有 " + jobs.size() + " 个岗位通过推荐门槛。",
            gateDetail));
    state.jobs = jobs;
    if (jobs.isEmpty()) {
      sender.sendAssistant(
          emitter,
          sessionId,
          state,
          "已继续检索到当前页深或评分预算上限，但仍没有岗位同时满足目标方向、薪资要求以及画像和简历匹配门槛。你可以适当放宽岗位方向、薪资或经验条件后重新搜索。");
      persistence.saveStateAsync(state);
      return;
    }
    sender.send(emitter, "job_cards", jobs);
    // 岗位推荐与换一批都追加独立助手消息；换一批仍复用上一轮槽位和候选池，但在聊天历史中是新的用户动作。
    if (!jobs.isEmpty()) {
      Map<String, Object> turnMeta = new LinkedHashMap<String, Object>();
      turnMeta.put("jobCards", jobs);
      if (state.toolEvents != null && !state.toolEvents.isEmpty()) {
        turnMeta.put("toolEvents", new java.util.ArrayList<Map<String, Object>>(state.toolEvents));
      }
      persistence.appendMessageAsync(sessionId, "assistant", "", turnMeta);
    }
    // 岗位列表与本轮推理过程统一异步落库，确保扫码搜索路径下首屏卡片即时呈现、不被持久化阻塞。
    persistence.saveStateAsync(state);
  }

  /**
   * 在专用执行器中预取匹配所需的完整简历，并显式传播租户与用户身份。
   *
   * @param state 会话状态
   * @return 简历准备 Future
   */
  private CompletableFuture<ResumePreparation> prepareResumeAsync(ChatSessionState state) {
    final String tenantId = state == null ? null : state.tenantId;
    final String userId = state == null ? null : state.userId;
    return CompletableFuture.supplyAsync(
        () -> {
          long startedAt = System.nanoTime();
          boolean hadPrevious = AuthenticationScope.isBound();
          String previousTenant = hadPrevious ? AuthenticationScope.tenantId() : null;
          String previousUser = hadPrevious ? AuthenticationScope.userId() : null;
          if (tenantId != null
              && !tenantId.trim().isEmpty()
              && userId != null
              && !userId.trim().isEmpty()) {
            AuthenticationScope.set(tenantId, userId);
          }
          try {
            return new ResumePreparation(
                resumeLoader.loadCurrentResume(state), elapsedMillis(startedAt));
          } finally {
            if (hadPrevious) {
              AuthenticationScope.set(previousTenant, previousUser);
            } else {
              AuthenticationScope.clear();
            }
          }
        },
        recommendationPreparationExecutor);
  }

  /**
   * 等待简历预取并保留原始业务异常语义。
   *
   * @param future 简历准备 Future
   * @return 简历准备结果
   */
  private ResumePreparation awaitResumePreparation(CompletableFuture<ResumePreparation> future) {
    try {
      return future.join();
    } catch (CompletionException exception) {
      Throwable cause = exception.getCause() == null ? exception : exception.getCause();
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      throw new RuntimeException("简历预取失败", cause);
    }
  }

  /**
   * 简历预取结果与独立执行耗时。
   */
  private static final class ResumePreparation {
    private final ResumeRecord resume;
    private final long elapsedMs;

    private ResumePreparation(ResumeRecord resume, long elapsedMs) {
      this.resume = resume;
      this.elapsedMs = elapsedMs;
    }
  }

  /**
   * 计算单个推荐阶段的单调时钟耗时。
   *
   * @param startedAtNanos 阶段开始时间
   * @return 耗时毫秒数
   */
  private long elapsedMillis(long startedAtNanos) {
    return Math.max(0L, (System.nanoTime() - startedAtNanos) / 1_000_000L);
  }

  /**
   * 提取适合用户展示的异常摘要。
   *
   * @param exception 异常
   * @param fallback 默认摘要
   * @return 非空异常摘要
   */
  private String conciseMessage(RuntimeException exception, String fallback) {
    if (exception == null
        || exception.getMessage() == null
        || exception.getMessage().trim().isEmpty()) return fallback;
    return exception.getMessage().trim();
  }

  private String retainedPreviousSearchFailureMessage(boolean retainPreviousBatchOnFailure) {
    if (retainPreviousBatchOnFailure) {
      return "换一批未能完成，上一批通过画像与简历预筛的岗位消息仍保留，请稍后重试。";
    }
    return "本轮岗位搜索未能完成，上一批通过画像与简历预筛的岗位消息仍保留，请稍后重试。";
  }

  private List<Map<String, Object>> copyJobs(List<Map<String, Object>> jobs) {
    List<Map<String, Object>> copied = new java.util.ArrayList<Map<String, Object>>();
    if (jobs == null) return copied;
    for (Map<String, Object> job : jobs) {
      if (job != null) copied.add(new LinkedHashMap<String, Object>(job));
    }
    return copied;
  }

  /**
   * 获取当前候选项偏移量。
   *
   * @param slots 候选槽位
   * @return 当前候选项偏移量
   */
  private int currentCandidateOffset(Map<String, Object> slots) {
    if (slots != null && slots.containsKey(CANDIDATE_OFFSET_SLOT)) {
      Object value = slots.get(CANDIDATE_OFFSET_SLOT);
      if (value instanceof Number) return Math.max(0, ((Number) value).intValue());
      try {
        return Math.max(0, Integer.parseInt(String.valueOf(value)));
      } catch (NumberFormatException ignored) {
        return 0;
      }
    }
    int limit = Math.max(1, properties.getMaxJobsPerRecommend());
    long batchSize =
        Math.min(
            Math.max(1, properties.getMaxJobsPerScoring()),
            (long) limit * Math.max(1, properties.getRecommendOverfetchFactor()));
    return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, currentBossPage(slots) - 1L) * batchSize);
  }
}
