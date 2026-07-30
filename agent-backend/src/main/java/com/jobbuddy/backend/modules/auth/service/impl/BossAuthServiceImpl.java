package com.jobbuddy.backend.modules.auth.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.common.security.AuthenticationScope;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.BossAuthProviders;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginCancelResponse;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginQrResponse;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginStatusResponse;
import com.jobbuddy.backend.modules.auth.event.BossAuthLostEvent;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.auth.repository.AuthStateRepository;
import com.jobbuddy.backend.modules.auth.service.BossAuthService;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;

/**
 * 协调二维码会话、加密凭据持久化与短期认证缓存。
 *
 * <p>凭据仅从内存注入 Tool 请求，不写本地凭据目录；登录失效事件同时清除缓存和持久化状态。
 */
@Service
public class BossAuthServiceImpl implements BossAuthService {
  private static final long DEFAULT_AUTH_CACHE_TTL_MILLIS = 5 * 60 * 1000L;
  private static final long QR_SESSION_TTL_MINUTES = 5L;

  private final BossCliService bossCliService;
  private final AuthStateRepository authStateRepository;
  private final Map<String, AuthCacheEntry> authCache =
      new ConcurrentHashMap<String, AuthCacheEntry>();
  private final Map<String, Object> authStatusLocks = new ConcurrentHashMap<String, Object>();
  private final JsonCodec jsonCodec = new JsonCodec();

  /**
   * 创建 Boss 认证服务实例。
   *
   * @param bossCliService Boss CLI 服务
   * @param authStateRepository 认证状态存储访问
   */
  public BossAuthServiceImpl(
      BossCliService bossCliService, AuthStateRepository authStateRepository) {
    this.bossCliService = bossCliService;
    this.authStateRepository = authStateRepository;
  }

  /**
   * 获取登录引导信息。
   *
   * @return 登录引导信息
   */
  public BossLoginStatusResponse loginPrompt() {
    Map<String, Object> prompt = new LinkedHashMap<String, Object>();
    prompt.put("authRequired", true);
    prompt.put("provider", BossAuthProviders.DISPLAY_PROVIDER);
    prompt.put("message", "Boss 直聘未登录，请在弹窗中扫码完成登录。");
    return jsonCodec.convert(prompt, BossLoginStatusResponse.class);
  }

  /**
   * 启动二维码登录。
   *
   * @param sessionId 会话标识
   * @return 启动后的二维码登录
   */
  public BossLoginQrResponse startQrLogin(String sessionId) {
    if (isLoggedIn(sessionId))
      return jsonCodec.convert(loggedInResponse(true, "Boss 登录态有效。"), BossLoginQrResponse.class);
    String activeQrSessionId = qrSessionIdForOwner();
    if (activeQrSessionId != null) {
      requireQrOwner(activeQrSessionId);
      Map<String, Object> active = qrLoginSnapshot(activeQrSessionId);
      active.put("authRequired", !Boolean.TRUE.equals(active.get("ok")));
      active.put("message", "继续使用当前账号未完成的 Boss 登录二维码。");
      return jsonCodec.convert(active, BossLoginQrResponse.class);
    }
    Map<String, Object> start = jsonCodec.toMap(bossCliService.qrStart());
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("authRequired", true);
    response.put("provider", BossAuthProviders.DISPLAY_PROVIDER);
    response.put("message", "请使用 Boss 直聘 App 扫描二维码完成登录。");
    if (Boolean.TRUE.equals(start.get("ok"))) {
      Map<String, Object> data = asMap(start.get("data"));
      String qrSessionId = stringValue(data.get("session_id"));
      String toolSessionToken = trimToNull(stringValue(data.remove("session_token")));
      if (toolSessionToken == null) {
        throw new IllegalStateException("Boss 二维码工具会话未返回安全令牌");
      }
      Instant expiresAt = Instant.now().plus(QR_SESSION_TTL_MINUTES, ChronoUnit.MINUTES);
      authStateRepository.saveQrSession(
          currentTenantId(), currentUserId(), sessionId, qrSessionId, toolSessionToken, expiresAt);
      response.put("qrSessionId", qrSessionId);
      response.put("qrId", data.get("qr_id"));
      response.put("imageBase64", data.get("image_base64"));
      response.put("imageMime", data.get("image_mime"));
      response.put("expiresAt", expiresAt.toString());
      response.put("status", data.get("status"));
    } else {
      response.put("status", "error");
      response.put("error", start.get("error"));
    }
    return jsonCodec.convert(response, BossLoginQrResponse.class);
  }

