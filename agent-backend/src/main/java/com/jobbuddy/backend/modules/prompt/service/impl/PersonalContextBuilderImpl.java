package com.jobbuddy.backend.modules.prompt.service.impl;

import com.jobbuddy.backend.common.security.AuthenticationScope;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.job.service.JobFavoriteService;
import com.jobbuddy.backend.modules.journey.service.JobJourneyService;
import com.jobbuddy.backend.modules.prompt.model.PersonalContext;
import com.jobbuddy.backend.modules.prompt.model.UserProfileContext;
import com.jobbuddy.backend.modules.prompt.service.PersonalContextBuilder;
import com.jobbuddy.backend.modules.prompt.service.ProfileContextService;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import jakarta.annotation.PreDestroy;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.springframework.stereotype.Service;

/**
 * 从 Backend 业务数据构建紧凑且与任务相关的个人上下文。
 *
 * <p>仅按任务需要加载数据源并分别限长，避免完整简历、旅程或记忆集合进入每次 Runtime 请求。
 */
@Service
public class PersonalContextBuilderImpl implements PersonalContextBuilder {
  private static final int CONTEXT_PARALLELISM = 6;
  private static final AtomicInteger CONTEXT_THREAD_SEQUENCE = new AtomicInteger();

  private final ProfileContextService profileContextService;
  private final ResumeStorageService resumeStorageService;
  private final JobFavoriteService favoriteService;
  private final JobJourneyService journeyService;
  private final SystemSettingsService settingsService;
  private final ExecutorService contextExecutor;
  private final JsonCodec jsonCodec = new JsonCodec();

  /**
   * 创建个人上下文构建器实例。
   *
   * @param profileContextService 画像上下文服务
   * @param resumeStorageService 简历存储服务
   * @param favoriteService 收藏岗位服务
   * @param journeyService 求职旅程服务
   * @param settingsService 设置服务
   */
  public PersonalContextBuilderImpl(
      ProfileContextService profileContextService,
      ResumeStorageService resumeStorageService,
      JobFavoriteService favoriteService,
      JobJourneyService journeyService,
      SystemSettingsService settingsService) {
    this.profileContextService = profileContextService;
    this.resumeStorageService = resumeStorageService;
    this.favoriteService = favoriteService;
    this.journeyService = journeyService;
    this.settingsService = settingsService;
    this.contextExecutor =
        new ThreadPoolExecutor(
            CONTEXT_PARALLELISM,
            CONTEXT_PARALLELISM,
            30L,
            TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(64),
            runnable -> {
              Thread thread =
                  new Thread(
                      runnable, "personal-context-" + CONTEXT_THREAD_SEQUENCE.incrementAndGet());
              thread.setDaemon(true);
              return thread;
            },
            new ThreadPoolExecutor.CallerRunsPolicy());
  }

