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

  public BossAuthServiceImpl(
      BossCliService bossCliService, AuthStateRepository authStateRepository) {
    this.bossCliService = bossCliService;
    this.authStateRepository = authStateRepository;
  }

  public BossLoginStatusResponse loginPrompt() {
    Map<String, Object> prompt = new LinkedHashMap<String, Object>();
    prompt.put("authRequired", true);
    prompt.put("provider", BossAuthProviders.DISPLAY_PROVIDER);
    prompt.put("message", "Boss 直聘未登录，请在弹窗中扫码完成登录。");
    return jsonCodec.convert(prompt, BossLoginStatusResponse.class);
  }

  public BossLoginQrResponse startQrLogin(String sessionId) {
    if (isLoggedIn(sessionId))
      return jsonCodec.convert(loggedInResponse(true, "Boss 登录态有效。"), BossLoginQrResponse.class);
    String activeQrSessionId = qrSessionIdForOwner();
    if (activeQrSessionId != null) {
      requireQrOwner(activeQrSessionId);
      Map<String, Object> active = qrLoginStatus(activeQrSessionId);
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
      Instant expiresAt = Instant.now().plus(QR_SESSION_TTL_MINUTES, ChronoUnit.MINUTES);
      authStateRepository.saveQrSession(
          currentTenantId(), currentUserId(), sessionId, qrSessionId, expiresAt);
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

  public BossLoginStatusResponse loginStatus(String sessionId, String qrSessionIdOverride) {
    if (isCachedAuthenticated())
      return jsonCodec.convert(
          loggedInResponse(true, "Boss 登录态缓存有效。"), BossLoginStatusResponse.class);
    String qrSessionId = trimToNull(qrSessionIdOverride);
    if (qrSessionId == null) qrSessionId = qrSessionIdForOwner();
    if (qrSessionId != null) {
      requireQrOwner(qrSessionId);
      return jsonCodec.convert(qrLoginStatus(qrSessionId), BossLoginStatusResponse.class);
    }
    return jsonCodec.convert(validateLoginState(false), BossLoginStatusResponse.class);
  }

  public BossLoginCancelResponse cancelLogin(String sessionId, String qrSessionIdOverride) {
    String qrSessionId = trimToNull(qrSessionIdOverride);
    if (qrSessionId == null) qrSessionId = qrSessionIdForOwner();
    if (qrSessionId == null)
      return jsonCodec.convert(bossCliService.cancelLogin(), BossLoginCancelResponse.class);
    requireQrOwner(qrSessionId);
    authStateRepository.deleteQrSession(currentTenantId(), currentUserId(), qrSessionId);
    return jsonCodec.convert(bossCliService.qrCancel(qrSessionId), BossLoginCancelResponse.class);
  }

  public boolean isLoggedIn(String sessionId) {
    if (isCachedAuthenticated()) return true;
    return isStatusAuthenticated(validateLoginState(false));
  }

  /**
   * Search/detail success only refreshes the current owner's status; the existing encrypted
   * credential is preserved.
   */
  public void rememberCurrentCredential(JsonNode source) {
    markAuthenticated();
    authStateRepository.updateStatus(
        BossAuthProviders.STORAGE_PROVIDER, "logged_in", metadata(source));
  }

  public void markLoginInvalid(JsonNode source) {
    clearAuthenticatedCache("auth_required");
    authStateRepository.updateStatus(
        BossAuthProviders.STORAGE_PROVIDER, "auth_required", metadata(source));
  }

  @EventListener
  public void onBossAuthLost(BossAuthLostEvent event) {
    clearAuthenticatedCache("auth_required");
  }

  public void requireLoginOrThrow(String sessionId) {
    if (isLoggedIn(sessionId)) return;
    throw new BossAuthRequiredException("Boss 直聘未登录，请先完成二维码登录。", jsonCodec.toMap(loginPrompt()));
  }

  private Map<String, Object> qrLoginStatus(String qrSessionId) {
    Map<String, Object> result = jsonCodec.toMap(bossCliService.qrStatus(qrSessionId));
    Map<String, Object> data =
        Boolean.TRUE.equals(result.get("ok"))
            ? asMap(result.get("data"))
            : new LinkedHashMap<String, Object>();
    String credentialJson = trimToNull(stringValue(data.remove("credential_json")));

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

  private String qrSessionIdForOwner() {
    Map<String, Object> row =
        authStateRepository.findActiveQrSession(currentTenantId(), currentUserId());
    return row == null ? null : trimToNull(stringValue(row.get("qrSessionId")));
  }

  private void requireQrOwner(String qrSessionId) {
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
  }

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

  private void markAuthenticated() {
    long now = System.currentTimeMillis();
    authCache.put(scopeKey(), new AuthCacheEntry(true, now, now, "logged_in"));
  }

  private void clearAuthenticatedCache(String status) {
    long now = System.currentTimeMillis();
    authCache.put(
        scopeKey(), new AuthCacheEntry(false, 0L, now, status == null ? "auth_required" : status));
  }

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

  @SuppressWarnings("unchecked")
  private Map<String, Object> asMap(Object value) {
    return value instanceof Map ? (Map<String, Object>) value : new LinkedHashMap<String, Object>();
  }

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

  private boolean isStatusCheckFailure(Map<String, Object> status) {
    return status == null
        || status.isEmpty()
        || "error".equals(String.valueOf(status.get("status")))
        || status.get("error") != null;
  }

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

  private String currentTenantId() {
    String value = AuthenticationScope.tenantId();
    if (value == null || value.trim().isEmpty())
      throw new IllegalStateException("Boss 认证缺少 tenantId");
    return value.trim();
  }

  private String currentUserId() {
    String value = AuthenticationScope.userId();
    if (value == null || value.trim().isEmpty())
      throw new IllegalStateException("Boss 认证缺少 userId");
    return value.trim();
  }

  private String scopeKey() {
    return currentTenantId() + "\u0000" + currentUserId();
  }

  private String stringValue(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private String trimToNull(String value) {
    return value == null || value.trim().isEmpty() ? null : value.trim();
  }

  private static final class AuthCacheEntry {
    private final boolean authenticated;
    private final long authenticatedAt;
    private final long validatedAt;
    private final String status;

    private AuthCacheEntry(
        boolean authenticated, long authenticatedAt, long validatedAt, String status) {
      this.authenticated = authenticated;
      this.authenticatedAt = authenticatedAt;
      this.validatedAt = validatedAt;
      this.status = status;
    }
  }
}
