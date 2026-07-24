package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.security.AuthenticationScope;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.auth.service.BossAuthService;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolArguments;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;
import com.jobbuddy.backend.modules.chat.service.JobRecommendationResult;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService.JobProgressConsumer;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import com.jobbuddy.backend.modules.chat.util.ChatValueSupport;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Service;

@Service
public class JobRuntimeServiceImpl implements JobRuntimeService {
  private static final int RESUME_MATCH_BATCH_SIZE = 15;
  private static final int RESUME_MATCH_RETRY_BATCH_SIZE = 4;
  private static final String RECOMMENDATION_LIST_MODE = "recommendation_list";
  private static final String FULL_JD_ANALYSIS_MODE = "full_jd_analysis";
  private static final String CANDIDATE_OFFSET_SLOT = "candidate_offset";
  private static final String NO_QUALIFIED_BATCH_WARNING = "当前批次没有岗位同时达到匹配分、置信度和投递建议门槛。";

  private final Map<String, CacheEntry> fastSearchCache =
      new ConcurrentHashMap<String, CacheEntry>();
  private final Map<String, Long> bossSearchCooldownUntil = new ConcurrentHashMap<String, Long>();
  private final RuntimeToolClient runtimeToolClient;
  private final JobBuddyProperties properties;
  private final BossAuthService bossAuthService;
  private final JsonCodec jsonCodec;
  private final BossCliService bossCliService;
  private final SystemSettingsService settingsService;
  private final JobCandidateFilter candidateFilter;

  public JobRuntimeServiceImpl(
      RuntimeToolClient runtimeToolClient,
      JobBuddyProperties properties,
      BossAuthService bossAuthService,
      JsonCodec jsonCodec,
      BossCliService bossCliService,
      SystemSettingsService settingsService) {
    this.runtimeToolClient = runtimeToolClient;
    this.properties = properties;
    this.bossAuthService = bossAuthService;
    this.jsonCodec = jsonCodec;
    this.bossCliService = bossCliService;
    this.settingsService = settingsService;
    this.candidateFilter = new JobCandidateFilter(settingsService);
  }

  public Map<String, Object> startBossLogin(String sessionId) {
    Map<String, Object> login = jsonCodec.toMap(bossAuthService.startQrLogin(sessionId));
    // 登录态有效时清除残留冷却，确保登录成功后可立即继续搜索。
    if (login != null && !Boolean.TRUE.equals(login.get("authRequired"))) {
      clearBossSearchCooldown();
    }
    return login;
  }

  public boolean hasUsableBossCredential() {
    // BossBrowserClient 会按当前 AuthenticationScope 从数据库读取并解密凭据，
    // 不再把任意用户的凭据恢复到进程级共享内存。
    boolean authenticated = bossAuthService.isLoggedIn(null);
    if (authenticated) clearBossSearchCooldown();
    return authenticated;
  }

  public List<Map<String, Object>> recommendJobs(IntentResult intent, String sessionId) {
    return recommendJobs(intent, sessionId, null);
  }

  public List<Map<String, Object>> recommendJobs(
      IntentResult intent, String sessionId, final JobProgressConsumer consumer) {
    final Map<String, Object> slots =
        intent.getSlots() == null ? Collections.<String, Object>emptyMap() : intent.getSlots();
    final int limit = Math.max(1, properties.getMaxJobsPerRecommend());
    int target =
        Math.min(
            Math.max(limit, limit * Math.max(1, properties.getRecommendOverfetchFactor())),
            Math.max(limit, properties.getMaxJobsPerScoring()));
    return recommendJobsWithTarget(intent, target, limit, consumer);
  }

  public List<Map<String, Object>> recommendJobsFast(
      IntentResult intent, String sessionId, final JobProgressConsumer consumer) {
    final Map<String, Object> slots =
        intent.getSlots() == null ? Collections.<String, Object>emptyMap() : intent.getSlots();
    final int limit = Math.max(1, properties.getMaxJobsPerRecommend());
    final int candidateBatchSize = recommendationCandidateBatchSize(limit);
    final int page = intSlot(slots.get("boss_page"), 1);
    final int candidateOffset = candidateOffset(slots, page, candidateBatchSize);
    final int needed = candidateOffset + candidateBatchSize;
    final String cacheKey = fastSearchCacheKey(slots, candidateBatchSize);
    assertBossSearchNotCoolingDown();

    CacheEntry cached = fastSearchCache.get(cacheKey);
    if (cached != null && cached.expired(fastSearchCacheTtlMillis())) {
      fastSearchCache.remove(cacheKey, cached);
      cached = null;
    }
    // 首次搜索按候选池倍率预取；换一批从显式消费游标之后继续，只有缓存不足且上游仍有结果时才追抓。
    cached =
        cached == null
            ? buildInitialPool(intent, slots, needed, cacheKey)
            : ensurePoolCoversOffset(intent, slots, needed, cacheKey, cached);

    List<Map<String, Object>> slice = pageSlice(cached.jobs, candidateOffset, candidateBatchSize);
    if (consumer != null && !slice.isEmpty()) consumer.accept(slice, slice, "candidate_pool", page);
    return slice;
  }

  /** 首次搜索：先抓最小页数，再按配置补抓有限页数，使过滤后的候选尽量覆盖质量门评估批次。 */
  private CacheEntry buildInitialPool(
      IntentResult intent, Map<String, Object> slots, int needed, String cacheKey) {
    int firstPaintPages = envInt("BOSS_SEARCH_FIRST_PAINT_PAGES", 1, 1, 3);
    PoolFetch fetch = fetchPoolBatchWithSideEffects(intent, 1, firstPaintPages);
    List<Map<String, Object>> pool = applyFilterPipeline(fetch.rows, slots);
    CacheEntry entry =
        new CacheEntry(pool, collectIds(fetch.rows), fetch.nextPage, fetch.exhausted);
    putFastSearchCache(cacheKey, entry);
    int maxDepth = bounded(properties.getBossSearchMaxPageDepth(), 1, 10);
    int reservePageBudget = bounded(properties.getBossSearchMaxPages(), 1, 5);
    CacheEntry warmed = entry;
    int fetchedPages = 0;
    while (warmed.jobs.size() < needed
        && !warmed.exhausted
        && warmed.nextPage <= maxDepth
        && fetchedPages < reservePageBudget) {
      warmed = appendPoolBatch(intent, slots, cacheKey, warmed, 1);
      fetchedPages++;
    }
    return warmed;
  }