  /**
   * 构建个人上下文。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param message 消息内容
   * @param intent 意图
   * @param state 状态
   * @return 构建结果
   */
  public PersonalContext build(
      String tenantId, String userId, String message, IntentResult intent, ChatSessionState state) {
    if (tenantId == null
        || tenantId.trim().isEmpty()
        || userId == null
        || userId.trim().isEmpty()) {
      throw new IllegalArgumentException("个人上下文构建必须提供 tenantId 和 userId");
    }
    String effectiveTenant = tenantId.trim();
    String effectiveUser = userId.trim();
    String taskType = intent == null ? "general" : intent.getIntent();
    boolean contextHelpful = needsPersonalContext(message, intent);
    String resumeId = state == null ? null : state.resumeId;
    CompletableFuture<UserProfileContext> profileFuture =
        contextHelpful
            ? loadAsync(effectiveTenant, effectiveUser, () -> safeProfile(effectiveUser, resumeId))
            : CompletableFuture.completedFuture(
                new UserProfileContext(Collections.<String, Object>emptyMap(), ""));
    CompletableFuture<Map<String, Object>> resumeFuture =
        contextHelpful && resumeId != null && !resumeId.trim().isEmpty()
            ? loadAsync(
                effectiveTenant, effectiveUser, () -> compactResume(effectiveUser, resumeId, true))
            : CompletableFuture.completedFuture(Collections.<String, Object>emptyMap());
    CompletableFuture<List<Map<String, Object>>> favoritesFuture =
        shouldLoadFavorites(intent)
            ? loadAsync(effectiveTenant, effectiveUser, () -> safeFavorites(effectiveUser))
            : CompletableFuture.completedFuture(Collections.<Map<String, Object>>emptyList());
    CompletableFuture<List<Map<String, Object>>> journeyFuture =
        shouldLoadJourney(intent)
            ? loadAsync(effectiveTenant, effectiveUser, () -> safeJourney(effectiveUser))
            : CompletableFuture.completedFuture(Collections.<Map<String, Object>>emptyList());
    CompletableFuture<List<Map<String, Object>>> blacklistFuture =
        shouldLoadBlacklist(intent)
            ? loadAsync(effectiveTenant, effectiveUser, () -> limit(safeBlacklist(), 12))
            : CompletableFuture.completedFuture(Collections.<Map<String, Object>>emptyList());
    CompletableFuture<List<Map<String, Object>>> memoryFuture =
        contextHelpful
            ? loadAsync(
                effectiveTenant,
                effectiveUser,
                () -> safeLongTermMemory(effectiveTenant, effectiveUser, message))
            : CompletableFuture.completedFuture(Collections.<Map<String, Object>>emptyList());
    List<Map<String, Object>> currentJobs =
        limit(
            state == null || state.jobs == null
                ? Collections.<Map<String, Object>>emptyList()
                : state.jobs,
            8);
    UserProfileContext profileContext = profileFuture.join();
    Map<String, Object> resume = resumeFuture.join();
    List<Map<String, Object>> favorites = favoritesFuture.join();
    List<Map<String, Object>> journey = journeyFuture.join();
    List<Map<String, Object>> blacklist = blacklistFuture.join();
    List<Map<String, Object>> longTermMemory = memoryFuture.join();
    String summary =
        summarize(
            taskType,
            profileContext.getSummary(),
            resume,
            currentJobs,
            favorites,
            journey,
            blacklist,
            longTermMemory);
    return new PersonalContext(
        taskType,
        jsonCodec.toMap(profileContext.getProfile()),
        resume,
        currentJobs,
        favorites,
        journey,
        blacklist,
        longTermMemory,
        summary);
  }

  /**
   * 关闭个人上下文读取线程池。
   */
  @PreDestroy
  public void shutdown() {
    contextExecutor.shutdownNow();
  }

  /**
   * 在有界线程池中加载独立上下文数据源，并把请求身份传播到工作线程。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param loader 数据加载函数
   * @param <T> 结果类型
   * @return 异步结果
   */
  private <T> CompletableFuture<T> loadAsync(String tenantId, String userId, Supplier<T> loader) {
    return CompletableFuture.supplyAsync(
        () -> withAuthentication(tenantId, userId, loader), contextExecutor);
  }

  /**
   * 临时绑定调用者身份，并在任务结束后恢复工作线程原有状态。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param loader 数据加载函数
   * @param <T> 结果类型
   * @return 加载结果
   */
  private <T> T withAuthentication(String tenantId, String userId, Supplier<T> loader) {
    boolean hadPrevious = AuthenticationScope.isBound();
    String previousTenant = hadPrevious ? AuthenticationScope.tenantId() : null;
    String previousUser = hadPrevious ? AuthenticationScope.userId() : null;
    AuthenticationScope.set(tenantId, userId);
    try {
      return loader.get();
    } finally {
      if (hadPrevious) {
        AuthenticationScope.set(previousTenant, previousUser);
      } else {
        AuthenticationScope.clear();
      }
    }
  }