  /**
   * 获取登录状态。
   *
   * @param sessionId 会话标识
   * @param qrSessionIdOverride 指定的二维码会话标识
   * @return 登录状态
   */
  public BossLoginStatusResponse loginStatus(String sessionId, String qrSessionIdOverride) {
    if (isCachedAuthenticated())
      return jsonCodec.convert(
          loggedInResponse(true, "Boss 登录态缓存有效。"), BossLoginStatusResponse.class);
    String qrSessionId = trimToNull(qrSessionIdOverride);
    if (qrSessionId != null) {
      requireQrOwner(qrSessionId);
      return jsonCodec.convert(qrLoginStatus(qrSessionId), BossLoginStatusResponse.class);
    }
    qrSessionId = qrSessionIdForOwner();
    if (qrSessionId != null)
      return jsonCodec.convert(qrLoginSnapshot(qrSessionId), BossLoginStatusResponse.class);
    return jsonCodec.convert(validateLoginState(false), BossLoginStatusResponse.class);
  }

  /**
   * 取消登录。
   *
   * @param sessionId 会话标识
   * @param qrSessionIdOverride 指定的二维码会话标识
   * @return 登录取消结果
   */
  public BossLoginCancelResponse cancelLogin(String sessionId, String qrSessionIdOverride) {
    String qrSessionId = trimToNull(qrSessionIdOverride);
    if (qrSessionId == null) qrSessionId = qrSessionIdForOwner();
    if (qrSessionId == null)
      return jsonCodec.convert(bossCliService.cancelLogin(), BossLoginCancelResponse.class);
    Map<String, Object> qrSession = requireQrOwner(qrSessionId);
    BossLoginCancelResponse response =
        jsonCodec.convert(
            bossCliService.qrCancel(qrSessionId, requiredToolSessionToken(qrSession)),
            BossLoginCancelResponse.class);
    authStateRepository.deleteQrSession(currentTenantId(), currentUserId(), qrSessionId);
    return response;
  }

  /**
   * 退出当前用户的 Boss 登录态。
   *
   * <p>只清理当前租户和用户持久化的 Boss 凭据、认证缓存及未完成二维码会话，不影响 JobBuddy 登录会话。
   *
   * @return 退出登录结果
   */
  public BossLoginCancelResponse logout() {
    String tenantId = currentTenantId();
    String userId = currentUserId();
    Object lock = authStatusLocks.computeIfAbsent(scopeKey(), ignored -> new Object());
    synchronized (lock) {
      Map<String, Object> logoutMetadata = new LinkedHashMap<String, Object>();
      logoutMetadata.put("provider", BossAuthProviders.STORAGE_PROVIDER);
      logoutMetadata.put("status", "logged_out");
      logoutMetadata.put("loggedOutAt", Instant.now().toString());
      authStateRepository.clearCredential(
          tenantId, userId, BossAuthProviders.STORAGE_PROVIDER, "logged_out", logoutMetadata);
      authStateRepository.clearCredential(
          tenantId,
          userId,
          BossAuthProviders.LEGACY_STORAGE_PROVIDER,
          "logged_out",
          logoutMetadata);
      authStateRepository.deleteQrSessionsForOwner(tenantId, userId);
      clearAuthenticatedCache("logged_out");
    }

    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("ok", true);
    response.put("status", "logged_out");
    return jsonCodec.convert(response, BossLoginCancelResponse.class);
  }