  /** 换一批：候选池不足以覆盖消费游标时，只追抓尚未访问的后续页并追加到池尾。 */
  private CacheEntry ensurePoolCoversOffset(
      IntentResult intent,
      Map<String, Object> slots,
      int needed,
      String cacheKey,
      CacheEntry entry) {
    int maxDepth = bounded(properties.getBossSearchMaxPageDepth(), 1, 10);
    int batchPages = bounded(properties.getBossSearchMaxPages(), 1, 5);
    if (entry.jobs.size() >= needed || entry.exhausted || entry.nextPage > maxDepth) {
      return entry;
    }
    // 单轮换一批最多追抓一批（batchPages 页），避免一次点击连发多次 Boss 请求放大风控暴露。
    int pagesToFetch = Math.max(1, Math.min(batchPages, maxDepth - entry.nextPage + 1));
    return appendPoolBatch(intent, slots, cacheKey, entry, pagesToFetch);
  }

  /** 从候选池下一页起追加一个有界批次；只过滤、追加新增岗位，不改变已展示岗位的分页顺序。 */
  private CacheEntry appendPoolBatch(
      IntentResult intent,
      Map<String, Object> slots,
      String cacheKey,
      CacheEntry entry,
      int pagesToFetch) {
    PoolFetch fetch = fetchPoolBatchWithSideEffects(intent, entry.nextPage, pagesToFetch);
    java.util.Set<String> seenIds = new java.util.LinkedHashSet<String>(entry.seenIds);
    List<Map<String, Object>> freshRaw = new ArrayList<Map<String, Object>>();
    if (fetch.rows != null) {
      for (int i = 0; i < fetch.rows.size(); i++) {
        Map<String, Object> row = fetch.rows.get(i);
        if (row == null) continue;
        String id = jobId(row, i);
        if (seenIds.contains(id)) continue;
        seenIds.add(id);
        freshRaw.add(row);
      }
    }
    // 只对新增批次过滤排序后追加到池尾，不重排整池，避免已展示分页错位。
    List<Map<String, Object>> mergedPool = new ArrayList<Map<String, Object>>(entry.jobs);
    mergedPool.addAll(applyFilterPipeline(freshRaw, slots));
    CacheEntry updated = new CacheEntry(mergedPool, seenIds, fetch.nextPage, fetch.exhausted);
    putFastSearchCache(cacheKey, updated);
    return updated;
  }

  /** 抓取候选页并附带登录态副作用：抓取成功记忆凭证、清除冷却；未登录刷新登录态；风控信号进入冷却。 */
  private PoolFetch fetchPoolBatchWithSideEffects(
      IntentResult intent, int startPage, int pagesToFetch) {
    PoolFetch fetch;
    try {
      fetch = fetchCandidatePages(intent, startPage, pagesToFetch);
    } catch (BossAuthRequiredException e) {
      // 未登录只刷新登录态并提示扫码，不进入风控冷却，避免登录后仍被冷却拦截。
      bossAuthService.markLoginInvalid(jsonCodec.toTree(authFailureSource("boss_job_search_fast")));
      throw e;
    } catch (RuntimeException e) {
      if (looksLikeBossRisk(e)) startBossSearchCooldown("boss_job_search_risk");
      throw e;
    }
    if (fetch.rows != null && !fetch.rows.isEmpty()) {
      // 搜索成功说明登录态和环境正常，清除可能残留的风控冷却。
      clearBossSearchCooldown();
      Map<String, Object> source = new LinkedHashMap<String, Object>();
      source.put("status", "logged_in");
      source.put("ok", true);
      source.put("source", "boss_job_search_success");
      bossAuthService.rememberCurrentCredential(jsonCodec.toTree(source));
    }
    return fetch;
  }

  private List<Map<String, Object>> applyFilterPipeline(
      List<Map<String, Object>> rawJobs, Map<String, Object> slots) {
    return candidateFilter.apply(rawJobs, slots);
  }

  /** 从显式候选游标切片；候选耗尽时返回空列表，禁止回绕旧岗位。 */
  private List<Map<String, Object>> pageSlice(
      List<Map<String, Object>> pool, int offset, int limit) {
    if (pool == null || pool.isEmpty()) return new ArrayList<Map<String, Object>>();
    int from = Math.max(0, offset);
    if (from >= pool.size()) return new ArrayList<Map<String, Object>>();
    int to = Math.min(from + limit, pool.size());
    return copyJobs(pool.subList(from, to));
  }

  private java.util.Set<String> collectIds(List<Map<String, Object>> rows) {
    java.util.Set<String> ids = new java.util.LinkedHashSet<String>();
    if (rows == null) return ids;
    for (int i = 0; i < rows.size(); i++) {
      Map<String, Object> row = rows.get(i);
      if (row != null) ids.add(jobId(row, i));
    }
    return ids;
  }

  private String stringSlot(Object value, String fallback) {
    String text = value == null ? "" : String.valueOf(value).trim();
    return text.isEmpty() ? fallback : text;
  }

  private void assertBossSearchNotCoolingDown() {
    String scopeKey = currentScopeKey();
    Long until = bossSearchCooldownUntil.get(scopeKey);
    if (until == null) return;
    if (until <= System.currentTimeMillis()) {
      bossSearchCooldownUntil.remove(scopeKey, until);
      return;
    }
    long seconds = Math.max(1, (until - System.currentTimeMillis() + 999) / 1000);
    throw new RuntimeException("Boss 搜索触发风控保护，约 " + seconds + " 秒后重试。登录有效时搜索成功会自动解除冷却。");
  }

  private void startBossSearchCooldown(String reason) {
    int minutes = bounded(properties.getBossSearchCooldownMinutesOnRisk(), 1, 24 * 60);
    long until = System.currentTimeMillis() + minutes * 60L * 1000L;
    bossSearchCooldownUntil.put(currentScopeKey(), until);
  }

  private void clearBossSearchCooldown() {
    bossSearchCooldownUntil.remove(currentScopeKey());
  }

  private boolean looksLikeBossRisk(Throwable error) {
    String text = String.valueOf(error == null ? "" : error.getMessage()).toLowerCase();
    return text.contains("风控")
        || text.contains("封禁")
        || text.contains("频繁")
        || text.contains("captcha")
        || text.contains("verify")
        || text.contains("security")
        || text.contains("risk")
        || text.contains("访问异常")
        || text.contains("环境异常");
  }

  private long fastSearchCacheTtlMillis() {
    int minutes = bounded(properties.getBossSearchCacheTtlMinutes(), 1, 24 * 60);
    return minutes * 60L * 1000L;
  }

