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
        String cacheKey = fastSearchCacheKey(slots, limit);
        assertBossSearchNotCoolingDown();
        CacheEntry cached = fastSearchCache.get(cacheKey);
        if (cached != null && !cached.expired(fastSearchCacheTtlMillis())) {
            List<Map<String, Object>> cachedJobs = copyJobs(cached.jobs);
            if (consumer != null && !cachedJobs.isEmpty()) consumer.accept(cachedJobs, cachedJobs, "cache", 0);
            return cachedJobs;
        }
        int page = intSlot(slots.get("boss_page"), 1);
        // 快速推荐只抓取有限页面，本地过滤排序后返回 Top N。
        restoreCredentialFromPersisted();
        List<Map<String, Object>> jobs;
        try {
            jobs = searchCandidatePoolWithTimeout(intent, slots, limit, page, consumer);
        } catch (BossAuthRequiredException e) {
            // 未登录只刷新登录态并提示扫码，不进入风控冷却，避免登录后仍被冷却拦截。
            bossAuthService.markLoginInvalid(authFailureSource("boss_job_search_fast"));
            throw e;
        } catch (RuntimeException e) {
            if (looksLikeBossRisk(e)) startBossSearchCooldown("boss_job_search_risk");
            throw e;
        }
        if (jobs != null && !jobs.isEmpty()) {
            // 搜索成功说明登录态和环境正常，清除可能残留的风控冷却。
            clearBossSearchCooldown();
            Map<String, Object> source = new LinkedHashMap<String, Object>();
            source.put("status", "logged_in");
            source.put("ok", true);
            source.put("source", "boss_job_search_success");
            bossAuthService.rememberCurrentCredential(source);
        }
        jobs = clientFilter(jobs, slots);
        jobs = settingsService.filterBlacklistedJobs(jobs);
        jobs = sortByUserRequirement(jobs, slots);
        List<Map<String, Object>> result = jobs.size() > limit ? new ArrayList<Map<String, Object>>(jobs.subList(0, limit)) : jobs;
        fastSearchCache.put(cacheKey, new CacheEntry(copyJobs(result)));
        return result;
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
        return limit + ":" + String.valueOf(slots == null ? Collections.emptyMap() : slots);
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
        CacheEntry(List<Map<String, Object>> jobs) { this.jobs = jobs; }
        boolean expired(long ttlMillis) { return System.currentTimeMillis() - createdAt > ttlMillis; }
    }

    private List<Map<String, Object>> searchCandidatePoolWithTimeout(final IntentResult intent, final Map<String, Object> slots, final int limit, final int requestedPage, final JobProgressConsumer consumer) {
        final int timeoutSeconds = bossCandidatePoolTimeoutSeconds();
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        java.util.concurrent.Future<List<Map<String, Object>>> future = executor.submit(new java.util.concurrent.Callable<List<Map<String, Object>>>() {
            @Override
            public List<Map<String, Object>> call() {
                int maxPages = envInt("BOSS_SEARCH_MAX_PAGES", properties.getBossSearchMaxPages(), 1, 5);
                int targetCandidates = envInt("BOSS_SEARCH_TARGET_CANDIDATES", properties.getBossSearchTargetCandidates(), limit, Math.max(limit, properties.getMaxJobsPerScoring()));
                int delayMillis = envInt("BOSS_SEARCH_PAGE_DELAY_MILLIS", properties.getBossSearchPageDelayMillis(), 0, 10000);
                List<Map<String, Object>> accumulated = new ArrayList<Map<String, Object>>();
                Map<String, Boolean> seen = new LinkedHashMap<String, Boolean>();
                for (int offset = 0; offset < maxPages && accumulated.size() < targetCandidates; offset++) {
                    int page = requestedPage + offset;
                    List<Map<String, Object>> rows = page <= 1
                            ? bossCliService.searchJobsFirstPage(intent, new BossCliService.JobBatchConsumer() {
                                @Override
                                public void accept(List<Map<String, Object>> accumulated, List<Map<String, Object>> latestBatch, String query, int page) {
                                    // 保留回调用于兼容 boss-cli 首屏搜索。
                                }
                            })
                            : bossCliService.searchJobsPage(intent, page);
                    mergeUniqueJobs(accumulated, seen, rows);
                    if (consumer != null && rows != null && !rows.isEmpty()) {
                        List<Map<String, Object>> filtered = clientFilter(accumulated, slots);
                        filtered = settingsService.filterBlacklistedJobs(filtered);
                        filtered = sortByUserRequirement(filtered, slots);
                        List<Map<String, Object>> preview = filtered.size() > limit ? new ArrayList<Map<String, Object>>(filtered.subList(0, limit)) : filtered;
                        consumer.accept(preview, rows, "candidate_pool", page);
                    }
                    if (rows == null || rows.isEmpty()) break;
                    if (offset + 1 < maxPages && accumulated.size() < targetCandidates && delayMillis > 0) {
                        try { Thread.sleep(delayMillis); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                    }
                }
                return accumulated;
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
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d{1,3})\\s*[-~]\\s*(\\d{1,3})\\s*[kK]").matcher(salary);
        if (!matcher.find()) return false;
        Integer jobMin = toInteger(matcher.group(1));
        Integer jobMax = toInteger(matcher.group(2));
        if (jobMin == null || jobMax == null) return false;
        int expectedMin = minSalary == null ? 0 : minSalary.intValue();
        int expectedMax = maxSalary == null ? 999 : maxSalary.intValue();
        return jobMax >= expectedMin && jobMin <= expectedMax;
    }

    private Integer toInteger(Object value) {
        if (value == null) return null;
        try { return Integer.valueOf(String.valueOf(value)); } catch (Exception e) { return null; }
    }
}