  /**
   * 判断是否已登录。
   *
   * @param sessionId 会话标识
   * @return 是否已登录
   */
  public boolean isLoggedIn(String sessionId) {
    if (isCachedAuthenticated()) return true;
    return isStatusAuthenticated(validateLoginState(false));
  }

  /**
   * 搜索或详情成功只刷新当前属主状态，保留已有加密凭据。
   *
   * @param source 源数据
   */
  public void rememberCurrentCredential(JsonNode source) {
    markAuthenticated();
    authStateRepository.updateStatus(
        BossAuthProviders.STORAGE_PROVIDER, "logged_in", metadata(source));
  }

  /**
   * 标记登录凭据失效。
   *
   * @param source 源数据
   */
  public void markLoginInvalid(JsonNode source) {
    clearAuthenticatedCache("auth_required");
    authStateRepository.updateStatus(
        BossAuthProviders.STORAGE_PROVIDER, "auth_required", metadata(source));
  }

  /**
   * 处理 Boss 认证失效。
   *
   * @param event 事件名称
   */
  @EventListener
  public void onBossAuthLost(BossAuthLostEvent event) {
    clearAuthenticatedCache("auth_required");
  }

  /**
   * 校验并获取登录或异常。
   *
   * @param sessionId 会话标识
   */
  public void requireLoginOrThrow(String sessionId) {
    if (isLoggedIn(sessionId)) return;
    throw new BossAuthRequiredException("Boss 直聘未登录，请先完成二维码登录。", jsonCodec.toMap(loginPrompt()));
  }

  /**
   * 获取二维码登录状态。
   *
   * @param qrSessionId 二维码会话标识
   * @return 二维码登录状态
   */
  private Map<String, Object> qrLoginStatus(String qrSessionId) {
    Object lock = authStatusLocks.computeIfAbsent(scopeKey(), ignored -> new Object());
    synchronized (lock) {
      return qrLoginStatusLocked(qrSessionId);
    }
  }

  /**
   * 获取当前二维码的本地快照，不占用上游长轮询。
   *
   * @param qrSessionId 二维码会话标识
   * @return 二维码当前快照
   */
  private Map<String, Object> qrLoginSnapshot(String qrSessionId) {
    Map<String, Object> qrSession = requireQrOwner(qrSessionId);
    Map<String, Object> result =
        jsonCodec.toMap(
            bossCliService.qrSnapshot(qrSessionId, requiredToolSessionToken(qrSession)));
    Map<String, Object> data =
        Boolean.TRUE.equals(result.get("ok"))
            ? asMap(result.get("data"))
            : new LinkedHashMap<String, Object>();
    data.remove("credential_json");
    data.remove("session_token");

    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("qrSessionId", qrSessionId);
    response.put("status", data.get("status"));
    response.put("updatedAt", data.get("updated_at"));
    response.put("expiresAt", data.get("expires_at"));
    response.put("imageBase64", data.get("image_base64"));
    response.put("imageMime", data.get("image_mime"));
    response.put("qrVersion", data.get("qr_version"));
    response.put(
        "error", Boolean.TRUE.equals(result.get("ok")) ? data.get("error") : result.get("error"));
    response.put("ok", false);
    response.put("authenticated", false);
    response.put("provider", BossAuthProviders.DISPLAY_PROVIDER);
    return response;
  }