  private String fastSearchCacheKey(Map<String, Object> slots, int limit) {
    // 缓存键剔除 boss_page，使当前用户同一组检索条件下的所有翻页命中同一候选池；
    // 租户和用户前缀阻止条件相同的其他用户复用候选结果。
    Map<String, Object> keyed = new java.util.TreeMap<String, Object>();
    if (slots != null) keyed.putAll(slots);
    keyed.remove("boss_page");
    keyed.remove(CANDIDATE_OFFSET_SLOT);
    return currentScopeKey() + ":" + limit + ":" + keyed;
  }

  private String currentScopeKey() {
    return AuthenticationScope.tenantId() + ":" + AuthenticationScope.userId();
  }

  private void putFastSearchCache(String cacheKey, CacheEntry entry) {
    long ttlMillis = fastSearchCacheTtlMillis();
    for (Map.Entry<String, CacheEntry> item : fastSearchCache.entrySet()) {
      CacheEntry value = item.getValue();
      if (value == null || value.expired(ttlMillis)) {
        fastSearchCache.remove(item.getKey(), value);
      }
    }
    int maxEntries = envInt("BOSS_SEARCH_CACHE_MAX_ENTRIES", 128, 8, 4096);
    if (!fastSearchCache.containsKey(cacheKey) && fastSearchCache.size() >= maxEntries) {
      String oldestKey = null;
      long oldestCreatedAt = Long.MAX_VALUE;
      for (Map.Entry<String, CacheEntry> item : fastSearchCache.entrySet()) {
        CacheEntry value = item.getValue();
        if (value != null && value.createdAt < oldestCreatedAt) {
          oldestCreatedAt = value.createdAt;
          oldestKey = item.getKey();
        }
      }
      if (oldestKey != null) fastSearchCache.remove(oldestKey);
    }
    fastSearchCache.put(cacheKey, entry);
  }

  private int intSlot(Object value, int fallback) {
    if (value instanceof Number) return Math.max(1, ((Number) value).intValue());
    try {
      return Math.max(1, Integer.parseInt(String.valueOf(value)));
    } catch (Exception e) {
      return fallback;
    }
  }

  private int nonNegativeIntSlot(Object value, int fallback) {
    if (value instanceof Number) return Math.max(0, ((Number) value).intValue());
    try {
      return Math.max(0, Integer.parseInt(String.valueOf(value)));
    } catch (Exception e) {
      return fallback;
    }
  }

  private int candidateOffset(Map<String, Object> slots, int page, int candidateBatchSize) {
    return slots.containsKey(CANDIDATE_OFFSET_SLOT)
        ? nonNegativeIntSlot(slots.get(CANDIDATE_OFFSET_SLOT), 0)
        : Math.max(0, page - 1) * candidateBatchSize;
  }

  private IntentResult withCandidateOffset(IntentResult source, int offset) {
    Map<String, Object> slots =
        source == null || source.getSlots() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(source.getSlots());
    slots.put(CANDIDATE_OFFSET_SLOT, Math.max(0, offset));
    if (source == null) {
      return new IntentResult(
          "job",
          "job.recommend",
          1.0,
          Collections.<String>emptyList(),
          "low",
          false,
          "call_get_recommend_jobs",
          slots);
    }
    IntentResult result =
        new IntentResult(
            source.getDomain(),
            source.getIntent(),
            source.getConfidence(),
            source.getSecondary() == null
                ? Collections.<String>emptyList()
                : new ArrayList<String>(source.getSecondary()),
            source.getRisk(),
            source.isNeedsClarification(),
            source.getNextAction(),
            slots,
            source.getTraceId());
    result.setRouter(source.getRouter());
    return result;
  }

  private void mergeCounters(Map<String, Integer> target, Map<String, Integer> source) {
    for (Map.Entry<String, Integer> entry : source.entrySet()) {
      int value = entry.getValue() == null ? 0 : Math.max(0, entry.getValue().intValue());
      target.put(entry.getKey(), target.getOrDefault(entry.getKey(), Integer.valueOf(0)) + value);
    }
  }

  private int recommendationCandidateBatchSize(int limit) {
    long expanded =
        (long) Math.max(1, limit) * Math.max(1, properties.getRecommendOverfetchFactor());
    return (int)
        Math.min(
            Math.max(1, properties.getMaxJobsPerScoring()),
            Math.max((long) Math.max(1, limit), expanded));
  }

  private List<Map<String, Object>> copyJobs(List<Map<String, Object>> jobs) {
    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    if (jobs == null) return rows;
    for (Map<String, Object> job : jobs) rows.add(new LinkedHashMap<String, Object>(job));
    return rows;
  }

  private static class CacheEntry {
    final long createdAt = System.currentTimeMillis();
    final List<Map<String, Object>> jobs;
    final java.util.Set<String> seenIds;
    final int nextPage;
    final boolean exhausted;

    CacheEntry(
        List<Map<String, Object>> jobs,
        java.util.Set<String> seenIds,
        int nextPage,
        boolean exhausted) {
      this.jobs = jobs;
      this.seenIds = seenIds;
      this.nextPage = nextPage;
      this.exhausted = exhausted;
    }

    boolean expired(long ttlMillis) {
      return System.currentTimeMillis() - createdAt > ttlMillis;
    }
  }

  /** 单次候选池抓取结果：累计去重后的原始岗位、下一次应抓取的 Boss 页码、上游是否已枯竭。 */
  private static class PoolFetch {
    final List<Map<String, Object>> rows;
    final int nextPage;
    final boolean exhausted;

    PoolFetch(List<Map<String, Object>> rows, int nextPage, boolean exhausted) {
      this.rows = rows;
      this.nextPage = nextPage;
      this.exhausted = exhausted;
    }
  }

