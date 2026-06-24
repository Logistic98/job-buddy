package com.jobbuddy.backend.modules.auth.service.impl;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.auth.client.BossBrowserClient;
import com.jobbuddy.backend.modules.auth.event.BossAuthLostEvent;
import com.jobbuddy.backend.modules.auth.service.BossCliService;

import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.net.URLEncoder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Boss 直聘能力实现：底层调用 agent-runtime 的 boss_browser 按需工具。
 *
 * 设计要点：
 * - 不再使用 CDP 或单独浏览器会话，真实取数由 agent-tool 中的 jackwener/boss-cli 适配层完成。
 * - Boss 访问必须串行、低频，配合 Python 侧限速与风控冷却。
 * - 真实 Cookie 保存在 agent-tool 的 boss-cli credential.json 中；Java 侧只维护非敏感登录标记，
 *   供上层做廉价的"是否曾登录"判断，避免把 Cookie 写入日志或业务库。
 */
@Service
public class BossCliServiceImpl implements BossCliService {
    // 单次批量搜索允许向 Boss 工具发起的搜索请求总量上限。工具默认每小时搜索配额较低，
    // 这里刻意压到远低于该值，给详情懒加载与同一小时内的二次搜索留出余量，
    // 避免一次请求把整小时配额打满后触发限速甚至误判硬停。
    // boss-cli 已内置请求抖动与退避，这里继续从业务层压低翻页数量。
    private static final int MAX_SEARCH_REQUESTS_PER_BATCH = 3;

    private final BossBrowserClient browserClient;
    private final ApplicationEventPublisher eventPublisher;
    private final String bossWebBaseUrl;
    private final String homeDir;
    private final Path credentialFile;

    public BossCliServiceImpl(BossBrowserClient browserClient, ApplicationEventPublisher eventPublisher,
                              JobBuddyProperties properties) {
        this.browserClient = browserClient;
        this.eventPublisher = eventPublisher;
        String configuredBase = properties == null ? null : properties.getBossWebBaseUrl();
        String base = configuredBase == null ? "" : configuredBase.trim();
        while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
        this.bossWebBaseUrl = base.isEmpty() ? "https://www.zhipin.com" : base;
        String configuredHome = System.getenv("BOSS_CLI_HOME");
        this.homeDir = configuredHome == null || configuredHome.trim().isEmpty()
                ? resolveDefaultHome()
                : resolveConfiguredHome(configuredHome);
        this.credentialFile = Paths.get(System.getenv().getOrDefault(
                "BOSS_CLI_MARKER_FILE",
                Paths.get(this.homeDir, "login-marker.json").toString()
        ));
    }

    private String resolveConfiguredHome(String configuredHome) {
        Path path = Paths.get(configuredHome).normalize();
        if (path.isAbsolute()) return path.toString();
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if ("agent-backend".equals(cwd.getFileName() == null ? "" : cwd.getFileName().toString())) {
            return cwd.getParent().resolve(path).normalize().toString();
        }
        return cwd.resolve(path).normalize().toString();
    }

    private String resolveDefaultHome() {
        Path cwd = Paths.get("").toAbsolutePath().normalize();
        if ("agent-backend".equals(cwd.getFileName() == null ? "" : cwd.getFileName().toString())) {
            return cwd.getParent().resolve(".run").resolve("boss-cli-home").toString();
        }
        return cwd.resolve(".run").resolve("boss-cli-home").toString();
    }

    // ---- 登录态 ----
    public Map<String, Object> status() {
        Map<String, Object> envelope = browserClient.get("/status");
        Map<String, Object> data = dataOf(envelope);
        boolean authenticated = success(envelope) && Boolean.TRUE.equals(data.get("authenticated"));
        if (authenticated) ensureLocalMarker(data);
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        result.put("authenticated", authenticated);
        result.put("search_authenticated", authenticated);
        result.put("ok", authenticated);
        result.put("status", authenticated ? "logged_in" : "auth_required");
        result.put("provider", "boss-zhipin");
        result.put("homeDir", homeDir);
        result.put("credentialPersisted", hasLocalCredential());
        result.put("riskMarker", data.get("risk_marker"));
        result.put("finalUrl", data.get("final_url"));
        if (!success(envelope)) result.put("error", errorOf(envelope));
        return result;
    }

