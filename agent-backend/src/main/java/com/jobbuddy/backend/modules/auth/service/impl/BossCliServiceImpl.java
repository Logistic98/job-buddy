package com.jobbuddy.backend.modules.auth.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.security.AuthenticationScope;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.client.BossBrowserClient;
import com.jobbuddy.backend.modules.auth.dto.internal.BossCliCancelResult;
import com.jobbuddy.backend.modules.auth.dto.internal.BossCliQrResult;
import com.jobbuddy.backend.modules.auth.dto.internal.BossCliStatusResult;
import com.jobbuddy.backend.modules.auth.dto.internal.BossFavoriteListResult;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginStatusResponse;
import com.jobbuddy.backend.modules.auth.event.BossAuthLostEvent;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import java.net.URLEncoder;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

/**
 * Boss 直聘能力实现：底层调用 agent-runtime 的 boss_browser 按需工具。
 *
 * <p>设计要点： - 不使用 CDP 或单独浏览器会话，真实取数由 agent-tool 中的 jackwener/boss-cli 适配层完成。 - Boss 访问必须串行、低频，配合
 * Python 侧限速与风控冷却。 - 真实 Cookie 由 Java 侧持久化到 PostgreSQL auth_state，并随工具请求注入 agent-tool 内存；
 * 禁止写入本地凭证文件、日志、Trace 或聊天内容。
 */
@Service
public class BossCliServiceImpl implements BossCliService {
  // 单次批量搜索允许向 Boss 工具发起的搜索请求总量上限。工具默认每小时搜索配额较低，
  // 这里刻意压到远低于该值，给最终展示岗位的详情补全与同一小时内的二次搜索留出余量，
  // 避免一次请求把整小时配额打满后触发限速甚至误判硬停。
  // boss-cli 已内置请求抖动与退避，这里继续从业务层压低翻页数量。
  private static final int MAX_SEARCH_REQUESTS_PER_BATCH = 3;
  private static final int MIN_JOB_DESCRIPTION_CHARS = 30;
  private static final long FAVORITE_PAGE_CACHE_TTL_MILLIS = 2 * 60 * 1000L;

  private final BossBrowserClient browserClient;
  private final ApplicationEventPublisher eventPublisher;
  private final String bossWebBaseUrl;
  private final String homeDir = "PostgreSQL: auth_state/boss-zhipin";
  private final JsonCodec jsonCodec = new JsonCodec();
  private final Map<String, FavoritePageCacheEntry> favoritePageCache =
      new ConcurrentHashMap<String, FavoritePageCacheEntry>();

  /**
   * 创建 Boss CLI 服务实例。
   *
   * @param browserClient 浏览器客户端
   * @param eventPublisher 事件发布器
   * @param properties 配置属性
   */
  public BossCliServiceImpl(
      BossBrowserClient browserClient,
      ApplicationEventPublisher eventPublisher,
      JobBuddyProperties properties) {
    this.browserClient = browserClient;
    this.eventPublisher = eventPublisher;
    String configuredBase = properties == null ? null : properties.getBossWebBaseUrl();
    String base = configuredBase == null ? "" : configuredBase.trim();
    while (base.endsWith("/")) base = base.substring(0, base.length() - 1);
    this.bossWebBaseUrl = base.isEmpty() ? "https://www.zhipin.com" : base;
  }

  // ---- 登录态 ----
  /**
   * 获取状态。
   *
   * @return 当前状态
   */
  public BossCliStatusResult status() {
    Map<String, Object> envelope = browserClient.get("/status");
    Map<String, Object> data = dataOf(envelope);
    boolean statusAvailable = success(envelope);
    boolean authenticated = statusAvailable && Boolean.TRUE.equals(data.get("authenticated"));
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    result.put("authenticated", authenticated);
    result.put("search_authenticated", authenticated);
    result.put("ok", authenticated);
    result.put(
        "status", authenticated ? "logged_in" : (statusAvailable ? "auth_required" : "error"));
    result.put("provider", "boss-zhipin");
    result.put("homeDir", homeDir);
    result.put("riskMarker", data.get("risk_marker"));
    result.put("finalUrl", data.get("final_url"));
    if (!success(envelope)) result.put("error", errorOf(envelope));
    return jsonCodec.convert(result, BossCliStatusResult.class);
  }