  /**
   * 在当前属主锁内获取二维码登录状态。
   *
   * <p>同一二维码可被设置页、收藏导入和对话入口复用，必须串行轮询并轮换状态令牌，避免旧阶段覆盖新阶段。
   *
   * @param qrSessionId 二维码会话标识
   * @return 二维码登录状态
   */
  private Map<String, Object> qrLoginStatusLocked(String qrSessionId) {
    Map<String, Object> qrSession = requireQrOwner(qrSessionId);
    Map<String, Object> result =
        jsonCodec.toMap(bossCliService.qrStatus(qrSessionId, requiredToolSessionToken(qrSession)));
    Map<String, Object> data =
        Boolean.TRUE.equals(result.get("ok"))
            ? asMap(result.get("data"))
            : new LinkedHashMap<String, Object>();
    String credentialJson = trimToNull(stringValue(data.remove("credential_json")));
    String rotatedToken = trimToNull(stringValue(data.remove("session_token")));
    if (rotatedToken != null) {
      authStateRepository.updateQrSessionToken(
          currentTenantId(),
          currentUserId(),
          qrSessionId,
          rotatedToken,
          intValue(qrSession.get("toolSessionVersion")));
    }

    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("qrSessionId", qrSessionId);
    response.put("status", data.get("status"));
    response.put("updatedAt", data.get("updated_at"));
    response.put("expiresAt", data.get("expires_at"));
    response.put("imageBase64", data.get("image_base64"));
    response.put("imageMime", data.get("image_mime"));
    response.put("qrVersion", data.get("qr_version"));
    response.put(
        "error", Boolean.TRUE.equals(result.get("ok")) ? data.get("error") : result.get("error"));
    response.put("ok", "logged_in".equals(String.valueOf(data.get("status"))));
    response.put("provider", BossAuthProviders.DISPLAY_PROVIDER);

    if (Boolean.TRUE.equals(response.get("ok"))) {
      if (credentialJson == null) {
        throw new IllegalStateException("Boss 扫码成功但未返回可持久化凭据");
      }
      authStateRepository.save(
          currentTenantId(),
          currentUserId(),
          BossAuthProviders.STORAGE_PROVIDER,
          "logged_in",
          credentialJson,
          metadata(response));
      markAuthenticated();
      authStateRepository.deleteQrSession(currentTenantId(), currentUserId(), qrSessionId);
    } else if ("expired".equals(String.valueOf(response.get("status")))
        || "error".equals(String.valueOf(response.get("status")))) {
      clearAuthenticatedCache(String.valueOf(response.get("status")));
      authStateRepository.updateStatus(
          BossAuthProviders.STORAGE_PROVIDER,
          String.valueOf(response.get("status")),
          metadata(response));
      authStateRepository.deleteQrSession(currentTenantId(), currentUserId(), qrSessionId);
    }
    return response;
  }

  /**
   * 校验登录状态。
   *
   * @param force 是否强制执行
   * @return 校验后的登录状态
   */
  private Map<String, Object> validateLoginState(boolean force) {
    if (!force && isCachedAuthenticated()) return loggedInResponse(true, "Boss 登录态缓存有效。");
    String owner = scopeKey();
    Object lock = authStatusLocks.computeIfAbsent(owner, ignored -> new Object());
    synchronized (lock) {
      if (!force && isCachedAuthenticated()) return loggedInResponse(true, "Boss 登录态缓存有效。");
      Map<String, Object> status = jsonCodec.toMap(bossCliService.status());
      if (isStatusCheckFailure(status)) throw new RuntimeException("Boss 登录态暂时无法校验，请稍后重试。");
      if (isStatusAuthenticated(status)) {
        markAuthenticated();
        authStateRepository.updateStatus(
            BossAuthProviders.STORAGE_PROVIDER, "logged_in", metadata(status));
      } else {
        clearAuthenticatedCache("auth_required");
        authStateRepository.updateStatus(
            BossAuthProviders.STORAGE_PROVIDER, "auth_required", metadata(status));
      }
      return status;
    }
  }

  /**
   * 获取二维码会话标识用于属主。
   *
   * @return 二维码会话标识用于属主
   */
  private String qrSessionIdForOwner() {
    Map<String, Object> row =
        authStateRepository.findActiveQrSession(currentTenantId(), currentUserId());
    return row == null ? null : trimToNull(stringValue(row.get("qrSessionId")));
  }