  /**
   * 判断是否需要个人上下文。
   *
   * @param message 消息内容
   * @param intent 意图
   * @return 是否需要个人上下文
   */
  private boolean needsPersonalContext(String message, IntentResult intent) {
    String name = intent == null ? "" : intent.getIntent();
    String domain = intent == null ? "" : intent.getDomain();
    if ("job".equals(domain)) return true;
    String text = message == null ? "" : message.toLowerCase();
    return containsAny(text, "我", "我的", "简历", "画像", "项目", "面试", "岗位", "投递", "求职", "这些", "当前")
        || "runtime".equals(domain)
        || "complex_engineering_qa".equals(name);
  }

  /**
   * 判断是否加载收藏岗位。
   *
   * @param intent 意图
   * @return 是否加载收藏岗位
   */
  private boolean shouldLoadFavorites(IntentResult intent) {
    String name = intent == null ? "" : intent.getIntent();
    return "job.favorite.plan".equals(name)
        || "job.compare".equals(name)
        || "interview.prepare".equals(name)
        || "application.material".equals(name);
  }

  /**
   * 判断是否加载求职旅程。
   *
   * @param intent 意图
   * @return 是否加载求职旅程
   */
  private boolean shouldLoadJourney(IntentResult intent) {
    String name = intent == null ? "" : intent.getIntent();
    return "journey.record".equals(name)
        || "interview.prepare".equals(name)
        || "application.material".equals(name);
  }

  /**
   * 判断是否加载黑名单。
   *
   * @param intent 意图
   * @return 是否加载黑名单
   */
  private boolean shouldLoadBlacklist(IntentResult intent) {
    String name = intent == null ? "" : intent.getIntent();
    return "job.recommend".equals(name)
        || "job.compare".equals(name)
        || "job.favorite.plan".equals(name);
  }

  /**
   * 获取安全数据画像。
   *
   * @param userId 用户标识
   * @param resumeId 简历标识
   * @return 安全数据画像
   */
  private UserProfileContext safeProfile(String userId, String resumeId) {
    try {
      return profileContextService.current(userId, resumeId);
    } catch (Exception ignored) {
      return new UserProfileContext(Collections.<String, Object>emptyMap(), "");
    }
  }

  /**
   * 压缩简历上下文。
   *
   * @param userId 用户标识
   * @param resumeId 简历标识
   * @param enabled 是否启用
   * @return 压缩后的简历文本
   */
  private Map<String, Object> compactResume(String userId, String resumeId, boolean enabled) {
    if (!enabled || resumeId == null || resumeId.trim().isEmpty()) return Collections.emptyMap();
    try {
      ResumeRecord record = resumeStorageService.get(resumeId, userId);
      if (record == null || record.getParsed() == null) return Collections.emptyMap();
      Map<String, Object> parsed = record.getParsed();
      Map<String, Object> resume = new LinkedHashMap<String, Object>();
      copy(
          parsed,
          resume,
          "name",
          "targetRole",
          "summary",
          "skills",
          "projects",
          "experiences",
          "education",
          "advantages",
          "risks");
      return resume;
    } catch (Exception ignored) {
      return Collections.emptyMap();
    }
  }

  /**
   * 获取安全数据收藏项。
   *
   * @param userId 用户标识
   * @return 安全数据收藏项
   */
  private List<Map<String, Object>> safeFavorites(String userId) {
    try {
      return limit(toMaps(favoriteService.listFavorites(userId)), 8);
    } catch (Exception ignored) {
      return Collections.emptyList();
    }
  }

  /**
   * 获取安全数据求职旅程。
   *
   * @param userId 用户标识
   * @return 安全数据求职旅程
   */
  private List<Map<String, Object>> safeJourney(String userId) {
    try {
      return limit(toMaps(journeyService.listRecords(userId, null, null, null)), 8);
    } catch (Exception ignored) {
      return Collections.emptyList();
    }
  }

  /**
   * 获取安全数据黑名单。
   *
   * @return 安全数据黑名单
   */
  private List<Map<String, Object>> safeBlacklist() {
    try {
      return toMaps(settingsService.listBlacklistItems());
    } catch (Exception ignored) {
      return Collections.emptyList();
    }
  }