  /**
   * 通知认证状态丢失。
   */
  private void notifyAuthLost() {
    favoritePageCache.clear();
    // Boss 工具判定登录态失效时广播事件，让上层当前用户的登录态缓存立即失效，
    // 避免静默使用过期登录态继续访问 Boss。
    if (eventPublisher != null) {
      eventPublisher.publishEvent(new BossAuthLostEvent("boss_cli_auth_required"));
    }
  }

  /**
   * 判断已认证用户。
   *
   * @return 用户是否已认证
   */
  public boolean isAuthenticated() {
    return statusAuthenticated(jsonCodec.toMap(status()));
  }

  /**
   * 判断状态已认证用户。
   *
   * @param status 状态
   * @return 用户是否已认证
   */
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

  /**
   * 获取登录指令。
   *
   * @return 登录指令
   */
  public BossLoginStatusResponse loginInstructions() {
    Map<String, Object> currentStatus = jsonCodec.toMap(status());
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    if (statusAuthenticated(currentStatus)) {
      response.put("authRequired", false);
      response.put("provider", "boss-zhipin");
      response.put("status", "logged_in");
      response.put("ok", true);
      response.put("message", "Boss 登录态有效。");
      response.put("homeDir", homeDir);
      return jsonCodec.convert(response, BossLoginStatusResponse.class);
    }
    response.put("authRequired", true);
    response.put("provider", "boss-zhipin");
    response.put("status", "auth_required");
    response.put("message", "Boss 直聘未登录，请在登录弹窗中扫码完成登录。");
    response.put("homeDir", homeDir);
    return jsonCodec.convert(response, BossLoginStatusResponse.class);
  }

  /**
   * 取数接口返回 4001（登录态不足以完成搜索/详情/画像）时，用来构造随异常携带的 authData。
   *
   * <p>不能在这里回查 {@link #status()}：status 只校验 wt2 cookie 是否存在，而搜索还依赖 __zp_stoken__， 当 stoken
   * 过期且静默刷新失败时，status 仍会判定 logged_in，导致前端出现"提示需要登录、authData 却显示已登录" 的自相矛盾。既然当前操作已经因登录态不足而失败，这里直接返回
   * authRequired:true，保证提示与 authData 一致。
   *
   * @return 认证操作说明
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
  /**
   * 启动二维码登录。
   *
   * @return 二维码登录启动结果
   */
  public BossCliQrResult qrStart() {
    String sessionId = UUID.randomUUID().toString();
    Map<String, Object> requestPayload = new LinkedHashMap<String, Object>();
    requestPayload.put("session_id", sessionId);
    Map<String, Object> envelope = browserClient.post("/login/qr/start", requestPayload);
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    if (!success(envelope)) {
      response.put("ok", false);
      response.put("data", null);
      response.put("error", errorOf(envelope));
      return jsonCodec.convert(response, BossCliQrResult.class);
    }
    Map<String, Object> data = dataOf(envelope);
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("session_id", stringOrDefault(data.get("session_id"), sessionId));
    payload.put("session_token", data.get("session_token"));
    payload.put("qr_id", null);
    payload.put("image_base64", data.get("image_base64"));
    payload.put("image_mime", data.get("image_mime"));
    payload.put("expires_at", null);
    payload.put("status", "qr_ready");
    payload.put("login_url", data.get("login_url"));
    response.put("ok", true);
    response.put("data", payload);
    return jsonCodec.convert(response, BossCliQrResult.class);
  }

  /**
   * 获取二维码状态。
   *
   * @param sessionId 会话标识
   * @param sessionToken 会话令牌
   * @return 二维码状态
   */
  public BossCliQrResult qrStatus(String sessionId, String sessionToken) {
    return qrStatus(sessionId, sessionToken, true);
  }

  /**
   * 获取二维码本地快照，不等待 Boss 长轮询。
   *
   * @param sessionId 会话标识
   * @param sessionToken 会话令牌
   * @return 二维码当前快照
   */
  public BossCliQrResult qrSnapshot(String sessionId, String sessionToken) {
    return qrStatus(sessionId, sessionToken, false);
  }