  /**
   * 校验并获取二维码属主。
   *
   * @param qrSessionId 二维码会话标识
   * @return 校验后的并获取二维码属主
   */
  private Map<String, Object> requireQrOwner(String qrSessionId) {
    Map<String, Object> owner = authStateRepository.findQrSession(qrSessionId);
    if (owner == null) throw new IllegalArgumentException("Boss 登录会话不存在或已过期");
    if (!currentTenantId().equals(stringValue(owner.get("tenantId")))
        || !currentUserId().equals(stringValue(owner.get("userId")))) {
      throw new IllegalArgumentException("无权访问该 Boss 登录会话");
    }
    Instant expiresAt = toInstant(owner.get("expiresAt"));
    if (expiresAt == null || !expiresAt.isAfter(Instant.now())) {
      authStateRepository.deleteQrSession(currentTenantId(), currentUserId(), qrSessionId);
      throw new IllegalArgumentException("Boss 登录会话不存在或已过期");
    }
    return owner;
  }

  /**
   * 校验并获取工具会话令牌。
   *
   * @param session 会话
   * @return 校验后的并获取工具会话令牌
   */
  private String requiredToolSessionToken(Map<String, Object> session) {
    String value = trimToNull(stringValue(session.get("toolSessionToken")));
    if (value == null) throw new IllegalStateException("Boss 登录会话缺少工具状态令牌");
    return value;
  }

  /**
   * 获取整数值。
   *
   * @param value 输入值
   * @return 整数值
   */
  private int intValue(Object value) {
    return value instanceof Number ? ((Number) value).intValue() : 0;
  }

  /**
   * 判断缓存凭据是否有效。
   *
   * @return 缓存凭据是否有效是否成立
   */
  private boolean isCachedAuthenticated() {
    AuthCacheEntry entry = authCache.get(scopeKey());
    if (entry == null
        || !entry.authenticated
        || System.currentTimeMillis() - entry.authenticatedAt > authCacheTtlMillis()) {
      if (entry != null) authCache.remove(scopeKey(), entry);
      return false;
    }
    return true;
  }

  /**
   * 标记已认证用户。
   */
  private void markAuthenticated() {
    long now = System.currentTimeMillis();
    authCache.put(scopeKey(), new AuthCacheEntry(true, now, now, "logged_in"));
  }

  /**
   * 清理已认证用户缓存。
   *
   * @param status 状态
   */
  private void clearAuthenticatedCache(String status) {
    long now = System.currentTimeMillis();
    authCache.put(
        scopeKey(), new AuthCacheEntry(false, 0L, now, status == null ? "auth_required" : status));
  }

  /**
   * 构建已登录响应。
   *
   * @param cached 缓存数据
   * @param message 消息内容
   * @return 已登录响应
   */
  private Map<String, Object> loggedInResponse(boolean cached, String message) {
    AuthCacheEntry entry = authCache.get(scopeKey());
    Map<String, Object> response = new LinkedHashMap<String, Object>();
    response.put("authRequired", false);
    response.put("provider", BossAuthProviders.DISPLAY_PROVIDER);
    response.put("status", "logged_in");
    response.put("ok", true);
    response.put("authenticated", true);
    response.put("cached", cached);
    response.put("message", message);
    response.put("lastStatus", entry == null ? "unknown" : entry.status);
    response.put(
        "lastValidatedAt",
        entry == null || entry.validatedAt <= 0L
            ? null
            : Instant.ofEpochMilli(entry.validatedAt).toString());
    return response;
  }

  /**
   * 获取元数据。
   *
   * @param sourceValue 源值
   * @return 元数据
   */
  private Map<String, Object> metadata(Object sourceValue) {
    Map<String, Object> source = jsonCodec.toMap(sourceValue);
    AuthCacheEntry entry = authCache.get(scopeKey());
    Map<String, Object> metadata = new LinkedHashMap<String, Object>();
    metadata.put("provider", BossAuthProviders.STORAGE_PROVIDER);
    metadata.put("syncedAt", Instant.now().toString());
    metadata.put("lastStatus", entry == null ? "unknown" : entry.status);
    metadata.put(
        "lastValidatedAt",
        entry == null || entry.validatedAt <= 0L
            ? null
            : Instant.ofEpochMilli(entry.validatedAt).toString());
    if (source != null) {
      metadata.put("status", source.get("status"));
      metadata.put("ok", source.get("ok"));
      metadata.put("updatedAt", source.get("updatedAt"));
      metadata.put("expiresAt", source.get("expiresAt"));
      metadata.put("source", source.get("source"));
    }
    return metadata;
  }

