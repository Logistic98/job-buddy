package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService.JobProgressConsumer;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.auth.service.BossAuthService;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class JobRuntimeServiceImpl implements JobRuntimeService {
    private final Map<String, CacheEntry> fastSearchCache = new ConcurrentHashMap<String, CacheEntry>();
    private final Map<String, Long> bossSearchCooldownUntil = new ConcurrentHashMap<String, Long>();
    private final RuntimeToolClient runtimeToolClient;
    private final JobBuddyProperties properties;
    private final BossAuthService bossAuthService;
    private final JsonCodec jsonCodec;
    private final BossCliService bossCliService;
    private final SystemSettingsService settingsService;

    public JobRuntimeServiceImpl(RuntimeToolClient runtimeToolClient, JobBuddyProperties properties, BossAuthService bossAuthService, JsonCodec jsonCodec, BossCliService bossCliService, SystemSettingsService settingsService) {
        this.runtimeToolClient = runtimeToolClient;
        this.properties = properties;
        this.bossAuthService = bossAuthService;
        this.jsonCodec = jsonCodec;
        this.bossCliService = bossCliService;
        this.settingsService = settingsService;
    }

    public Map<String, Object> startBossLogin(String sessionId) {
        Map<String, Object> login = bossAuthService.startQrLogin(sessionId);
        // 登录态有效时清除残留冷却，确保登录成功后可立即继续搜索。
        if (login != null && !Boolean.TRUE.equals(login.get("authRequired"))) {
            clearBossSearchCooldown();
        }
        return login;
    }

    public boolean hasUsableBossCredential() {
        // 本地凭证缺失时先用持久化凭证回填，再校验登录态。
        restoreCredentialFromPersisted();
        boolean authenticated = bossCliService.isAuthenticated();
        if (authenticated) clearBossSearchCooldown();
        return authenticated;
    }

    private void restoreCredentialFromPersisted() {
        if (bossCliService.hasLocalCredential()) return;
        try {
            bossAuthService.restorePersistedLoginState();
        } catch (RuntimeException ignored) {
            // 回填失败交由后续真实登录态校验处理。
        }
    }

    public List<Map<String, Object>> recommendJobs(IntentResult intent, String sessionId) {
        return recommendJobs(intent, sessionId, null);
    }

    public List<Map<String, Object>> recommendJobs(IntentResult intent, String sessionId, final JobProgressConsumer consumer) {
        final Map<String, Object> slots = intent.getSlots() == null ? Collections.<String, Object>emptyMap() : intent.getSlots();
        final int limit = Math.max(1, properties.getMaxJobsPerRecommend());
        int target = Math.min(Math.max(limit, limit * Math.max(1, properties.getRecommendOverfetchFactor())), Math.max(limit, properties.getMaxJobsPerScoring()));
        return recommendJobsWithTarget(intent, target, limit, consumer);
    }

    public List<Map<String, Object>> recommendJobsFast(IntentResult intent, String sessionId, final JobProgressConsumer consumer) {
        final Map<String, Object> slots = intent.getSlots() == null ? Collections.<String, Object>emptyMap() : intent.getSlots();
        final int limit = Math.max(1, properties.getMaxJobsPerRecommend());
        final int page = intSlot(slots.get("boss_page"), 1);
        final String cacheKey = fastSearchCacheKey(slots, limit);
        assertBossSearchNotCoolingDown();

        CacheEntry cached = fastSearchCache.get(cacheKey);
        if (cached != null && cached.expired(fastSearchCacheTtlMillis())) cached = null;
        // 首次搜索构建跨页候选池；换一批命中缓存后按需追抓扩充，二者都保证候选池覆盖当前页。
        // 命中池内即为零 Boss 请求的即时刷新，只有池被翻完且上游仍有更多岗位时才追抓。
        cached = cached == null
                ? buildInitialPool(intent, slots, limit, page, cacheKey)
                : ensurePoolCoversPage(intent, slots, limit, page, cacheKey, cached);

        List<Map<String, Object>> slice = pageSlice(cached.jobs, page, limit);
        if (consumer != null && !slice.isEmpty()) consumer.accept(slice, slice, "candidate_pool", page);
        return slice;
    }

    /** 首次搜索：首屏只抓 1 页并过滤排序后入缓存即返回，单次 Boss 请求即出结果；更深候选池交给换一批按需懒扩。 */
    private CacheEntry buildInitialPool(IntentResult intent, Map<String, Object> slots, int limit, int page, String cacheKey) {
        // 首屏只抓 1 页、页间零等待：单次 Boss 请求即可返回结果，显著降低首屏等待，同时把请求量降到最低、减少风控暴露。
        // 旧实现首屏先抓 BOSS_SEARCH_MAX_PAGES 页，再因严格筛选不足 limit 而同步追抓第二批（最多 4 页 + 两段 5s 等待，
        // 叠加候选池双倍超时），首屏空白可达上百秒。现在不再为了凑满 limit 而在首屏同步多页阻塞。
        int firstPaintPages = envInt("BOSS_SEARCH_FIRST_PAINT_PAGES", 1, 1, 3);
        PoolFetch fetch = fetchPoolBatchWithSideEffects(intent, 1, firstPaintPages);
        List<Map<String, Object>> pool = applyFilterPipeline(fetch.rows, slots);
        CacheEntry entry = new CacheEntry(pool, collectIds(fetch.rows), fetch.nextPage, fetch.exhausted);
        fastSearchCache.put(cacheKey, entry);
        if (page <= 1) {
            int maxDepth = envInt("BOSS_SEARCH_MAX_PAGE_DEPTH", properties.getBossSearchMaxPageDepth(), 1, 10);
            // 仅当首屏第 1 页过滤后为空、且上游仍有更多岗位时才兜底追抓一批，避免“0 个岗位”的空首屏；
            // 非空首屏直接返回当前批，把深度留给换一批按需懒扩，绝不为了凑满 limit 而同步多页阻塞首屏。
            if (pool.isEmpty() && !fetch.exhausted && fetch.nextPage <= maxDepth) {
                return ensurePoolCoversPage(intent, slots, limit, page, cacheKey, entry);
            }
            return entry;
        }
        return ensurePoolCoversPage(intent, slots, limit, page, cacheKey, entry);
    }

    /** 换一批：候选池不足以覆盖当前页且上游未枯竭、未超深度时，追抓一批新页并就地去重过滤后追加到池尾。 */
    private CacheEntry ensurePoolCoversPage(IntentResult intent, Map<String, Object> slots, int limit, int page, String cacheKey, CacheEntry entry) {
        int maxDepth = envInt("BOSS_SEARCH_MAX_PAGE_DEPTH", properties.getBossSearchMaxPageDepth(), 1, 10);
        int batchPages = envInt("BOSS_SEARCH_MAX_PAGES", properties.getBossSearchMaxPages(), 1, 5);
        int needed = Math.max(1, page) * limit;
        if (entry.jobs.size() >= needed || entry.exhausted || entry.nextPage > maxDepth) {
            return entry;
        }
        // 单轮换一批最多追抓一批（batchPages 页），避免一次点击连发多次 Boss 请求放大风控暴露。
        int pagesToFetch = Math.max(1, Math.min(batchPages, maxDepth - entry.nextPage + 1));
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
        fastSearchCache.put(cacheKey, updated);
        return updated;
    }

    /** 抓取候选页并附带登录态副作用：抓取成功记忆凭证、清除冷却；未登录刷新登录态；风控信号进入冷却。 */
    private PoolFetch fetchPoolBatchWithSideEffects(IntentResult intent, int startPage, int pagesToFetch) {
        restoreCredentialFromPersisted();
        PoolFetch fetch;
        try {
            fetch = fetchCandidatePages(intent, startPage, pagesToFetch);
        } catch (BossAuthRequiredException e) {
            // 未登录只刷新登录态并提示扫码，不进入风控冷却，避免登录后仍被冷却拦截。
            bossAuthService.markLoginInvalid(authFailureSource("boss_job_search_fast"));
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
            bossAuthService.rememberCurrentCredential(source);
        }
        return fetch;
    }

    private List<Map<String, Object>> applyFilterPipeline(List<Map<String, Object>> rawJobs, Map<String, Object> slots) {
        List<Map<String, Object>> jobs = rawJobs == null ? new ArrayList<Map<String, Object>>() : rawJobs;
        jobs = clientFilter(jobs, slots);
        jobs = settingsService.filterBlacklistedJobs(jobs);
        jobs = filterBySalary(jobs, slots);
        jobs = sortByUserRequirement(jobs, slots);
        return jobs;
    }

    /** 按候选池分页切片返回当前页；超出池范围时在已有池内循环切片，保证换一批始终有内容且不报错。 */
    private List<Map<String, Object>> pageSlice(List<Map<String, Object>> pool, int page, int limit) {
        if (pool == null || pool.isEmpty()) return new ArrayList<Map<String, Object>>();
        int totalPages = Math.max(1, (pool.size() + limit - 1) / limit);
        int effectivePage = (Math.max(1, page) - 1) % totalPages;
        int from = effectivePage * limit;
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
        Long until = bossSearchCooldownUntil.get("global");
        if (until == null || until <= System.currentTimeMillis()) return;
        long seconds = Math.max(1, (until - System.currentTimeMillis() + 999) / 1000);
        throw new RuntimeException("Boss 搜索触发风控保护，约 " + seconds + " 秒后重试。登录有效时搜索成功会自动解除冷却。");
    }

    private void startBossSearchCooldown(String reason) {
        int minutes = envInt("BOSS_SEARCH_COOLDOWN_MINUTES_ON_RISK", properties.getBossSearchCooldownMinutesOnRisk(), 1, 24 * 60);
        long until = System.currentTimeMillis() + minutes * 60L * 1000L;
        bossSearchCooldownUntil.put("global", until);
    }

    private void clearBossSearchCooldown() {
        bossSearchCooldownUntil.remove("global");
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
        int minutes = envInt("BOSS_SEARCH_CACHE_TTL_MINUTES", properties.getBossSearchCacheTtlMinutes(), 1, 24 * 60);
        return minutes * 60L * 1000L;
    }

    private String fastSearchCacheKey(Map<String, Object> slots, int limit) {
        // 缓存键剔除 boss_page，使同一组检索条件下的所有翻页（换一批）命中同一份候选池缓存。
        if (slots == null || slots.isEmpty()) return limit + ":{}";
        Map<String, Object> keyed = new java.util.TreeMap<String, Object>(slots);
        keyed.remove("boss_page");
        return limit + ":" + keyed;
    }

    private int intSlot(Object value, int fallback) {
        if (value instanceof Number) return Math.max(1, ((Number) value).intValue());
        try { return Math.max(1, Integer.parseInt(String.valueOf(value))); } catch (Exception e) { return fallback; }
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
        CacheEntry(List<Map<String, Object>> jobs, java.util.Set<String> seenIds, int nextPage, boolean exhausted) {
            this.jobs = jobs;
            this.seenIds = seenIds;
            this.nextPage = nextPage;
            this.exhausted = exhausted;
        }
        boolean expired(long ttlMillis) { return System.currentTimeMillis() - createdAt > ttlMillis; }
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
    private PoolFetch fetchCandidatePages(final IntentResult intent, final int startPage, final int pagesToFetch) {
        final int timeoutSeconds = bossCandidatePoolTimeoutSeconds();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<PoolFetch> future = executor.submit(new java.util.concurrent.Callable<PoolFetch>() {
            @Override
            public PoolFetch call() {
                int targetCandidates = envInt("BOSS_SEARCH_TARGET_CANDIDATES", properties.getBossSearchTargetCandidates(), 1, Math.max(1, properties.getMaxJobsPerScoring()));
                int delayMillis = envInt("BOSS_SEARCH_PAGE_DELAY_MILLIS", properties.getBossSearchPageDelayMillis(), 0, 10000);
                List<Map<String, Object>> accumulated = new ArrayList<Map<String, Object>>();
                Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
                int base = Math.max(1, startPage);
                int nextPage = base;
                boolean exhausted = false;
                for (int offset = 0; offset < Math.max(1, pagesToFetch); offset++) {
                    int page = base + offset;
                    List<Map<String, Object>> rows = page <= 1
                            ? bossCliService.searchJobsFirstPage(intent, new BossCliService.JobBatchConsumer() {
                                @Override
                                public void accept(List<Map<String, Object>> accumulated, List<Map<String, Object>> latestBatch, String query, int page) {
                                    // 保留回调用于兼容 boss-cli 首屏搜索。
                                }
                            })
                            : bossCliService.searchJobsPage(intent, page);
                    nextPage = page + 1;
                    mergeUniqueJobs(accumulated, seen, rows);
                    if (rows == null || rows.isEmpty()) {
                        // 上游本页无更多岗位，标记枯竭，避免后续换一批继续追抓空页。
                        exhausted = true;
                        break;
                    }
                    if (offset + 1 < Math.max(1, pagesToFetch) && accumulated.size() < targetCandidates && delayMillis > 0) {
                        try { Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                }
                return new PoolFetch(accumulated, nextPage, exhausted);
            }
        });
        try {
            return future.get(timeoutSeconds, java.util.concurrent.TimeUnit.SECONDS);
        } catch (java.util.concurrent.TimeoutException e) {
            future.cancel(true);
            throw new RuntimeException("Boss 候选池搜索超时（" + timeoutSeconds + " 秒），请稍后重试或放宽筛选。", e);
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

    private void mergeUniqueJobs(List<Map<String, Object>> target, Map<String, Boolean> seen, List<Map<String, Object>> rows) {
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
        int maxPages = envInt("BOSS_SEARCH_MAX_PAGES", properties.getBossSearchMaxPages(), 1, 5);
        int delayMillis = envInt("BOSS_SEARCH_PAGE_DELAY_MILLIS", properties.getBossSearchPageDelayMillis(), 0, 10000);
        // boss-cli 单次 HTTP 请求超时默认 30 秒，且首次请求可能包含登录态校验、限速等待和上游抖动。
        // 13 秒会在正常慢请求下误报候选池超时，因此默认至少给 30 秒。
        int fallback = Math.max(30, 10 * maxPages + (delayMillis * Math.max(0, maxPages - 1) / 1000) + 3);
        return envInt("BOSS_SEARCH_CANDIDATE_POOL_TIMEOUT_SECONDS", fallback, 15, 180);
    }

    private int envInt(String name, int fallback, int min, int max) {
        String value = System.getenv(name);
        int configured = fallback;
        if (value != null && !value.trim().isEmpty()) {
            try { configured = Integer.parseInt(value.trim()); } catch (NumberFormatException ignored) { configured = fallback; }
        }
        return Math.max(min, Math.min(max, configured));
    }

    private boolean isDetailEnrichmentEnabled() {
        String value = System.getenv("BOSS_RECOMMEND_ENRICH_DETAILS");
        return value != null && ("1".equals(value.trim()) || "true".equalsIgnoreCase(value.trim()) || "yes".equalsIgnoreCase(value.trim()));
    }

    private List<Map<String, Object>> recommendJobsWithTarget(IntentResult intent, int target, final int limit, final JobProgressConsumer consumer) {
        final Map<String, Object> slots = intent.getSlots() == null ? Collections.<String, Object>emptyMap() : intent.getSlots();
        // 本地凭证缺失时先回填持久化凭证。
        restoreCredentialFromPersisted();
        List<Map<String, Object>> jobs;
        try {
            jobs = bossCliService.searchJobsBatches(intent, target, new BossCliService.JobBatchConsumer() {
                @Override
                public void accept(List<Map<String, Object>> accumulated, List<Map<String, Object>> latestBatch, String query, int page) {
                    if (consumer == null) return;
                    List<Map<String, Object>> filtered = clientFilter(accumulated, slots);
                    filtered = settingsService.filterBlacklistedJobs(filtered);
                    filtered = sortByUserRequirement(filtered, slots);
                    List<Map<String, Object>> preview = filtered.size() > limit ? new ArrayList<Map<String, Object>>(filtered.subList(0, limit)) : filtered;
                    consumer.accept(preview, latestBatch, query, page);
                }
            });
        } catch (BossAuthRequiredException e) {
            // 搜索接口认证失败时刷新登录态。
            bossAuthService.markLoginInvalid(authFailureSource("boss_job_search_full"));
            throw e;
        }
        if (jobs != null && !jobs.isEmpty()) {
            Map<String, Object> source = new LinkedHashMap<String, Object>();
            source.put("status", "logged_in");
            source.put("ok", true);
            source.put("source", "boss_job_search_success");
            bossAuthService.rememberCurrentCredential(source);
        }
        jobs = clientFilter(jobs, slots);
        jobs = settingsService.filterBlacklistedJobs(jobs);
        jobs = sortByUserRequirement(jobs, slots);
        jobs = jobs.size() > target ? new ArrayList<Map<String, Object>>(jobs.subList(0, target)) : jobs;
        if (isDetailEnrichmentEnabled()) {
            return bossCliService.enrichJobDetails(jobs, Math.min(jobs.size(), Math.max(3, limit)));
        }
        return jobs;
    }

    public Map<String, Object> matchResume(ResumeRecord resume, List<Map<String, Object>> jobs, String sessionId) {
        if (resume == null || resume.getParsed() == null || resume.getParsed().isEmpty()) {
            throw new IllegalArgumentException("请先上传并解析简历");
        }
        validateMatchEvidence(jobs);
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("resume", resume.getParsed());
        args.put("jobs", jobs);
        args.put("top_k", Math.min(jobs == null ? 0 : jobs.size(), properties.getMaxJobsPerScoring()));
        Map<String, Object> result = runtimeToolClient.invoke("resume_match", args, sessionId, null);
        if (!Boolean.TRUE.equals(result.get("success"))) {
            throw new RuntimeException(String.valueOf(result.get("error")));
        }
        Object output = result.get("output");
        Map<String, Object> match = output instanceof Map ? (Map<String, Object>) output : Collections.<String, Object>emptyMap();
        return normalizeResumeMatchEvidence(match);
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

    private Map<String, Object> normalizeResumeMatchEvidence(Map<String, Object> match) {
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
                next.put("evidence_count", evidenceCount);
                if (!next.containsKey("score_confidence")) {
                    next.put("score_confidence", evidenceCount >= 2 ? "medium" : "low");
                }
                if (evidenceCount == 0 && toScore(next.get("score")) >= 70) {
                    next.put("score", 60);
                    next.put("score_confidence", "low");
                    next.put("limitations", appendList(next.get("limitations"), "模型缺少可审计证据，已下调高分展示。"));
                }
                rows.add(next);
            }
            normalized.put("matches", rows);
        }
        normalized.put("evaluation_policy", "evidence_required_no_fixture_no_mock");
        return normalized;
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
        try { return Math.max(0, Math.min(100, Integer.parseInt(String.valueOf(value)))); } catch (Exception e) { return 0; }
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
            bossAuthService.markLoginInvalid(authFailureSource("runtime_boss_output"));
            throw new BossAuthRequiredException("Boss 直聘未登录，请先完成二维码登录。", bossAuthService.loginPrompt());
        }
        if ("error".equals(String.valueOf(status)) || error != null) {
            throw new RuntimeException(message == null || "null".equals(message) ? String.valueOf(error) : message);
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

    private List<Map<String, Object>> clientFilter(List<Map<String, Object>> jobs, Map<String, Object> slots) {
        Object excludes = slots.get("reject_keywords");
        if (!(excludes instanceof List)) excludes = slots.get("hard_excludes");
        if (!(excludes instanceof List)) excludes = slots.get("exclude_keywords");
        if (!(excludes instanceof List) || jobs.isEmpty()) return jobs;
        List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> job : jobs) {
            String text = String.valueOf(job).toLowerCase();
            boolean hit = false;
            for (Object exclude : (List) excludes) {
                if (exclude != null && text.contains(String.valueOf(exclude).toLowerCase())) {
                    hit = true;
                    break;
                }
            }
            if (!hit) filtered.add(job);
        }
        return filtered;
    }

    private List<Map<String, Object>> sortByUserRequirement(List<Map<String, Object>> jobs, final Map<String, Object> slots) {
        if (jobs == null || jobs.isEmpty()) return jobs == null ? new ArrayList<Map<String, Object>>() : jobs;
        List<Map<String, Object>> sorted = new ArrayList<Map<String, Object>>(jobs);
        sorted.sort(new java.util.Comparator<Map<String, Object>>() {
            @Override
            public int compare(Map<String, Object> a, Map<String, Object> b) {
                return Integer.compare(requirementScore(b, slots), requirementScore(a, slots));
            }
        });
        return sorted;
    }

    private int requirementScore(Map<String, Object> job, Map<String, Object> slots) {
        String text = String.valueOf(job).toLowerCase();
        String title = stringValue(firstPresent(job, "jobName", "title", "name")).toLowerCase();
        int score = 0;
        Object role = slots.get("role");
        if (role != null) {
            for (String token : splitTokens(String.valueOf(role))) {
                if (title.contains(token)) score += 12;
                else if (text.contains(token)) score += 5;
            }
        }
        Object includes = slots.get("include_keywords");
        if (includes instanceof List) {
            for (Object item : (List) includes) {
                if (item == null) continue;
                String keyword = String.valueOf(item).toLowerCase();
                if (keyword.isEmpty()) continue;
                if (title.contains(keyword)) score += 18;
                else if (text.contains(keyword)) score += 8;
            }
        }
        Object negatives = slots.get("negative_keywords");
        if (!(negatives instanceof List)) negatives = slots.get("soft_excludes");
        if (negatives instanceof List) {
            for (Object item : (List) negatives) {
                if (item == null) continue;
                String keyword = String.valueOf(item).toLowerCase();
                if (!keyword.isEmpty() && text.contains(keyword)) score -= 30;
            }
        }
        Integer minSalary = toInteger(slots.get("salary_min_k"));
        Integer maxSalary = toInteger(slots.get("salary_max_k"));
        if (salaryOverlap(job, minSalary, maxSalary)) score += 15;
        return score;
    }

    private List<String> splitTokens(String value) {
        List<String> tokens = new ArrayList<String>();
        String lower = value == null ? "" : value.toLowerCase().trim();
        if (lower.isEmpty()) return tokens;
        for (String token : lower.split("[\\s,，;；/|]+")) {
            if (token != null && !token.trim().isEmpty()) tokens.add(token.trim());
        }
        if (tokens.isEmpty()) tokens.add(lower);
        return tokens;
    }

    private boolean salaryOverlap(Map<String, Object> job, Integer minSalary, Integer maxSalary) {
        if (minSalary == null && maxSalary == null) return false;
        String salary = stringValue(firstPresent(job, "salaryDesc", "salary_desc", "salary", "salaryText", "salaryName", "salaryRange", "jobSalary", "pay", "wage", "compensation"));
        int[] range = parseMonthlyRangeK(salary);
        if (range == null) return false;
        int expectedMin = minSalary == null ? 0 : minSalary.intValue();
        int expectedMax = maxSalary == null ? 999 : maxSalary.intValue();
        return range[1] >= expectedMin && range[0] <= expectedMax;
    }

    /**
     * 薪资硬过滤：仅在用户显式给出 salary_min_k 或 salary_max_k 时启用。丢弃可解析为月薪区间但与期望
     * 区间完全不重叠的岗位、以"元/天/日/时"计价的日结时薪岗与标题或薪资命中"实习"的实习岗；对"面议"
     * 或无法解析薪资的岗位保留，交由排序按其它信号决定位置，避免把信息缺失误判为不符合条件。
     */
    private List<Map<String, Object>> filterBySalary(List<Map<String, Object>> jobs, Map<String, Object> slots) {
        if (jobs == null || jobs.isEmpty()) return jobs == null ? new ArrayList<Map<String, Object>>() : jobs;
        Integer minSalary = toInteger(slots.get("salary_min_k"));
        Integer maxSalary = toInteger(slots.get("salary_max_k"));
        if (minSalary == null && maxSalary == null) return jobs;
        int expectedMin = minSalary == null ? 0 : minSalary.intValue();
        int expectedMax = maxSalary == null ? Integer.MAX_VALUE : maxSalary.intValue();
        List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> job : jobs) {
            if (job == null) continue;
            String salary = stringValue(firstPresent(job, "salaryDesc", "salary_desc", "salary", "salaryText", "salaryName", "salaryRange", "jobSalary", "pay", "wage", "compensation"));
            String title = stringValue(firstPresent(job, "jobName", "title", "name"));
            // 日结/日薪/时薪岗位与月薪区间检索语义冲突，直接丢弃。
            if (salary.contains("天") || salary.contains("日") || salary.contains("时")) continue;
            // 实习岗与正式月薪检索语义冲突，按标题或薪资文案识别后丢弃。
            if (title.contains("实习") || salary.contains("实习")) continue;
            int[] range = parseMonthlyRangeK(salary);
            // 面议或无法解析薪资的岗位信息缺失，保留交由排序决定其位置，避免误伤。
            if (range == null) {
                filtered.add(job);
                continue;
            }
            if (range[1] >= expectedMin && range[0] <= expectedMax) filtered.add(job);
        }
        return filtered;
    }

    /** 解析月薪区间（单位 K）：优先匹配"15-20K"区间，其次单值"20K"，无法解析返回 null。 */
    private int[] parseMonthlyRangeK(String salary) {
        if (salary == null || salary.isEmpty()) return null;
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,3})\\s*[-~]\\s*(\\d{1,3})\\s*[kK]").matcher(salary);
        if (matcher.find()) {
            Integer min = toInteger(matcher.group(1));
            Integer max = toInteger(matcher.group(2));
            if (min == null || max == null) return null;
            return new int[]{Math.min(min, max), Math.max(min, max)};
        }
        java.util.regex.Matcher single = java.util.regex.Pattern.compile("(\\d{1,3})\\s*[kK]").matcher(salary);
        if (single.find()) {
            Integer val = toInteger(single.group(1));
            if (val == null) return null;
            return new int[]{val.intValue(), val.intValue()};
        }
        return null;
    }

    private Integer toInteger(Object value) {
        if (value == null) return null;
        try { return Integer.valueOf(String.valueOf(value)); } catch (Exception e) { return null; }
    }
}