  /**
   * 获取二维码状态或本地快照。
   *
   * @param sessionId 会话标识
   * @param sessionToken 会话令牌
   * @param waitForUpdate 是否等待上游扫码状态变化
   * @return 二维码状态
   */
  private BossCliQrResult qrStatus(String sessionId, String sessionToken, boolean waitForUpdate) {
    Map<String, Object> requestPayload = new LinkedHashMap<String, Object>();
    requestPayload.put("session_id", sessionId);
    requestPayload.put("session_token", sessionToken);
    requestPayload.put("wait_for_update", waitForUpdate);
    Map<String, Object> envelope = browserClient.post("/login/qr/status", requestPayload);
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
      errorMessage = toolError != null ? toolError : "二维码登录未获得完整登录态，请先在浏览器登录 Boss 直聘后重试。";
    } else if ("qr_confirmed".equals(toolStatus) || "qr_confirmed".equals(reason)) {
      // 手机端确认后立即返回中间态，下一轮再完成凭据派发与补齐，避免前端长时间停留在等待扫码。
      status = "confirmed";
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
    // Boss 工具在等待扫码期间会持续下发最新活码，透传给前端用于刷新展示，避免扫到已失效二维码。
    payload.put("image_base64", data.get("image_base64"));
    payload.put("image_mime", data.get("image_mime"));
    payload.put("qr_version", data.get("qr_version"));
    // 仅在 Java 服务内部返回给 BossAuthService 立即按当前 tenant/user 加密持久化；
    // Controller 响应会显式剥离该字段，不能进入前端、日志、Trace 或聊天事件。
    payload.put("credential_json", data.get("credential_json"));
    payload.put("session_token", data.get("session_token"));
    if (errorMessage != null) {
      Map<String, Object> error = new LinkedHashMap<String, Object>();
      error.put("message", String.valueOf(errorMessage));
      payload.put("error", error);
    } else {
      payload.put("error", null);
    }
    response.put("ok", true);
    response.put("data", payload);
    return jsonCodec.convert(response, BossCliQrResult.class);
  }

  /**
   * 取消二维码登录。
   *
   * @param sessionId 会话标识
   * @param sessionToken 会话令牌
   * @return 二维码登录取消结果
   */
  public BossCliCancelResult qrCancel(String sessionId, String sessionToken) {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("session_id", sessionId);
    payload.put("session_token", sessionToken);
    Map<String, Object> envelope = browserClient.post("/login/qr/cancel", payload);
    if (!success(envelope)) {
      throw new IllegalStateException(message(envelope));
    }
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("ok", true);
    response.put("status", "cancelled");
    return jsonCodec.convert(response, BossCliCancelResult.class);
  }

  /**
   * 取消登录。
   *
   * @return 登录取消结果
   */
  public BossCliCancelResult cancelLogin() {
    return noop();
  }

  /**
   * 构建空操作结果。
   *
   * @return 空操作结果
   */
  private BossCliCancelResult noop() {
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("ok", true);
    response.put("status", "noop");
    return jsonCodec.convert(response, BossCliCancelResult.class);
  }

  // ---- 在线求职画像 ----
  /**
   * 获取在线数据画像。
   *
   * @return 在线数据画像
   */
  public JsonNode fetchOnlineProfile() {
    Map<String, Object> envelope =
        browserClient.post("/profile", Collections.<String, Object>emptyMap());
    int code = code(envelope);
    if (!success(code)) {
      if (code == 4001) {
        notifyAuthLost();
        throw new BossAuthRequiredException(
            "Boss 直聘未登录或登录态不完整，请先完成二维码登录。", authRequiredInstructions());
      }
      throw new RuntimeException("求职画像获取失败：" + message(envelope));
    }
    return jsonCodec.toTree(dataOf(envelope));
  }

  // ---- Boss 收藏列表 ----
  /**
   * 获取收藏岗位列表。
   *
   * @param page 页码
   * @return 收藏岗位列表
   */
  public BossFavoriteListResult favoriteJobs(int page) {
    return favoriteJobs(page, false);
  }