  /**
   * 转换为映射。
   *
   * @param value 输入值
   * @return 转换后的键值映射
   */
  @SuppressWarnings("unchecked")
  private Map<String, Object> asMap(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
  }

  /**
   * 判断状态已认证用户。
   *
   * @param status 状态
   * @return 用户是否已认证
   */
  private boolean isStatusAuthenticated(Map<String, Object> status) {
    if (status == null || status.isEmpty()) return false;
    if (Boolean.TRUE.equals(status.get("ok")) || Boolean.TRUE.equals(status.get("authenticated")))
      return true;
    Object data = status.get("data");
    if (data instanceof Map) {
      Map map = (Map) data;
      return Boolean.TRUE.equals(map.get("authenticated"))
          || "logged_in".equals(String.valueOf(map.get("status")));
    }
    return "logged_in".equals(String.valueOf(status.get("status")));
  }

  /**
   * 判断是否为状态检查失败。
   *
   * @param status 状态
   * @return 是否为状态检查失败
   */
  private boolean isStatusCheckFailure(Map<String, Object> status) {
    return status == null
        || status.isEmpty()
        || "error".equals(String.valueOf(status.get("status")))
        || status.get("error") != null;
  }

  /**
   * 转换为时间点。
   *
   * @param value 输入值
   * @return 转换后的时间点
   */
  private Instant toInstant(Object value) {
    if (value instanceof Instant) return (Instant) value;
    if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toInstant();
    if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant();
    if (value == null) return null;
    try {
      return Instant.parse(String.valueOf(value));
    } catch (Exception ignored) {
      return null;
    }
  }

  /**
   * 获取认证缓存有效期毫秒数。
   *
   * @return 认证缓存有效期毫秒数
   */
  private long authCacheTtlMillis() {
    String value = System.getenv("BOSS_AUTH_STATUS_CACHE_TTL_MS");
    if (value != null && !value.trim().isEmpty()) {
      try {
        return Math.max(30 * 1000L, Long.parseLong(value.trim()));
      } catch (NumberFormatException ignored) {
        return DEFAULT_AUTH_CACHE_TTL_MILLIS;
      }
    }
    return DEFAULT_AUTH_CACHE_TTL_MILLIS;
  }

  /**
   * 获取当前租户标识。
   *
   * @return 当前租户标识
   */
  private String currentTenantId() {
    String value = AuthenticationScope.tenantId();
    if (value == null || value.trim().isEmpty())
      throw new IllegalStateException("Boss 认证缺少 tenantId");
    return value.trim();
  }

  /**
   * 获取当前用户标识。
   *
   * @return 当前用户标识
   */
  private String currentUserId() {
    String value = AuthenticationScope.userId();
    if (value == null || value.trim().isEmpty())
      throw new IllegalStateException("Boss 认证缺少 userId");
    return value.trim();
  }

  /**
   * 获取作用域键。
   *
   * @return 作用域键
   */
  private String scopeKey() {
    return currentTenantId() + "\u0000" + currentUserId();
  }

  /**
   * 获取字符串值。
   *
   * @param value 输入值
   * @return 字符串值
   */
  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /**
   * 裁剪目标空值。
   *
   * @param value 输入值
   * @return 规范化文本
   */
  private String trimToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  /**
   * 定义认证缓存条目。
   */
  private static final class AuthCacheEntry {
    private final boolean authenticated;
    private final long authenticatedAt;
    private final long validatedAt;
    private final String status;

    /**
     * 创建认证缓存条目实例。
     *
     * @param authenticated 是否已认证
     * @param authenticatedAt 认证时间
     * @param validatedAt 校验时间
     * @param status 状态
     */
    private AuthCacheEntry(
        boolean authenticated, long authenticatedAt, long validatedAt, String status) {
      this.authenticated = authenticated;
      this.authenticatedAt = authenticatedAt;
      this.validatedAt = validatedAt;
      this.status = status;
    }
  }
}