  /**
   * 仅按当前问题召回高信号长期记忆（偏好/约束/目标），命中数量由设置层限制，避免噪声污染上下文。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param message 消息内容
   * @return 安全的长期记忆文本
   */
  private List<Map<String, Object>> safeLongTermMemory(
      String tenantId, String userId, String message) {
    if (message == null || message.trim().isEmpty()) return Collections.emptyList();
    try {
      return toMaps(settingsService.searchLocalMemories(tenantId, userId, message, 2));
    } catch (Exception ignored) {
      return Collections.emptyList();
    }
  }

  /**
   * 转换为映射列表。
   *
   * @param values 输入值列表
   * @return 转换后的键值映射列表
   */
  private List<Map<String, Object>> toMaps(List<?> values) {
    if (values == null || values.isEmpty()) return Collections.emptyList();
    List<Map<String, Object>> result = new java.util.ArrayList<Map<String, Object>>();
    for (Object value : values) result.add(jsonCodec.toMap(value));
    return result;
  }

  /**
   * 生成个人上下文摘要。
   *
   * @param taskType 任务类型
   * @param profileSummary 画像摘要
   * @param resume 简历
   * @param jobs 岗位列表
   * @param favorites 收藏岗位列表
   * @param journey 求职旅程
   * @param blacklist 黑名单
   * @param longTermMemory 长期记忆文本
   * @return 摘要文本
   */
  private String summarize(
      String taskType,
      String profileSummary,
      Map<String, Object> resume,
      List<Map<String, Object>> jobs,
      List<Map<String, Object>> favorites,
      List<Map<String, Object>> journey,
      List<Map<String, Object>> blacklist,
      List<Map<String, Object>> longTermMemory) {
    StringBuilder builder = new StringBuilder();
    builder.append("任务：").append(taskType == null ? "general" : taskType).append("。");
    if (profileSummary != null && !profileSummary.trim().isEmpty())
      builder.append("画像：").append(profileSummary).append("。");
    if (!resume.isEmpty()) builder.append("已读取当前简历摘要。");
    if (!jobs.isEmpty()) builder.append("当前会话岗位 ").append(jobs.size()).append(" 个。");
    if (!favorites.isEmpty()) builder.append("收藏岗位 ").append(favorites.size()).append(" 个。");
    if (!journey.isEmpty()) builder.append("求职进展记录 ").append(journey.size()).append(" 条。");
    if (!blacklist.isEmpty()) builder.append("黑名单/偏好约束 ").append(blacklist.size()).append(" 条。");
    if (!longTermMemory.isEmpty())
      builder.append("命中长期记忆 ").append(longTermMemory.size()).append(" 条。");
    return builder.toString();
  }

  /**
   * 复制上下文字段。
   *
   * @param source 源数据
   * @param target 待写入的上下文
   * @param keys 键列表
   */
  private void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
    for (String key : keys) {
      Object value = source.get(key);
      if (value != null && !String.valueOf(value).trim().isEmpty()) target.put(key, value);
    }
  }

  /**
   * 获取限制。
   *
   * @param rows 查询行列表
   * @param limit 数量上限
   * @return 限制
   */
  private List<Map<String, Object>> limit(List<Map<String, Object>> rows, int limit) {
    if (rows == null || rows.isEmpty()) return Collections.emptyList();
    int end = Math.min(rows.size(), Math.max(0, limit));
    return new java.util.ArrayList<Map<String, Object>>(rows.subList(0, end));
  }

  /**
   * 判断是否包含任一目标值。
   *
   * @param text 文本
   * @param needles 候选关键词
   * @return 是否包含任一目标值
   */
  private boolean containsAny(String text, String... needles) {
    if (text == null) return false;
    for (String needle : needles)
      if (needle != null && text.contains(needle.toLowerCase())) return true;
    return false;
  }
}