  /**
   * 获取收藏岗位列表。
   *
   * @param page 页码
   * @param forceRefresh 是否强制刷新
   * @return 收藏岗位列表
   */
  public BossFavoriteListResult favoriteJobs(int page, boolean forceRefresh) {
    int safePage = Math.max(1, page);
    String cacheKey = favoritePageCacheKey(safePage);
    FavoritePageCacheEntry cached = favoritePageCache.get(cacheKey);
    if (!forceRefresh && cached != null && !cached.expired()) return cached.value;
    if (cached != null && cached.expired()) favoritePageCache.remove(cacheKey, cached);
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("page", safePage);
    Map<String, Object> envelope = browserClient.post("/favorites", body);
    int code = code(envelope);
    if (code == 4001) {
      notifyAuthLost();
      throw new BossAuthRequiredException(
          "Boss 直聘未登录或登录态不完整，请先完成二维码登录。", authRequiredInstructions());
    }
    if (!success(code)) {
      throw new RuntimeException("Boss 收藏列表获取失败：" + message(envelope));
    }
    Map<String, Object> data = dataOf(envelope);
    List<Map<String, Object>> normalized = enrichJobs(extractJobs(data.get("jobs")));
    List<JsonNode> jobs = new ArrayList<JsonNode>();
    for (Map<String, Object> job : normalized) jobs.add(jsonCodec.toTree(job));
    BossFavoriteListResult result =
        new BossFavoriteListResult(
            jobs,
            numberOrDefault(data.get("page"), safePage),
            Boolean.TRUE.equals(data.get("hasMore")),
            numberOrDefault(data.get("totalCount"), jobs.size()),
            numberOrDefault(data.get("totalPages"), safePage),
            jsonCodec.toTree(data.get("rate")));
    favoritePageCache.put(cacheKey, new FavoritePageCacheEntry(result));
    return result;
  }

  /**
   * 获取收藏岗位分页缓存键。
   *
   * @param page 页码
   * @return 收藏岗位分页缓存键
   */
  private String favoritePageCacheKey(int page) {
    return stringOrDefault(AuthenticationScope.tenantId(), "default-tenant")
        + ":"
        + stringOrDefault(AuthenticationScope.userId(), "default-user")
        + ":"
        + page;
  }

  /**
   * 定义收藏岗位分页缓存条目。
   */
  private static final class FavoritePageCacheEntry {
    private final BossFavoriteListResult value;
    private final long createdAt = System.currentTimeMillis();

    /**
     * 创建收藏岗位分页缓存条目实例。
     *
     * @param value 待处理值
     */
    private FavoritePageCacheEntry(BossFavoriteListResult value) {
      this.value = value;
    }

    /**
     * 判断会话是否过期。
     *
     * @return 会话是否过期是否成立
     */
    private boolean expired() {
      return System.currentTimeMillis() - createdAt > FAVORITE_PAGE_CACHE_TTL_MILLIS;
    }
  }

  // ---- 搜索 ----
  /**
   * 检索岗位。
   *
   * @param intent 意图
   * @return 岗位搜索结果
   */
  public List<Map<String, Object>> searchJobs(IntentResult intent) {
    return searchJobs(intent, 0);
  }

  /**
   * 检索岗位。
   *
   * @param intent 意图
   * @param targetCount 目标数量
   * @return 岗位搜索结果
   */
  public List<Map<String, Object>> searchJobs(IntentResult intent, int targetCount) {
    return searchJobsBatches(intent, targetCount, null);
  }

  /**
   * 检索岗位首个分页。
   *
   * @param intent 意图
   * @return 岗位搜索首页结果
   */
  public List<Map<String, Object>> searchJobsFirstPage(IntentResult intent) {
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    java.util.Set<String> seen = new java.util.HashSet<String>();
    List<String> queries = searchQueries(intent);
    String query = queries.isEmpty() ? "Java" : queries.get(0);
    List<Map<String, Object>> pageJobs = searchJobsPage(intent, 1, query);
    for (Map<String, Object> job : pageJobs) {
      if (seen.add(jobKey(job))) result.add(job);
    }
    return result;
  }

  /**
   * 检索岗位分页。
   *
   * @param intent 意图
   * @param page 页码
   * @return 岗位搜索分页结果
   */
  public List<Map<String, Object>> searchJobsPage(IntentResult intent, int page) {
    List<String> queries = searchQueries(intent);
    String query = queries.isEmpty() ? "Java" : queries.get(0);
    return searchJobsPage(intent, Math.max(1, page), query);
  }