    private void ensureLocalMarker(Map<String, Object> source) {
        if (hasLocalCredential()) return;
        String credentialPath = source == null || source.get("credential_file") == null ? "" : String.valueOf(source.get("credential_file"));
        String markerJson = "{\"provider\":\"jackwener/boss-cli\","
                + "\"status\":\"logged_in\","
                + "\"updatedAt\":\"" + jsonEscape(Instant.now().toString()) + "\","
                + "\"credentialFile\":\"" + jsonEscape(credentialPath) + "\"}";
        writeCredential(markerJson);
    }

    private String jsonEscape(String value) {
        if (value == null) return "";
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    public String readCredentialJson() {
        try {
            if (!Files.exists(credentialFile)) return null;
            return new String(Files.readAllBytes(credentialFile), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null;
        }
    }

    public boolean hasLocalCredential() {
        String json = readCredentialJson();
        return json != null && !json.trim().isEmpty();
    }

    public boolean restoreCredentialIfMissing(String credentialJson) {
        if (credentialJson == null || credentialJson.trim().isEmpty() || hasLocalCredential()) return false;
        return writeCredential(credentialJson);
    }

    public boolean writeCredential(String credentialJson) {
        try {
            Path parent = credentialFile.getParent();
            if (parent != null) Files.createDirectories(parent);
            Files.write(credentialFile, credentialJson.getBytes(StandardCharsets.UTF_8));
            credentialFile.toFile().setReadable(false, false);
            credentialFile.toFile().setWritable(false, false);
            credentialFile.toFile().setReadable(true, true);
            credentialFile.toFile().setWritable(true, true);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private void clearLocalCredential() {
        try {
            Files.deleteIfExists(credentialFile);
        } catch (Exception ignored) {
            // 删除失败不阻断主流程。
        }
        // Boss 工具判定登录态失效时，删除本地标记的同时广播事件，让上层登录态缓存
        // （BossAuthService 的 5 分钟缓存）立即失效，避免静默使用过期登录态继续访问 Boss。
        if (eventPublisher != null) {
            eventPublisher.publishEvent(new BossAuthLostEvent("boss_cli_auth_required"));
        }
    }

    public boolean isAuthenticated() {
        return statusAuthenticated(status());
    }

    private boolean statusAuthenticated(Map<String, Object> status) {
        if (status == null || status.isEmpty()) return false;
        if (Boolean.TRUE.equals(status.get("ok"))) return true;
        if (Boolean.TRUE.equals(status.get("authenticated"))
                || Boolean.TRUE.equals(status.get("search_authenticated"))) return true;
        Object data = status.get("data");
        if (data instanceof Map) {
            Map map = (Map) data;
            return Boolean.TRUE.equals(map.get("authenticated"))
                    || Boolean.TRUE.equals(map.get("search_authenticated"))
                    || "logged_in".equals(String.valueOf(map.get("status")));
        }
        return "logged_in".equals(String.valueOf(status.get("status")));
    }

    public Map<String, Object> loginInstructions() {
        Map<String, Object> currentStatus = status();
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        if (statusAuthenticated(currentStatus)) {
            response.put("authRequired", false);
            response.put("provider", "boss-zhipin");
            response.put("status", "logged_in");
            response.put("ok", true);
            response.put("message", "Boss 登录态有效。");
            response.put("homeDir", homeDir);
            return response;
        }
        response.put("authRequired", true);
        response.put("provider", "boss-zhipin");
        response.put("status", "auth_required");
        response.put("message", "Boss 直聘未登录，请在登录弹窗中扫码完成登录。");
        response.put("homeDir", homeDir);
        return response;
    }

    /**
     * 取数接口返回 4001（登录态不足以完成搜索/详情/画像）时，用来构造随异常携带的 authData。
     *
     * 不能在这里回查 {@link #status()}：status 只校验 wt2 cookie 是否存在，而搜索还依赖 __zp_stoken__，
     * 当 stoken 过期且静默刷新失败时，status 仍会判定 logged_in，导致前端出现"提示需要登录、authData 却显示已登录"
     * 的自相矛盾。既然当前操作已经因登录态不足而失败，这里直接返回 authRequired:true，保证提示与 authData 一致。
     */
    private Map<String, Object> authRequiredInstructions() {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("authRequired", true);
        response.put("provider", "boss-zhipin");
        response.put("status", "auth_required");
        response.put("ok", false);
        response.put("message", "Boss 登录态已失效或不完整，请在登录弹窗中重新扫码完成登录。");
        response.put("homeDir", homeDir);
        return response;
    }

    // ---- 扫码登录 ----
    public Map<String, Object> qrStart() {
        Map<String, Object> envelope = browserClient.post("/login/qr/start", Collections.<String, Object>emptyMap());
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        if (!success(envelope)) {
            response.put("ok", false);
            response.put("data", null);
            response.put("error", errorOf(envelope));
            return response;
        }
        Map<String, Object> data = dataOf(envelope);
        // 浏览器模型只有一条登录流，这里合成 session_id 让既有前端轮询路径继续生效。
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("session_id", UUID.randomUUID().toString());
        payload.put("qr_id", null);
        payload.put("image_base64", data.get("image_base64"));
        payload.put("image_mime", data.get("image_mime"));
        payload.put("expires_at", null);
        payload.put("status", "qr_ready");
        payload.put("login_url", data.get("login_url"));
        response.put("ok", true);
        response.put("data", payload);
        return response;
    }

    public Map<String, Object> qrStatus(String sessionId) {
        Map<String, Object> envelope = browserClient.post("/login/qr/status", Collections.<String, Object>emptyMap());
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        int code = code(envelope);
        Map<String, Object> data = dataOf(envelope);
        boolean authenticated = success(code) && Boolean.TRUE.equals(data.get("authenticated"));
        String toolStatus = stringOrDefault(data.get("status"), "");
        String reason = stringOrDefault(data.get("reason"), "");
        Object toolError = data.get("error");

        // 将工具的细分状态映射为前端可识别的语义状态，并保留错误/原因信息。
        // 关键点：扫码完成但缺少关键 Web Cookie 的 auth_required 属于终态，必须落到 error
        // 让前端停止轮询，否则会对一张已确认的二维码持续轮询、间接反复访问 Boss，触发风控。
        String status;
        Object errorMessage = null;
        if (authenticated || "logged_in".equals(toolStatus)) {
            status = "logged_in";
        } else if (!success(code) && code != 4001) {
            status = "error";
            errorMessage = message(envelope);
        } else if ("qr_expired".equals(toolStatus)) {
            status = "expired";
        } else if ("auth_required".equals(toolStatus)) {
            status = "error";
            errorMessage = toolError != null ? toolError
                    : "二维码登录未获得完整登录态，请先在浏览器登录 Boss 直聘后重试。";
        } else if ("qr_waiting_confirm".equals(reason)) {
            // 已扫码，等待手机端确认，给前端进度反馈。
            status = "scanned";
        } else {
            status = "waiting";
        }

        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("status", status);
        payload.put("authenticated", authenticated);
        payload.put("updated_at", Instant.now().toString());
        payload.put("expires_at", null);
        // Boss 工具在等待扫码期间会持续下发最新活码，透传给前端用于刷新展示，避免扫到失效旧码。
        payload.put("image_base64", data.get("image_base64"));
        payload.put("image_mime", data.get("image_mime"));
        payload.put("qr_version", data.get("qr_version"));
        if (errorMessage != null) {
            Map<String, Object> error = new LinkedHashMap<String, Object>();
            error.put("message", String.valueOf(errorMessage));
            payload.put("error", error);
        } else {
            payload.put("error", null);
        }
        response.put("ok", true);
        response.put("data", payload);
        return response;
    }

    public Map<String, Object> qrCancel(String sessionId) {
        return noop();
    }

    public Map<String, Object> cancelLogin() {
        return noop();
    }

    private Map<String, Object> noop() {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("ok", true);
        response.put("status", "noop");
        return response;
    }

    // ---- 在线求职画像 ----
    public Map<String, Object> fetchOnlineProfile() {
        Map<String, Object> envelope = browserClient.post("/profile", Collections.<String, Object>emptyMap());
        int code = code(envelope);
        if (!success(code)) {
            if (code == 4001) {
                clearLocalCredential();
                throw new BossAuthRequiredException("Boss 直聘未登录或登录态不完整，请先完成二维码登录。", authRequiredInstructions());
            }
            throw new RuntimeException("求职画像获取失败：" + message(envelope));
        }
        return dataOf(envelope);
    }

    // ---- 搜索 ----
    public List<Map<String, Object>> searchJobs(IntentResult intent) {
        return searchJobs(intent, 0);
    }

    public List<Map<String, Object>> searchJobs(IntentResult intent, int targetCount) {
        return searchJobsBatches(intent, targetCount, null);
    }

    public List<Map<String, Object>> searchJobsFirstPage(IntentResult intent, JobBatchConsumer consumer) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        List<String> queries = searchQueries(intent);
        String query = queries.isEmpty() ? "Java" : queries.get(0);
        List<Map<String, Object>> pageJobs = searchJobsPage(intent, 1, query);
        for (Map<String, Object> job : pageJobs) {
            if (seen.add(jobKey(job))) result.add(job);
        }
        if (consumer != null && !result.isEmpty()) {
            consumer.accept(new ArrayList<Map<String, Object>>(result), new ArrayList<Map<String, Object>>(result), query, 1);
        }
        return result;
    }

    public List<Map<String, Object>> searchJobsPage(IntentResult intent, int page) {
        List<String> queries = searchQueries(intent);
        String query = queries.isEmpty() ? "Java" : queries.get(0);
        return searchJobsPage(intent, Math.max(1, page), query);
    }

    /**
     * 浏览器是单会话串行、限速的，搜索强制串行执行，避免并发请求堆积在浏览器锁上并触发风控。
     * 为控制请求量、保护账号，多轮分页只使用主查询词，逐页累积到目标数量为止。
     */
    public List<Map<String, Object>> searchJobsBatches(IntentResult intent, int targetCount, JobBatchConsumer consumer) {
        int expected = Math.max(1, targetCount);
        List<Map<String, Object>> merged = new ArrayList<Map<String, Object>>();
        java.util.Set<String> seen = new java.util.HashSet<String>();
        List<String> queries = searchQueries(intent);
        int maxPages = targetCount > 0 ? Math.min(6, Math.max(1, (int) Math.ceil(expected / 15.0) + 1)) : 1;
        int searchRequests = 0;

        for (String query : queries) {
            for (int page = 1; page <= maxPages && (targetCount <= 0 || merged.size() < expected); page++) {
                if (searchRequests >= MAX_SEARCH_REQUESTS_PER_BATCH) return merged;
                List<Map<String, Object>> pageJobs;
                try {
                    pageJobs = searchJobsPage(intent, page, query);
                    searchRequests++;
                } catch (BossAuthRequiredException e) {
                    if (!merged.isEmpty()) return merged;
                    throw e;
                }
                if (pageJobs.isEmpty()) break;
                List<Map<String, Object>> added = new ArrayList<Map<String, Object>>();
                for (Map<String, Object> job : pageJobs) {
                    if (seen.add(jobKey(job))) {
                        merged.add(job);
                        added.add(job);
                    }
                    if (targetCount > 0 && merged.size() >= expected) break;
                }
                if (consumer != null && !added.isEmpty()) {
                    consumer.accept(new ArrayList<Map<String, Object>>(merged), added, query, page);
                }
            }
            if (targetCount <= 0) break; // 无目标数量时只取主查询首页，控制请求量。
            if (merged.size() >= expected) break;
        }
        return merged;
    }

    private List<String> searchQueries(IntentResult intent) {
        Map<String, Object> slots = intent.getSlots() == null ? Collections.<String, Object>emptyMap() : intent.getSlots();
        List<String> queries = new ArrayList<String>();
        Object role = slots.get("role");
        if (role != null && !String.valueOf(role).trim().isEmpty()) queries.add(String.valueOf(role));
        Object secondary = slots.get("secondary_queries");
        if (secondary instanceof List) {
            for (Object item : (List) secondary) {
                if (item != null && !String.valueOf(item).trim().isEmpty() && !queries.contains(String.valueOf(item))) {
                    queries.add(String.valueOf(item));
                }
            }
        }
        if (queries.isEmpty()) queries.add("Java");
        return queries.size() > 3 ? new ArrayList<String>(queries.subList(0, 3)) : queries;
    }

    private List<Map<String, Object>> searchJobsPage(IntentResult intent, int page, String query) {
        Map<String, Object> slots = intent.getSlots() == null ? Collections.<String, Object>emptyMap() : intent.getSlots();
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("query", stringOrDefault(query, "Java"));
        Object city = slots.get("city");
        if (city != null && !String.valueOf(city).trim().isEmpty()) body.put("city", String.valueOf(city));
        if (page > 1) body.put("page", page);

        Map<String, Object> envelope = browserClient.post("/search", body);
        int code = code(envelope);
        if (code == 4001) {
            clearLocalCredential();
            throw new BossAuthRequiredException("Boss 直聘未登录或登录态不完整，请先完成二维码登录。", authRequiredInstructions());
        }
        if (!success(code)) {
            throw new RuntimeException("岗位搜索失败：" + message(envelope));
        }
        Map<String, Object> data = dataOf(envelope);
        return enrichJobs(extractJobs(data.get("jobs")));
    }

    public List<Map<String, Object>> enrichJobDetails(List<Map<String, Object>> jobs, int maxDetails) {
        if (jobs == null || jobs.isEmpty() || maxDetails <= 0) return jobs == null ? new ArrayList<Map<String, Object>>() : jobs;
        int count = 0;
        for (Map<String, Object> job : jobs) {
            if (count >= maxDetails) break;
            String securityId = valueString(firstPresent(job, "securityId", "encryptJobId"));
            if (securityId.isEmpty()) continue;
            try {
                Map<String, Object> detail = jobDetail(securityId);
                if (!detail.isEmpty()) mergeJobDetail(job, detail);
                count++;
            } catch (BossAuthRequiredException authLoss) {
                // 详情补全过程中登录态失效：立即停手，不要继续逐个访问 Boss，
                // 否则会在风控敏感期持续高频请求。JD 可由懒加载详情接口按需补全。
                break;
            } catch (Exception detailFailure) {
                // 单个详情失败（含风控/超时等）同样停止补全，避免连续失败累积触发风控；
                // 搜索结果已可展示，JD 留待懒加载补全。
                break;
            }
        }
        return enrichJobs(jobs);
    }

    /** 按 securityId 拉取单个岗位详情（含 JD）。失败时抛出异常，便于懒加载接口分流处理。 */
    public Map<String, Object> jobDetail(String securityId) {
        return jobDetail(securityId, "");
    }

    /** 按 securityId 与原始链接拉取单个岗位详情（含 JD）。url 用于浏览器侧导航定位，可为空。 */
    public Map<String, Object> jobDetail(String securityId, String url) {
        String trimmedSecurityId = securityId == null ? "" : securityId.trim();
        String trimmedUrl = url == null ? "" : url.trim();
        if (trimmedSecurityId.isEmpty() && trimmedUrl.isEmpty()) {
            // 缺少定位信息时不要打开 Boss 首页做无效探测，避免制造额外风控流量。
            throw new RuntimeException("缺少岗位详情链接或 securityId，无法安全加载职位描述。");
        }
        Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("securityId", trimmedSecurityId);
        if (!trimmedUrl.isEmpty()) body.put("url", trimmedUrl);
        Map<String, Object> envelope = browserClient.post("/detail", body);
        int code = code(envelope);
        if (code == 4001) {
            clearLocalCredential();
            throw new BossAuthRequiredException("Boss 直聘未登录或登录态不完整，请先完成二维码登录。", authRequiredInstructions());
        }
        if (!success(code)) {
            throw new RuntimeException("岗位详情获取失败：" + message(envelope));
        }
        return dataOf(envelope);
    }

    private void mergeJobDetail(Map<String, Object> job, Map<String, Object> detail) {
        Map<String, Object> source = detail;
        Object nested = firstPresent(detail, "job", "jobInfo", "jobDetail", "detail");
        if (nested instanceof Map) source = (Map<String, Object>) nested;
        for (Map.Entry<String, Object> entry : source.entrySet()) {
            Object value = entry.getValue();
            if (value == null || String.valueOf(value).trim().isEmpty()) continue;
            job.putIfAbsent(entry.getKey(), value);
        }
        putIfPresent(job, "jobDescription", firstPresent(source, "jobDescription", "description", "postDescription", "jobDesc", "detailText", "jobSecText", "jobContent"));
        putIfPresent(job, "salaryDesc", firstPresent(source, "salaryDesc", "salary_desc", "salary", "salaryText", "salaryName", "salaryRange", "jobSalary", "pay", "wage", "compensation"));
        putIfPresent(job, "welfareList", firstPresent(source, "welfareList", "welfare", "benefits"));
        putIfPresent(job, "skillList", firstPresent(source, "skillList", "skills", "skillLabels"));
        putIfPresent(job, "companyScale", firstPresent(source, "brandScaleName", "companyScale", "scaleName", "brandScale"));
        putIfPresent(job, "companyStage", firstPresent(source, "brandStageName", "financeStage", "stageName"));
        putIfPresent(job, "companyIndustry", firstPresent(source, "brandIndustry", "industry", "industryName"));
        putIfPresent(job, "bossTitle", firstPresent(source, "bossTitle", "bossPosition", "positionTitle"));
        putIfPresent(job, "bossName", firstPresent(source, "bossName", "boss"));
    }

    private void putIfPresent(Map<String, Object> map, String key, Object value) {
        if (value != null && !String.valueOf(value).trim().isEmpty()) map.put(key, value);
    }

    private List<Map<String, Object>> enrichJobs(List<Map<String, Object>> jobs) {
        for (Map<String, Object> job : jobs) {
            String detailUrl = bossDetailUrl(job);
            if (detailUrl != null && !detailUrl.isEmpty()) {
                job.put("originalUrl", detailUrl);
                job.put("originalUrlType", "detail");
            } else {
                job.remove("originalUrl");
                job.put("originalUrlType", "missing");
            }
        }
        return jobs;
    }

    private String normalizeBossUrl(Object value) {
        if (value == null) return null;
        String url = String.valueOf(value).trim();
        if (url.isEmpty()) return null;
        if (url.startsWith("//")) return "https:" + url;
        if (url.startsWith("/")) return bossWebBaseUrl + url;
        if (url.startsWith("http://") || url.startsWith("https://")) return url;
        return null;
    }

    private String bossDetailUrl(Map<String, Object> job) {
        Object existing = firstPresent(job, "originalUrl", "jobUrl", "url", "href", "link", "detailUrl", "jobDetailUrl");
        String normalized = normalizeBossUrl(existing);
        if (normalized != null && normalized.contains("/job_detail/") && !normalized.contains("/web/geek/job?query=")) {
            return normalized;
        }
        String securityId = valueString(firstPresent(job, "securityId", "security_id"));
        String lid = valueString(firstPresent(job, "lid", "listId"));
        String pathId = firstUsableJobPathId(job, securityId);
        if (pathId.isEmpty()) return "";
        StringBuilder url = new StringBuilder(bossWebBaseUrl).append("/job_detail/").append(pathEncode(pathId)).append(".html");
        boolean hasQuery = false;
        if (!securityId.isEmpty()) {
            url.append("?securityId=").append(urlEncode(securityId));
            hasQuery = true;
        }
        if (!lid.isEmpty()) {
            url.append(hasQuery ? "&" : "?").append("lid=").append(urlEncode(lid));
        }
        return url.toString();
    }

    private String firstUsableJobPathId(Map<String, Object> job, String securityId) {
        for (String key : Arrays.asList("encryptJobId", "encrypt_job_id", "jobId", "job_id", "id")) {
            String value = valueString(job.get(key));
            if (!value.isEmpty() && !value.matches("\\d{4,}")) return value;
        }
        return "";
    }

    private String pathEncode(String value) {
        return urlEncode(value).replace("+", "%20").replace("%7E", "~");
    }

    private String valueString(Object value) {
        return value == null ? "" : String.valueOf(value).trim();
    }

    private String urlEncode(String value) {
        try {
            return URLEncoder.encode(value == null ? "" : value, "UTF-8");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }

    private String jobKey(Map<String, Object> job) {
        Object id = firstPresent(job, "securityId", "encryptJobId", "jobId", "id");
        if (id != null) return String.valueOf(id);
        return String.valueOf(firstPresent(job, "jobName", "title", "name")) + "|"
                + String.valueOf(firstPresent(job, "brandName", "companyName", "company")) + "|"
                + String.valueOf(firstPresent(job, "salaryDesc", "salary_desc", "salary", "salaryText", "salaryName", "salaryRange", "jobSalary", "pay", "wage", "compensation"));
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractJobs(Object data) {
        if (data instanceof List) return (List<Map<String, Object>>) data;
        if (data instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) data;
            Object pageLid = firstPresent(map, "lid", "listId");
            for (String key : Arrays.asList("jobs", "items", "list", "results", "jobList", "job_list")) {
                Object value = map.get(key);
                if (value instanceof List) {
                    List<Map<String, Object>> rows = (List<Map<String, Object>>) value;
                    if (pageLid != null) {
                        for (Map<String, Object> row : rows) row.putIfAbsent("lid", pageLid);
                    }
                    return rows;
                }
            }
            Object nested = map.get("data");
            if (nested instanceof List) return (List<Map<String, Object>>) nested;
            if (nested instanceof Map) return extractJobs(nested);
        }
        return new ArrayList<Map<String, Object>>();
    }

    private String stringOrDefault(Object value, String defaultValue) {
        return value == null || String.valueOf(value).trim().isEmpty() ? defaultValue : String.valueOf(value);
    }

    // ---- 统一响应解析 ----
    private int code(Map<String, Object> envelope) {
        if (envelope == null) return -1;
        Object value = envelope.get("code");
        if (value instanceof Number) return ((Number) value).intValue();
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (Exception e) {
            return -1;
        }
    }

    private boolean success(Map<String, Object> envelope) {
        return success(code(envelope));
    }

    private boolean success(int code) {
        return code == 0 || (code >= 200 && code < 300);
    }

    private String message(Map<String, Object> envelope) {
        if (envelope == null) return "";
        Object message = envelope.get("message");
        return message == null ? "" : String.valueOf(message);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> dataOf(Map<String, Object> envelope) {
        if (envelope == null) return new LinkedHashMap<String, Object>();
        Object data = envelope.get("data");
        if (data instanceof Map) return (Map<String, Object>) data;
        return new LinkedHashMap<String, Object>();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> errorOf(Map<String, Object> envelope) {
        if (envelope == null) return new LinkedHashMap<String, Object>();
        Object error = envelope.get("error");
        if (error instanceof Map) return (Map<String, Object>) error;
        Map<String, Object> fallback = new LinkedHashMap<String, Object>();
        fallback.put("code", envelope.get("code"));
        fallback.put("message", message(envelope));
        return fallback;
    }
}