  /** 抓取从 startPage 起的若干页候选并去重累计，带超时保护；返回累计岗位、下一页码与上游枯竭标记。 */
  private PoolFetch fetchCandidatePages(
      final IntentResult intent, final int startPage, final int pagesToFetch) {
    final int timeoutSeconds = bossCandidatePoolTimeoutSeconds(pagesToFetch);
    final String tenantId = AuthenticationScope.tenantId();
    final String userId = AuthenticationScope.userId();
    java.util.concurrent.ExecutorService executor =
        java.util.concurrent.Executors.newSingleThreadExecutor();
    java.util.concurrent.Future<PoolFetch> future =
        executor.submit(
            new java.util.concurrent.Callable<PoolFetch>() {
              @Override
              public PoolFetch call() {
                AuthenticationScope.set(tenantId, userId);
                try {
                  int targetCandidates =
                      envInt(
                          "BOSS_SEARCH_TARGET_CANDIDATES",
                          properties.getBossSearchTargetCandidates(),
                          1,
                          Math.max(1, properties.getMaxJobsPerScoring()));
                  int delayMillis =
                      envInt(
                          "BOSS_SEARCH_PAGE_DELAY_MILLIS",
                          properties.getBossSearchPageDelayMillis(),
                          0,
                          10000);
                  List<Map<String, Object>> accumulated = new ArrayList<Map<String, Object>>();
                  Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
                  int base = Math.max(1, startPage);
                  int nextPage = base;
                  boolean exhausted = false;
                  for (int offset = 0; offset < Math.max(1, pagesToFetch); offset++) {
                    int page = base + offset;
                    List<Map<String, Object>> rows =
                        page <= 1
                            ? bossCliService.searchJobsFirstPage(intent)
                            : bossCliService.searchJobsPage(intent, page);
                    nextPage = page + 1;
                    mergeUniqueJobs(accumulated, seen, rows);
                    if (rows == null || rows.isEmpty()) {
                      // 上游本页无更多岗位，标记枯竭，避免后续换一批继续追抓空页。
                      exhausted = true;
                      break;
                    }
                    if (offset + 1 < Math.max(1, pagesToFetch)
                        && accumulated.size() < targetCandidates
                        && delayMillis > 0) {
                      try {
                        Thread.sleep(delayMillis);
                      } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                      }
                    }
                  }
                  return new PoolFetch(accumulated, nextPage, exhausted);
                } finally {
                  AuthenticationScope.clear();
                }
              }
            });
    try {
      return future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
    } catch (java.util.concurrent.TimeoutException e) {
      future.cancel(true);
      throw new RuntimeException(
          "Boss 候选池搜索超过 " + timeoutSeconds + " 秒，请稍后重试；若持续超时，请检查 agent-runtime 与 agent-tool 服务状态。",
          e);
    } catch (java.util.concurrent.ExecutionException e) {
      Throwable cause = e.getCause() == null ? e : e.getCause();
      if (cause instanceof BossAuthRequiredException) throw (BossAuthRequiredException) cause;
      if (cause instanceof RuntimeException) throw (RuntimeException) cause;
      throw new RuntimeException(cause);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException("Boss 首屏搜索被中断", e);
    } finally {
      executor.shutdownNow();
    }
  }

  private void mergeUniqueJobs(
      List<Map<String, Object>> target, Map<String, Boolean> seen, List<Map<String, Object>> rows) {
    if (rows == null) return;
    for (int i = 0; i < rows.size(); i++) {
      Map<String, Object> row = rows.get(i);
      if (row == null) continue;
      String id = jobId(row, i);
      if (seen.containsKey(id)) continue;
      seen.put(id, true);
      target.add(row);
    }
  }

  public int bossCandidatePoolTimeoutSeconds() {
    int firstPaintPages = envInt("BOSS_SEARCH_FIRST_PAINT_PAGES", 1, 1, 3);
    return bossCandidatePoolTimeoutSeconds(firstPaintPages);
  }

  private int bossCandidatePoolTimeoutSeconds(int pagesToFetch) {
    int pages = Math.max(1, pagesToFetch);
    int requestTimeoutSeconds = envInt("BOSS_CLI_TIMEOUT_SECONDS", 20, 5, 60);
    int maxRetries = envInt("BOSS_CLI_MAX_RETRIES", 2, 1, 3);
    int delayMillis =
        envInt(
            "BOSS_SEARCH_PAGE_DELAY_MILLIS", properties.getBossSearchPageDelayMillis(), 0, 10000);
    // agent-tool 单页最坏耗时约为“单次请求超时 × 尝试次数”，另加 boss-cli 退避、工具限速、
    // Runtime 转发和序列化余量。外层预算必须严格大于内层预算，避免二者同为 30 秒时 Java 抢先误判超时。
    int perPageBudget = requestTimeoutSeconds * maxRetries + 10;
    int fallback = perPageBudget * pages + (delayMillis * Math.max(0, pages - 1) / 1000);
    return envInt("BOSS_SEARCH_CANDIDATE_POOL_TIMEOUT_SECONDS", fallback, 15, 180);
  }

  private int envInt(String name, int fallback, int min, int max) {
    String value = System.getenv(name);
    int configured = fallback;
    if (value != null && !value.trim().isEmpty()) {
      try {
        configured = Integer.parseInt(value.trim());
      } catch (NumberFormatException ignored) {
        configured = fallback;
      }
    }
    return Math.max(min, Math.min(max, configured));
  }

  private int bounded(int value, int min, int max) {
    return Math.max(min, Math.min(max, value));
  }

  private boolean isDetailEnrichmentEnabled() {
    String value = System.getenv("BOSS_RECOMMEND_ENRICH_DETAILS");
    return value != null
        && ("1".equals(value.trim())
            || "true".equalsIgnoreCase(value.trim())
            || "yes".equalsIgnoreCase(value.trim()));
  }

  private List<Map<String, Object>> recommendJobsWithTarget(
      IntentResult intent, int target, final int limit, final JobProgressConsumer consumer) {
    final Map<String, Object> slots =
        intent.getSlots() == null ? Collections.<String, Object>emptyMap() : intent.getSlots();
    List<Map<String, Object>> jobs;
    try {
      jobs =
          bossCliService.searchJobsBatches(
              intent,
              target,
              new BossCliService.JobBatchConsumer() {
                @Override
                public void accept(
                    List<Map<String, Object>> accumulated,
                    List<Map<String, Object>> latestBatch,
                    String query,
                    int page) {
                  if (consumer == null) return;
                  List<Map<String, Object>> filtered =
                      candidateFilter.clientFilter(accumulated, slots);
                  filtered = settingsService.filterBlacklistedJobs(filtered);
                  filtered = candidateFilter.sortByUserRequirement(filtered, slots);
                  List<Map<String, Object>> preview =
                      filtered.size() > limit
                          ? new ArrayList<Map<String, Object>>(filtered.subList(0, limit))
                          : filtered;
                  consumer.accept(preview, latestBatch, query, page);
                }
              });
    } catch (BossAuthRequiredException e) {
      // 搜索接口认证失败时刷新登录态。
      bossAuthService.markLoginInvalid(jsonCodec.toTree(authFailureSource("boss_job_search_full")));
      throw e;
    }
    if (jobs != null && !jobs.isEmpty()) {
      Map<String, Object> source = new LinkedHashMap<String, Object>();
      source.put("status", "logged_in");
      source.put("ok", true);
      source.put("source", "boss_job_search_success");
      bossAuthService.rememberCurrentCredential(jsonCodec.toTree(source));
    }
    jobs = candidateFilter.clientFilter(jobs, slots);
    jobs = settingsService.filterBlacklistedJobs(jobs);
    jobs = candidateFilter.sortByUserRequirement(jobs, slots);
    jobs =
        jobs.size() > target ? new ArrayList<Map<String, Object>>(jobs.subList(0, target)) : jobs;
    if (isDetailEnrichmentEnabled()) {
      return bossCliService.enrichJobDetails(jobs, Math.min(jobs.size(), Math.max(3, limit)));
    }
    return jobs;
  }

  public JobRecommendationResult prequalifyRecommendations(
      ResumeRecord resume, List<Map<String, Object>> jobs, String sessionId) {
    return prequalifyRecommendations(
        resume, jobs, sessionId, Math.max(1, properties.getMaxJobsPerRecommend()));
  }

  private JobRecommendationResult prequalifyRecommendations(
      ResumeRecord resume, List<Map<String, Object>> jobs, String sessionId, int desired) {
    int candidateCount = jobs == null ? 0 : jobs.size();
    if (resume == null || resume.getParsed() == null || resume.getParsed().isEmpty()) {
      return new JobRecommendationResult(
          Collections.<Map<String, Object>>emptyList(),
          candidateCount,
          Collections.singletonMap("缺少当前简历", candidateCount),
          Collections.singletonList("请先选择并解析当前简历，再获取个性化岗位推荐。"));
    }
    if (jobs == null || jobs.isEmpty()) {
      return new JobRecommendationResult(
          Collections.<Map<String, Object>>emptyList(),
          0,
          Collections.<String, Integer>emptyMap(),
          Collections.<String>emptyList());
    }
    List<String> sections =
        Arrays.asList(
            "score",
            "score_confidence",
            "recommendation",
            "reasoning",
            "evidence",
            "hits",
            "gaps",
            "limitations");
    List<Map<String, Object>> qualified = new ArrayList<Map<String, Object>>();
    Map<String, Integer> rejected = new LinkedHashMap<String, Integer>();
    desired = Math.max(1, desired);
    int scoringLimit = Math.min(candidateCount, Math.max(1, properties.getMaxJobsPerScoring()));
    int evaluated = 0;
    for (int from = 0; from < scoringLimit && qualified.size() < desired; ) {
      int remainingDisplaySlots = desired - qualified.size();
      int batchSize = Math.min(RESUME_MATCH_BATCH_SIZE, remainingDisplaySlots);
      int to = Math.min(scoringLimit, from + batchSize);
      List<Map<String, Object>> batch = new ArrayList<Map<String, Object>>(jobs.subList(from, to));
      Map<String, Object> normalized =
          scoreCompleteRecommendationBatch(resume, batch, sessionId, sections);
      Map<String, Map<String, Object>> matchesById =
          new LinkedHashMap<String, Map<String, Object>>();
      Object matches = normalized.get("matches");
      if (matches instanceof List) {
        for (Object item : (List) matches) {
          if (!(item instanceof Map)) continue;
          Map<String, Object> row = new LinkedHashMap<String, Object>((Map<String, Object>) item);
          matchesById.put(stringValue(row.get("id")), row);
        }
      }
      for (int i = 0; i < batch.size(); i++) {
        Map<String, Object> job = batch.get(i);
        if (job == null) continue;
        String id = jobId(job, i);
        Map<String, Object> match = matchesById.get(id);
        if (match == null) {
          throw new IllegalStateException("岗位匹配结果完整性校验失效，缺少岗位 ID：" + id);
        }
        int score = toScore(match.get("score"));
        if (match.get("score") == null || score < properties.getMinimumRecommendedMatchScore()) {
          increment(rejected, "未达到最低匹配分");
          continue;
        }
        String confidence = stringValue(firstPresent(match, "score_confidence", "confidence"));
        String recommendation = stringValue(match.get("recommendation"));
        if ("low".equalsIgnoreCase(confidence)) {
          increment(rejected, "匹配置信度低");
          continue;
        }
        if (isRejectedRecommendation(recommendation)) {
          increment(rejected, recommendation.isEmpty() ? "投递建议不明确" : "投递建议为" + recommendation);
          continue;
        }
        Map<String, Object> accepted = new LinkedHashMap<String, Object>(job);
        accepted.put("matchScore", score);
        accepted.put("matchConfidence", confidence);
        accepted.put("matchRecommendation", recommendation);
        accepted.put(
            "recommendationReasons", firstTexts(match.get("hits"), match.get("evidence"), 2));
        accepted.put(
            "recommendationWarnings", firstTexts(match.get("gaps"), match.get("limitations"), 2));
        accepted.put(
            "recommendationEvidenceLevel", hasJobDescription(job) ? "full_jd" : "list_metadata");
        qualified.add(accepted);
      }
      evaluated += batch.size();
      from = to;
    }
    qualified.sort(
        (left, right) ->
            Integer.compare(toScore(right.get("matchScore")), toScore(left.get("matchScore"))));
    if (qualified.size() > desired) {
      qualified = new ArrayList<Map<String, Object>>(qualified.subList(0, desired));
    }
    List<String> warnings = new ArrayList<String>();
    if (qualified.isEmpty()) warnings.add(NO_QUALIFIED_BATCH_WARNING);
    return new JobRecommendationResult(qualified, evaluated, rejected, warnings);
  }

  /** 在同一推荐任务内闭环执行“评分不足则继续检索”。每轮只消费实际完成评分的候选，并受总评分上限、 Boss 最大页深、单次补页数和上游枯竭状态共同约束。 */
  public JobRecommendationResult prequalifyRecommendationsWithContinuation(
      ResumeRecord resume,
      IntentResult intent,
      List<Map<String, Object>> initialJobs,
      String sessionId) {
    if (resume == null || resume.getParsed() == null || resume.getParsed().isEmpty()) {
      return prequalifyRecommendations(resume, initialJobs, sessionId);
    }
    int desired = Math.max(1, properties.getMaxJobsPerRecommend());
    int scoringLimit = Math.max(1, properties.getMaxJobsPerScoring());
    int candidateBatchSize = recommendationCandidateBatchSize(desired);
    Map<String, Object> slots =
        intent == null || intent.getSlots() == null
            ? Collections.<String, Object>emptyMap()
            : intent.getSlots();
    int page = intSlot(slots.get("boss_page"), 1);
    int offset = candidateOffset(slots, page, candidateBatchSize);
    int evaluated = 0;
    List<Map<String, Object>> qualified = new ArrayList<Map<String, Object>>();
    Map<String, Integer> rejected = new LinkedHashMap<String, Integer>();
    java.util.Set<String> warnings = new java.util.LinkedHashSet<String>();
    List<Map<String, Object>> candidates = copyJobs(initialJobs);
    int consecutiveEmptyContinuations = 0;
    int emptyContinuationLimit = bounded(properties.getBossSearchMaxPageDepth(), 1, 10);

    while (evaluated < scoringLimit && qualified.size() < desired) {
      if (candidates.isEmpty()) {
        if (consecutiveEmptyContinuations >= emptyContinuationLimit) break;
        candidates = recommendJobsFast(withCandidateOffset(intent, offset), sessionId, null);
        if (candidates.isEmpty()) {
          consecutiveEmptyContinuations++;
          continue;
        }
        consecutiveEmptyContinuations = 0;
      }
      int remainingBudget = scoringLimit - evaluated;
      List<Map<String, Object>> scoringBatch =
          candidates.size() > remainingBudget
              ? new ArrayList<Map<String, Object>>(candidates.subList(0, remainingBudget))
              : candidates;
      JobRecommendationResult batch =
          prequalifyRecommendations(resume, scoringBatch, sessionId, desired - qualified.size());
      int consumed = Math.max(0, batch.getCandidateCount());
      evaluated += consumed;
      offset += consumed;
      qualified.addAll(batch.getJobs());
      mergeCounters(rejected, batch.getRejectionReasons());
      warnings.addAll(batch.getWarnings());

      if (qualified.size() >= desired || evaluated >= scoringLimit || consumed <= 0) break;
      candidates = Collections.<Map<String, Object>>emptyList();
    }

    qualified.sort(
        (left, right) ->
            Integer.compare(toScore(right.get("matchScore")), toScore(left.get("matchScore"))));
    if (qualified.size() > desired) {
      qualified = new ArrayList<Map<String, Object>>(qualified.subList(0, desired));
    }
    if (!qualified.isEmpty()) warnings.remove(NO_QUALIFIED_BATCH_WARNING);
    if (qualified.isEmpty()) warnings.add(NO_QUALIFIED_BATCH_WARNING);
    if (!qualified.isEmpty() && qualified.size() < desired) {
      warnings.add("已继续检索到当前页深或评分预算上限，本轮找到 " + qualified.size() + " 个达到推荐门槛的岗位。");
    }
    return new JobRecommendationResult(
        qualified, evaluated, rejected, new ArrayList<String>(warnings));
  }

  public Map<String, Object> matchResume(
      ResumeRecord resume, List<Map<String, Object>> jobs, String sessionId) {
    return matchResumeSections(resume, jobs, sessionId, Collections.<String>emptyList());
  }

  public Map<String, Object> matchResumeSections(
      ResumeRecord resume,
      List<Map<String, Object>> jobs,
      String sessionId,
      List<String> sections) {
    return normalizeResumeMatchEvidence(
        invokeResumeMatch(resume, jobs, sessionId, sections, FULL_JD_ANALYSIS_MODE), true);
  }

  private Map<String, Object> invokeResumeMatch(
      ResumeRecord resume,
      List<Map<String, Object>> jobs,
      String sessionId,
      List<String> sections) {
    return invokeResumeMatch(resume, jobs, sessionId, sections, FULL_JD_ANALYSIS_MODE);
  }

  private Map<String, Object> invokeResumeMatch(
      ResumeRecord resume,
      List<Map<String, Object>> jobs,
      String sessionId,
      List<String> sections,
      String evaluationMode) {
    if (resume == null || resume.getParsed() == null || resume.getParsed().isEmpty()) {
      throw new IllegalArgumentException("请先上传并解析简历");
    }
    validateMatchEvidence(jobs);
    Map<String, Object> args = new LinkedHashMap<String, Object>();
    args.put("resume", resume.getParsed());
    args.put("jobs", jobs);
    args.put("top_k", Math.min(jobs == null ? 0 : jobs.size(), properties.getMaxJobsPerScoring()));
    if (sections != null && !sections.isEmpty()) args.put("sections", sections);
    args.put(
        "evaluation_mode",
        RECOMMENDATION_LIST_MODE.equals(evaluationMode)
            ? RECOMMENDATION_LIST_MODE
            : FULL_JD_ANALYSIS_MODE);
    RuntimeToolResult runtimeResult =
        runtimeToolClient.invoke(
            "resume_match", RuntimeToolArguments.fromMap(args, jsonCodec), sessionId, null);
    Map<String, Object> result =
        runtimeResult == null
            ? Collections.<String, Object>emptyMap()
            : runtimeResult.toMap(jsonCodec);
    if (!Boolean.TRUE.equals(result.get("success"))) {
      throw new RuntimeException(
          ChatValueSupport.errorMessage(result.get("error"), "Runtime 简历匹配执行失败，请稍后重试。"));
    }
    Object output = result.get("output");
    return output instanceof Map
        ? (Map<String, Object>) output
        : Collections.<String, Object>emptyMap();
  }

  /** 推荐预筛要求 Runtime 对输入岗位逐一返回结果。首轮不完整时按更小批次拆分重试一次；未评分岗位既不能计为低分，也不能推进候选游标。 */
  private Map<String, Object> scoreCompleteRecommendationBatch(
      ResumeRecord resume,
      List<Map<String, Object>> jobs,
      String sessionId,
      List<String> sections) {
    try {
      return requireCompleteRecommendationBatch(
          invokeResumeMatch(resume, jobs, sessionId, sections, RECOMMENDATION_LIST_MODE), jobs);
    } catch (RuntimeException firstFailure) {
      if (!isIncompleteMatchFailure(firstFailure) || jobs.size() <= 1) throw firstFailure;
      List<Map<String, Object>> mergedRows = new ArrayList<Map<String, Object>>();
      for (int from = 0; from < jobs.size(); from += RESUME_MATCH_RETRY_BATCH_SIZE) {
        int to = Math.min(jobs.size(), from + RESUME_MATCH_RETRY_BATCH_SIZE);
        List<Map<String, Object>> retryBatch =
            new ArrayList<Map<String, Object>>(jobs.subList(from, to));
        try {
          Map<String, Object> retry =
              requireCompleteRecommendationBatch(
                  invokeResumeMatch(
                      resume, retryBatch, sessionId, sections, RECOMMENDATION_LIST_MODE),
                  retryBatch);
          Object matches = retry.get("matches");
          if (matches instanceof List) {
            for (Object item : (List) matches) {
              if (item instanceof Map) {
                mergedRows.add(new LinkedHashMap<String, Object>((Map<String, Object>) item));
              }
            }
          }
        } catch (RuntimeException retryFailure) {
          throw new IncompleteMatchBatchException("岗位匹配结果不完整，拆分重试后仍未覆盖全部候选。", retryFailure);
        }
      }
      Map<String, Object> merged = new LinkedHashMap<String, Object>();
      merged.put("matches", mergedRows);
      merged.put("scored_count", mergedRows.size());
      merged.put("total_jobs", jobs.size());
      merged.put("evaluation_mode", RECOMMENDATION_LIST_MODE);
      return requireCompleteRecommendationBatch(merged, jobs);
    }
  }

  private Map<String, Object> requireCompleteRecommendationBatch(
      Map<String, Object> raw, List<Map<String, Object>> jobs) {
    Map<String, Object> normalized = normalizeResumeMatchEvidence(raw, false, false);
    Map<String, Integer> expected = new LinkedHashMap<String, Integer>();
    for (int i = 0; i < jobs.size(); i++) {
      String id = jobId(jobs.get(i), i);
      expected.put(id, Integer.valueOf(expected.getOrDefault(id, Integer.valueOf(0)) + 1));
    }
    if (expected.size() != jobs.size()) {
      throw new IncompleteMatchBatchException("岗位匹配输入包含重复岗位 ID，无法建立一一对应关系。");
    }

    java.util.Set<String> returned = new java.util.LinkedHashSet<String>();
    java.util.Set<String> duplicateIds = new java.util.LinkedHashSet<String>();
    java.util.Set<String> unknownIds = new java.util.LinkedHashSet<String>();
    Object matches = normalized.get("matches");
    if (matches instanceof List) {
      for (Object item : (List) matches) {
        if (!(item instanceof Map)) continue;
        String id = stringValue(((Map<String, Object>) item).get("id")).trim();
        if (!expected.containsKey(id)) unknownIds.add(id.isEmpty() ? "<empty>" : id);
        if (!returned.add(id)) duplicateIds.add(id.isEmpty() ? "<empty>" : id);
      }
    }
    java.util.Set<String> missingIds = new java.util.LinkedHashSet<String>(expected.keySet());
    missingIds.removeAll(returned);
    int reportedCount = intValue(normalized.get("scored_count"), returned.size());
    if (!missingIds.isEmpty()
        || !duplicateIds.isEmpty()
        || !unknownIds.isEmpty()
        || returned.size() != jobs.size()
        || reportedCount != jobs.size()) {
      throw new IncompleteMatchBatchException(
          "岗位匹配结果不完整：requested="
              + jobs.size()
              + ", returned="
              + returned.size()
              + ", reported="
              + reportedCount
              + ", missing="
              + missingIds
              + ", duplicate="
              + duplicateIds
              + ", unknown="
              + unknownIds);
    }
    normalized.put("requested_match_count", jobs.size());
    normalized.put("returned_match_count", returned.size());
    normalized.put("missing_match_count", 0);
    return normalized;
  }

  private boolean isIncompleteMatchFailure(RuntimeException error) {
    if (error instanceof IncompleteMatchBatchException) return true;
    String message = error.getMessage() == null ? "" : error.getMessage();
    return message.contains("岗位匹配结果不完整")
        || message.contains("匹配结果不完整")
        || message.contains("missing");
  }

  private void validateMatchEvidence(List<Map<String, Object>> jobs) {
    if (jobs == null || jobs.isEmpty()) {
      throw new IllegalArgumentException("缺少目标岗位证据，无法评估匹配度");
    }
    for (Map<String, Object> job : jobs) {
      if (job == null) continue;
      String source = stringValue(firstPresent(job, "source", "dataSource")).toLowerCase();
      if (source.contains("fixture") || source.contains("mock") || source.contains("synthetic")) {
        throw new IllegalArgumentException("岗位证据来源无效，无法生成匹配分");
      }
    }
  }

  private Map<String, Object> normalizeResumeMatchEvidence(
      Map<String, Object> match, boolean allowThresholdRelaxation) {
    return normalizeResumeMatchEvidence(match, allowThresholdRelaxation, true);
  }

  private Map<String, Object> normalizeResumeMatchEvidence(
      Map<String, Object> match,
      boolean allowThresholdRelaxation,
      boolean filterByRecommendationThreshold) {
    Map<String, Object> normalized = new LinkedHashMap<String, Object>();
    if (match != null) normalized.putAll(match);
    Object matches = normalized.get("matches");
    if (matches instanceof List) {
      List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
      for (Object item : (List) matches) {
        if (!(item instanceof Map)) continue;
        Map row = (Map) item;
        Map<String, Object> next = new LinkedHashMap<String, Object>(row);
        int evidenceCount = evidenceCount(next);
        boolean containsConfidenceEvidence =
            next.containsKey("score") || next.containsKey("evidence") || next.containsKey("hits");
        if (containsConfidenceEvidence) {
          next.put("evidence_count", evidenceCount);
        }
        if (!next.containsKey("score_confidence") && containsConfidenceEvidence) {
          next.put("score_confidence", evidenceCount >= 2 ? "medium" : "low");
        }
        if (next.containsKey("score") && evidenceCount == 0 && toScore(next.get("score")) >= 70) {
          next.put("score", 60);
          next.put("score_confidence", "low");
          next.put("limitations", appendList(next.get("limitations"), "模型缺少可审计证据，已下调高分展示。"));
        }
        rows.add(next);
      }
      if (filterByRecommendationThreshold) {
        applyRecommendationThreshold(normalized, rows, allowThresholdRelaxation);
      } else {
        normalized.put("matches", rows);
        normalized.put("recommendation_threshold_applied", false);
        normalized.put("recommendation_threshold_relaxed", false);
      }
    }
    normalized.put("evaluation_policy", "evidence_required_no_fixture_no_mock");
    return normalized;
  }

  private void applyRecommendationThreshold(
      Map<String, Object> normalized,
      List<Map<String, Object>> rows,
      boolean allowThresholdRelaxation) {
    int threshold = Math.max(0, Math.min(100, properties.getMinimumRecommendedMatchScore()));
    normalized.put("minimum_recommended_match_score", threshold);
    boolean hasScore = false;
    List<Map<String, Object>> recommended = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> row : rows) {
      Object value = row.get("score");
      if (value == null || String.valueOf(value).trim().isEmpty()) continue;
      hasScore = true;
      if (toScore(value) >= threshold) recommended.add(row);
    }
    if (!hasScore) {
      normalized.put("matches", rows);
      normalized.put("recommended_count", rows.size());
      normalized.put("recommendation_threshold_applied", false);
      normalized.put("recommendation_threshold_relaxed", false);
      return;
    }
    boolean relaxed = false;
    if (allowThresholdRelaxation && recommended.isEmpty() && !rows.isEmpty()) {
      Map<String, Object> best = rows.get(0);
      for (Map<String, Object> row : rows) {
        if (toScore(row.get("score")) > toScore(best.get("score"))) best = row;
      }
      recommended.add(best);
      relaxed = true;
      normalized.put(
          "warnings", appendList(normalized.get("warnings"), "没有岗位达到最低推荐匹配度，已保留匹配度最高的岗位。"));
    }
    normalized.put("matches", recommended);
    normalized.put("recommended_count", recommended.size());
    normalized.put("recommendation_threshold_applied", true);
    normalized.put("recommendation_threshold_relaxed", relaxed);
  }

  private void increment(Map<String, Integer> counters, String key) {
    counters.put(key, Integer.valueOf(counters.getOrDefault(key, Integer.valueOf(0)) + 1));
  }

  private boolean isRejectedRecommendation(String recommendation) {
    String value = recommendation == null ? "" : recommendation.trim();
    return value.isEmpty()
        || value.contains("不建议")
        || value.contains("证据不足")
        || value.contains("谨慎");
  }

  private boolean hasJobDescription(Map<String, Object> job) {
    return !stringValue(
            firstPresent(job, "jobDescription", "description", "postDescription", "jobRequire"))
        .trim()
        .isEmpty();
  }

  private List<String> firstTexts(Object primary, Object fallback, int limit) {
    Object source = primary instanceof List && !((List) primary).isEmpty() ? primary : fallback;
    if (!(source instanceof List)) return Collections.emptyList();
    List<String> result = new ArrayList<String>();
    for (Object item : (List) source) {
      String value;
      if (item instanceof Map) {
        value =
            stringValue(
                firstPresent(
                    (Map<String, Object>) item,
                    "assessment",
                    "resume_evidence",
                    "job_requirement",
                    "summary"));
      } else {
        value = stringValue(item);
      }
      value = value.trim();
      if (!value.isEmpty()) result.add(value);
      if (result.size() >= limit) break;
    }
    return result;
  }

  private int evidenceCount(Map<String, Object> row) {
    int count = 0;
    Object evidence = row.get("evidence");
    if (evidence instanceof List) count += ((List) evidence).size();
    Object hits = row.get("hits");
    if (hits instanceof List) count += ((List) hits).size();
    Object dimensions = row.get("dimensions");
    if (dimensions instanceof Map) count += ((Map) dimensions).size();
    return count;
  }

  private List<Object> appendList(Object value, Object item) {
    List<Object> rows = new ArrayList<Object>();
    if (value instanceof List) rows.addAll((List) value);
    rows.add(item);
    return rows;
  }

  private Object firstPresent(Map<String, Object> map, String... keys) {
    for (String key : keys) {
      Object value = map.get(key);
      if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
    }
    return null;
  }

  private String jobId(Map<String, Object> job, int idx) {
    Object id = firstPresent(job, "securityId", "id", "jobId", "encryptJobId");
    return id == null ? "job_" + idx : String.valueOf(id);
  }

  private int toScore(Object value) {
    if (value instanceof Number) return Math.max(0, Math.min(100, ((Number) value).intValue()));
    try {
      return Math.max(0, Math.min(100, Integer.parseInt(String.valueOf(value))));
    } catch (Exception e) {
      return 0;
    }
  }

  private int intValue(Object value, int fallback) {
    if (value instanceof Number) return ((Number) value).intValue();
    try {
      return value == null ? fallback : Integer.parseInt(String.valueOf(value));
    } catch (Exception e) {
      return fallback;
    }
  }

  private String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private void putIfPresent(Map<String, Object> map, String key, Object value) {
    if (value != null && !String.valueOf(value).isEmpty()) map.put(key, value);
  }

  private Object normalizeBossOutput(Object output) {
    if (!(output instanceof Map)) return output;
    Map map = (Map) output;
    Object result = map.get("result");
    if (result instanceof String) {
      Map<String, Object> parsed = jsonCodec.toMap((String) result);
      if (!parsed.isEmpty()) return parsed;
    }
    Object text = map.get("text");
    if (text instanceof String) {
      Map<String, Object> parsed = jsonCodec.toMap((String) text);
      if (!parsed.isEmpty()) return parsed;
    }
    return output;
  }

  private void ensureBossOutputSuccess(Object output, String sessionId) {
    if (!(output instanceof Map)) return;
    Map map = (Map) output;
    Object error = map.get("error");
    Object status = map.get("status");
    String message = String.valueOf(map.get("message"));
    if ("未登录".equals(String.valueOf(error)) || message.contains("请先完成登录")) {
      bossAuthService.markLoginInvalid(jsonCodec.toTree(authFailureSource("runtime_boss_output")));
      throw new BossAuthRequiredException(
          "Boss 直聘未登录，请先完成二维码登录。", jsonCodec.toMap(bossAuthService.loginPrompt()));
    }
    if ("error".equals(String.valueOf(status)) || error != null) {
      throw new RuntimeException(
          message == null || "null".equals(message) ? String.valueOf(error) : message);
    }
  }

  private Map<String, Object> authFailureSource(String source) {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("status", "auth_required");
    data.put("ok", false);
    data.put("source", source);
    return data;
  }

  private List<Map<String, Object>> extractJobs(Object output) {
    if (output instanceof List) return (List<Map<String, Object>>) output;
    if (output instanceof Map) {
      Map map = (Map) output;
      for (String key : Arrays.asList("jobs", "list", "items", "jobList")) {
        Object value = map.get(key);
        if (value instanceof List) return (List<Map<String, Object>>) value;
      }
      Object data = map.get("data");
      if (data instanceof List) return (List<Map<String, Object>>) data;
      if (data instanceof Map) return extractJobs(data);
      Object structured = map.get("structured");
      if (structured instanceof Map) return extractJobs(structured);
    }
    return new ArrayList<Map<String, Object>>();
  }

  private static final class IncompleteMatchBatchException extends RuntimeException {
    private IncompleteMatchBatchException(String message) {
      super(message);
    }

    private IncompleteMatchBatchException(String message, Throwable cause) {
      super(message, cause);
    }
  }
}