  /**
   * 浏览器是单会话串行、限速的，搜索强制串行执行，避免并发请求堆积在浏览器锁上并触发风控。 为控制请求量、保护账号，多轮分页只使用主查询词，逐页累积到目标数量为止。
   *
   * @param intent 意图
   * @param targetCount 目标数量
   * @param consumer 结果消费函数
   * @return 岗位搜索批次列表
   */
  public List<Map<String, Object>> searchJobsBatches(
      IntentResult intent, int targetCount, JobBatchConsumer consumer) {
    int expected = Math.max(1, targetCount);
    List<Map<String, Object>> merged = new ArrayList<Map<String, Object>>();
    java.util.Set<String> seen = new java.util.HashSet<String>();
    List<String> queries = searchQueries(intent);
    int maxPages =
        targetCount > 0 ? Math.min(6, Math.max(1, (int) Math.ceil(expected / 15.0) + 1)) : 1;
    int searchRequests = 0;

    for (String query : queries) {
      for (int page = 1;
          page <= maxPages && (targetCount <= 0 || merged.size() < expected);
          page++) {
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

  /**
   * 生成岗位检索词。
   *
   * @param intent 意图
   * @return 岗位检索词
   */
  private List<String> searchQueries(IntentResult intent) {
    Map<String, Object> slots =
        intent.getSlots() == null ? Collections.<String, Object>emptyMap() : intent.getSlots();
    List<String> queries = new ArrayList<String>();
    Object role = slots.get("role");
    if (role != null && !String.valueOf(role).trim().isEmpty()) queries.add(String.valueOf(role));
    Object secondary = slots.get("secondary_queries");
    if (secondary instanceof List) {
      for (Object item : (List) secondary) {
        if (item != null
            && !String.valueOf(item).trim().isEmpty()
            && !queries.contains(String.valueOf(item))) {
          queries.add(String.valueOf(item));
        }
      }
    }
    if (queries.isEmpty()) queries.add("Java");
    return queries.size() > 3 ? new ArrayList<String>(queries.subList(0, 3)) : queries;
  }

  /**
   * 检索岗位分页。
   *
   * @param intent 意图
   * @param page 页码
   * @param query 查询条件
   * @return 岗位搜索分页结果
   */
  private List<Map<String, Object>> searchJobsPage(IntentResult intent, int page, String query) {
    Map<String, Object> slots =
        intent.getSlots() == null ? Collections.<String, Object>emptyMap() : intent.getSlots();
    Map<String, Object> body = new LinkedHashMap<String, Object>();
    body.put("query", stringOrDefault(query, "Java"));
    Object city = slots.get("city");
    if (city != null && !String.valueOf(city).trim().isEmpty())
      body.put("city", String.valueOf(city));
    String salaryFilter = bossSalaryFilter(slots);
    if (!salaryFilter.isEmpty()) body.put("salary", salaryFilter);
    if (page > 1) body.put("page", page);

    Map<String, Object> envelope = browserClient.post("/search", body);
    int code = code(envelope);
    if (code == 4001) {
      notifyAuthLost();
      throw new BossAuthRequiredException(
          "Boss 直聘未登录或登录态不完整，请先完成二维码登录。", authRequiredInstructions());
    }
    if (!success(code)) {
      throw new RuntimeException("岗位搜索失败：" + message(envelope));
    }
    Map<String, Object> data = dataOf(envelope);
    return enrichJobs(extractJobs(data.get("jobs")));
  }

  /**
   * 将完全落在单个 Boss 薪资桶内的用户区间下推到上游。跨桶区间不下推，避免因为 Boss
   * 只接受单个枚举值而漏召回；精确区间仍由本地规则复核。
   *
   * @param slots 意图槽位
   * @return Boss 薪资筛选枚举，无法安全下推时返回空字符串
   */
  private String bossSalaryFilter(Map<String, Object> slots) {
    int min = numberOrDefault(slots.get("salary_min_k"), -1);
    int max = numberOrDefault(slots.get("salary_max_k"), -1);
    if (min < 0 || max < 0) {
      return min >= 50 && max < 0 ? "50K以上" : "";
    }
    int lower = Math.min(min, max);
    int upper = Math.max(min, max);
    if (upper <= 3) return "3K以下";
    if (lower >= 3 && upper <= 5) return "3-5K";
    if (lower >= 5 && upper <= 10) return "5-10K";
    if (lower >= 10 && upper <= 15) return "10-15K";
    if (lower >= 15 && upper <= 20) return "15-20K";
    if (lower >= 20 && upper <= 30) return "20-30K";
    if (lower >= 30 && upper <= 50) return "30-50K";
    if (lower >= 50) return "50K以上";
    return "";
  }

  /**
   * 补充岗位详情。
   *
   * @param jobs 岗位列表
   * @param maxDetails 最大补全岗位数量
   * @return 补全岗位详情列表
   */
  public List<Map<String, Object>> enrichJobDetails(
      List<Map<String, Object>> jobs, int maxDetails) {
    List<Map<String, Object>> enriched = new ArrayList<Map<String, Object>>();
    if (jobs == null || jobs.isEmpty()) return enriched;
    for (Map<String, Object> job : jobs) {
      enriched.add(
          job == null
              ? new LinkedHashMap<String, Object>()
              : new LinkedHashMap<String, Object>(job));
    }
    if (maxDetails <= 0) return enriched;
    int detailJobs = 0;
    boolean transientRetryUsed = false;
    for (Map<String, Object> job : enriched) {
      if (hasSufficientJobDescription(job)) continue;
      if (detailJobs >= maxDetails) break;
      String explicitSecurityId = valueString(firstPresent(job, "securityId", "security_id"));
      String securityId =
          valueString(
              firstPresent(
                  job,
                  "securityId",
                  "security_id",
                  "encryptJobId",
                  "encrypt_job_id",
                  "jobId",
                  "job_id"));
      String detailUrl =
          valueString(
              firstPresent(
                  job,
                  "originalUrl",
                  "jobUrl",
                  "url",
                  "href",
                  "link",
                  "detailUrl",
                  "jobDetailUrl"));
      if (securityId.isEmpty() && detailUrl.isEmpty()) continue;
      detailJobs++;
      boolean detailLoaded = false;
      for (int attempt = 0; attempt < 2; attempt++) {
        try {
          Map<String, Object> detail = jsonCodec.toMap(jobDetail(securityId, detailUrl));
          if (!detail.isEmpty()) mergeJobDetail(job, detail);
          detailLoaded = true;
          break;
        } catch (BossAuthRequiredException authLoss) {
          // 详情补全过程中登录态失效：立即停手，不要继续逐个访问 Boss，
          // 否则会在风控敏感期持续高频请求。保留已补全结果并让推荐链路直接展示。
          return enrichJobs(enriched);
        } catch (BossJobDetailFailure detailFailure) {
          boolean canRetry =
              attempt == 0
                  && !transientRetryUsed
                  && isRetryableJobDetailFailure(explicitSecurityId, detailFailure);
          if (!canRetry) return enrichJobs(enriched);
          transientRetryUsed = true;
        } catch (RuntimeException unexpectedDetailFailure) {
          // 未携带稳定 Boss 业务码的异常不做猜测性重试，避免把永久错误放大为连续访问。
          return enrichJobs(enriched);
        }
      }
      if (!detailLoaded) return enrichJobs(enriched);
    }
    return enrichJobs(enriched);
  }

  /**
   * 判断岗位详情失败是否属于允许即时补偿一次的稳定瞬时故障。
   *
   * @param securityId Boss 岗位安全标识
   * @param failure 岗位详情业务失败
   * @return 是否允许补偿一次
   */
  private boolean isRetryableJobDetailFailure(String securityId, BossJobDetailFailure failure) {
    if (securityId == null || securityId.trim().isEmpty() || failure.code != 5001) return false;
    return failure.bossMessage.contains("未拿到岗位详情数据，请稍后重试")
        || failure.bossMessage.contains("临时安全令牌刷新失败，请稍后重试");
  }

  /**
   * 判断岗位是否已经携带足量职位描述。
   *
   * @param job 岗位
   * @return 是否已有足量职位描述
   */
  private boolean hasSufficientJobDescription(Map<String, Object> job) {
    String description =
        valueString(
            firstPresent(
                job,
                "jobDescription",
                "description",
                "postDescription",
                "jobDesc",
                "jobSecText",
                "detailText",
                "jobRequire",
                "jobContent"));
    return description.length() >= MIN_JOB_DESCRIPTION_CHARS;
  }

  /**
   * 按 securityId 拉取单个岗位详情（含 JD）。失败时抛出异常，便于检索阶段补全与显式详情接口分流处理。
   *
   * @param securityId Boss 岗位安全标识
   * @return 岗位详情
   */
  public JsonNode jobDetail(String securityId) {
    return jobDetail(securityId, "");
  }

  /**
   * 按 securityId 与原始链接拉取单个岗位详情（含 JD）。url 用于浏览器侧导航定位，可为空。
   *
   * @param securityId Boss 岗位安全标识
   * @param url 请求地址
   * @return 岗位详情
   */
  public JsonNode jobDetail(String securityId, String url) {
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
      notifyAuthLost();
      throw new BossAuthRequiredException(
          "Boss 直聘未登录或登录态不完整，请先完成二维码登录。", authRequiredInstructions());
    }
    if (!success(code)) {
      throw new BossJobDetailFailure(code, message(envelope));
    }
    return jsonCodec.toTree(dataOf(envelope));
  }

  /**
   * 合并岗位详情。
   *
   * @param job 岗位
   * @param detail 详情
   */
  private void mergeJobDetail(Map<String, Object> job, Map<String, Object> detail) {
    Map<String, Object> source = detail;
    Object nested = firstPresent(detail, "job", "jobInfo", "jobDetail", "detail");
    if (nested instanceof Map) source = (Map<String, Object>) nested;
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      Object value = entry.getValue();
      if (value == null || String.valueOf(value).trim().isEmpty()) continue;
      job.putIfAbsent(entry.getKey(), value);
    }
    putIfPresent(
        job,
        "jobDescription",
        firstPresent(
            source,
            "jobDescription",
            "description",
            "postDescription",
            "jobDesc",
            "detailText",
            "jobSecText",
            "jobContent"));
    putIfPresent(
        job,
        "salaryDesc",
        firstPresent(
            source,
            "salaryDesc",
            "salary_desc",
            "salary",
            "salaryText",
            "salaryName",
            "salaryRange",
            "jobSalary",
            "pay",
            "wage",
            "compensation"));
    putIfPresent(job, "welfareList", firstPresent(source, "welfareList", "welfare", "benefits"));
    putIfPresent(job, "skillList", firstPresent(source, "skillList", "skills", "skillLabels"));
    putIfPresent(
        job,
        "companyScale",
        firstPresent(source, "brandScaleName", "companyScale", "scaleName", "brandScale"));
    putIfPresent(
        job, "companyStage", firstPresent(source, "brandStageName", "financeStage", "stageName"));
    putIfPresent(
        job, "companyIndustry", firstPresent(source, "brandIndustry", "industry", "industryName"));
    putIfPresent(
        job, "bossTitle", firstPresent(source, "bossTitle", "bossPosition", "positionTitle"));
    putIfPresent(job, "bossName", firstPresent(source, "bossName", "boss"));
  }

  /**
   * 仅写入非空字段。
   *
   * @param map 数据映射
   * @param key 业务键
   * @param value 输入值
   */
  private void putIfPresent(Map<String, Object> map, String key, Object value) {
    if (value != null && !String.valueOf(value).trim().isEmpty()) map.put(key, value);
  }

  /**
   * 补充岗位。
   *
   * @param jobs 岗位列表
   * @return 补全岗位列表
   */
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

  /**
   * 规范化 Boss 地址。
   *
   * @param value 输入值
   * @return 规范化后的 Boss 地址
   */
  private String normalizeBossUrl(Object value) {
    if (value == null) return null;
    String url = String.valueOf(value).trim();
    if (url.isEmpty()) return null;
    if (url.startsWith("//")) return "https:" + url;
    if (url.startsWith("/")) return bossWebBaseUrl + url;
    if (url.startsWith("http://") || url.startsWith("https://")) return url;
    return null;
  }

  /**
   * 获取 Boss 详情地址。
   *
   * @param job 岗位
   * @return Boss 详情地址
   */
  private String bossDetailUrl(Map<String, Object> job) {
    Object existing =
        firstPresent(
            job, "originalUrl", "jobUrl", "url", "href", "link", "detailUrl", "jobDetailUrl");
    String normalized = normalizeBossUrl(existing);
    if (normalized != null
        && normalized.contains("/job_detail/")
        && !normalized.contains("/web/geek/job?query=")) {
      return normalized;
    }
    String securityId = valueString(firstPresent(job, "securityId", "security_id"));
    String lid = valueString(firstPresent(job, "lid", "listId"));
    String pathId = firstUsableJobPathId(job, securityId);
    if (pathId.isEmpty()) return "";
    StringBuilder url =
        new StringBuilder(bossWebBaseUrl)
            .append("/job_detail/")
            .append(pathEncode(pathId))
            .append(".html");
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

  /**
   * 获取首个可用状态岗位路径标识。
   *
   * @param job 岗位
   * @param securityId Boss 岗位安全标识
   * @return 首个可用状态岗位路径标识
   */
  private String firstUsableJobPathId(Map<String, Object> job, String securityId) {
    for (String key : Arrays.asList("encryptJobId", "encrypt_job_id", "jobId", "job_id", "id")) {
      String value = valueString(job.get(key));
      if (!value.isEmpty() && !value.matches("\\d{4,}")) return value;
    }
    return "";
  }

  /**
   * 编码路径参数。
   *
   * @param value 输入值
   * @return 路径编码文本
   */
  private String pathEncode(String value) {
    return urlEncode(value).replace("+", "%20").replace("%7E", "~");
  }

  /**
   * 获取值字符串。
   *
   * @param value 输入值
   * @return 值字符串
   */
  private String valueString(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  /**
   * 编码 URL 参数。
   *
   * @param value 输入值
   * @return URL 编码文本
   */
  private String urlEncode(String value) {
    try {
      return URLEncoder.encode(value == null ? "" : value, "UTF-8");
    } catch (Exception e) {
      return value == null ? "" : value;
    }
  }

  /**
   * 获取岗位键。
   *
   * @param job 岗位
   * @return 岗位键
   */
  private String jobKey(Map<String, Object> job) {
    Object id = firstPresent(job, "securityId", "encryptJobId", "jobId", "id");
    if (id != null) return String.valueOf(id);
    return String.valueOf(firstPresent(job, "jobName", "title", "name"))
        + "|"
        + String.valueOf(firstPresent(job, "brandName", "companyName", "company"))
        + "|"
        + String.valueOf(
            firstPresent(
                job,
                "salaryDesc",
                "salary_desc",
                "salary",
                "salaryText",
                "salaryName",
                "salaryRange",
                "jobSalary",
                "pay",
                "wage",
                "compensation"));
  }

  /**
   * 获取首个非空值。
   *
   * @param map 数据映射
   * @param keys 键列表
   * @return 首个有效值
   */
  private Object firstPresent(Map<String, Object> map, String... keys) {
    for (String key : keys) {
      Object value = map.get(key);
      if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
    }
    return null;
  }

  /**
   * 提取岗位。
   *
   * @param data 业务数据
   * @return 岗位
   */
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

  /**
   * 计算数值或默认值。
   *
   * @param value 输入值
   * @param defaultValue 默认值
   * @return 带默认值的数值
   */
  private int numberOrDefault(Object value, int defaultValue) {
    if (value instanceof Number) return ((Number) value).intValue();
    try {
      return Integer.parseInt(String.valueOf(value));
    } catch (Exception ignored) {
      return defaultValue;
    }
  }

  /**
   * 获取字符串或默认值。
   *
   * @param value 输入值
   * @param defaultValue 默认值
   * @return 字符串或默认值
   */
  private String stringOrDefault(Object value, String defaultValue) {
    return value == null || String.valueOf(value).trim().isEmpty()
        ? defaultValue
        : String.valueOf(value);
  }

  // ---- 统一响应解析 ----
  /**
   * 计算代码。
   *
   * @param envelope 响应封装
   * @return 状态码
   */
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

  /**
   * 构建成功结果。
   *
   * @param envelope 响应封装
   * @return 成功响应
   */
  private boolean success(Map<String, Object> envelope) {
    return success(code(envelope));
  }

  /**
   * 构建成功结果。
   *
   * @param code 编码
   * @return 成功响应
   */
  private boolean success(int code) {
    return code == 0 || (code >= 200 && code < 300);
  }

  /**
   * 获取消息。
   *
   * @param envelope 响应封装
   * @return 消息
   */
  private String message(Map<String, Object> envelope) {
    if (envelope == null) return "";
    Object message = envelope.get("message");
    return message == null ? "" : String.valueOf(message);
  }

  /**
   * 获取数据对象。
   *
   * @param envelope 响应封装
   * @return 数据对象
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> dataOf(Map<String, Object> envelope) {
    if (envelope == null) return new LinkedHashMap<String, Object>();
    Object data = envelope.get("data");
    if (data instanceof Map) return (Map<String, Object>) data;
    return new LinkedHashMap<String, Object>();
  }

  /**
   * 获取错误对象。
   *
   * @param envelope 响应封装
   * @return 错误对象
   */
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

  /**
   * 保留 Boss 岗位详情业务码与原始消息，供受限补偿策略精确分类。
   */
  private static final class BossJobDetailFailure extends RuntimeException {
    private final int code;
    private final String bossMessage;

    private BossJobDetailFailure(int code, String bossMessage) {
      super("岗位详情获取失败：" + bossMessage);
      this.code = code;
      this.bossMessage = bossMessage == null ? "" : bossMessage;
    }
  }
}
