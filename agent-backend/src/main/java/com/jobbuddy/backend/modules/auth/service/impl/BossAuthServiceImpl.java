package com.jobbuddy.backend.modules.auth.service.impl;

import com.jobbuddy.backend.modules.auth.event.BossAuthLostEvent;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.auth.repository.AuthStateRepository;
import com.jobbuddy.backend.modules.auth.service.BossAuthService;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class BossAuthServiceImpl implements BossAuthService {
    private static final String PROVIDER = "jackwener/boss-cli";
    private static final String BOSS_PROVIDER = "boss-zhipin";
    private static final long DEFAULT_AUTH_CACHE_TTL_MILLIS = 5 * 60 * 1000L;

    private final BossCliService bossCliService;
    private final AuthStateRepository authStateRepository;
    private final Map<String, String> sessionToQr = new ConcurrentHashMap<String, String>();
    private final Object authStatusLock = new Object();

    private volatile boolean cachedAuthenticated = false;
    private volatile long lastAuthenticatedAt = 0L;
    private volatile long lastValidationAt = 0L;
    private volatile String lastStatus = "unknown";

    public BossAuthServiceImpl(BossCliService bossCliService, AuthStateRepository authStateRepository) {
        this.bossCliService = bossCliService;
        this.authStateRepository = authStateRepository;
    }

    @PostConstruct
    public void restorePersistedLoginState() {
        Map<String, Object> saved = findSavedState();
        if (saved == null) return;
        String credentialJson = stringValue(saved.get("credentialJson"));
        if (credentialJson != null && !credentialJson.trim().isEmpty()) {
            bossCliService.writeCredential(credentialJson);
            lastStatus = stringValue(saved.get("status")) == null ? "restored" : stringValue(saved.get("status"));
        }
    }

    /** 定期轻量校验已有登录态，减少无效外部请求。 */
    @Scheduled(fixedDelayString = "${job-buddy.boss-auth.validation-delay-ms:300000}", initialDelayString = "${job-buddy.boss-auth.validation-initial-delay-ms:60000}")
    public void validateLoginStatePeriodically() {
        if (!bossCliService.hasLocalCredential() && !cachedAuthenticated) return;
        if (!isValidationDue()) return;
        try {
            validateLoginState(null, true);
        } catch (RuntimeException ignored) {
            markLoginInvalid(authFailureSource("periodic_validation"));
        }
    }

    public Map<String, Object> loginPrompt() {
        Map<String, Object> prompt = new LinkedHashMap<String, Object>();
        prompt.put("authRequired", true);
        prompt.put("provider", BOSS_PROVIDER);
        prompt.put("message", "Boss 直聘未登录，请在弹窗中扫码完成登录。");
        return prompt;
    }

    public Map<String, Object> startQrLogin(String sessionId) {
        if (isLoggedIn(sessionId)) {
            return loggedInResponse(true, "Boss 登录态有效。");
        }
        Map<String, Object> start = bossCliService.qrStart();
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("authRequired", true);
        response.put("provider", BOSS_PROVIDER);
        response.put("message", "请使用 Boss 直聘 App 扫描二维码完成登录。");
        if (Boolean.TRUE.equals(start.get("ok"))) {
            Map<String, Object> data = asMap(start.get("data"));
            String qrSessionId = stringValue(data.get("session_id"));
            if (sessionId != null && qrSessionId != null) sessionToQr.put(sessionId, qrSessionId);
            response.put("qrSessionId", qrSessionId);
            response.put("qrId", data.get("qr_id"));
            response.put("imageBase64", data.get("image_base64"));
            response.put("imageMime", data.get("image_mime"));
            response.put("expiresAt", data.get("expires_at"));
            response.put("status", data.get("status"));
        } else {
            response.put("status", "error");
            response.put("error", start.get("error"));
        }
        return response;
    }

    public Map<String, Object> loginStatus(String sessionId, String qrSessionIdOverride) {
        String qrSessionId = qrSessionIdOverride != null && !qrSessionIdOverride.isEmpty()
                ? qrSessionIdOverride
                : (sessionId == null ? null : sessionToQr.get(sessionId));
        if (qrSessionId != null) {
            return qrLoginStatus(sessionId, qrSessionId);
        }
        if (isCachedAuthenticated()) {
            return loggedInResponse(true, "Boss 登录态缓存有效。");
        }
        return validateLoginState(sessionId, false);
    }

    public Map<String, Object> cancelLogin(String sessionId, String qrSessionIdOverride) {
        String qrSessionId = qrSessionIdOverride != null && !qrSessionIdOverride.isEmpty()
                ? qrSessionIdOverride
                : (sessionId == null ? null : sessionToQr.remove(sessionId));
        if (qrSessionId == null) return bossCliService.cancelLogin();
        return bossCliService.qrCancel(qrSessionId);
    }

    public boolean isLoggedIn(String sessionId) {
        if (isCachedAuthenticated()) return true;
        Map<String, Object> status = validateLoginState(sessionId, false);
        return isStatusAuthenticated(status);
    }

    public void rememberCurrentCredential(Map<String, Object> source) {
        markAuthenticated();
        persistCurrentCredential("logged_in", source);
    }

    public void markLoginInvalid(Map<String, Object> source) {
        clearAuthenticatedCache("auth_required");
        authStateRepository.updateStatus(PROVIDER, "auth_required", metadata(source));
    }

    /**
     * 监听底层能力层广播的登录态失效事件（Boss 工具返回 4001 时发布）。
     * 仅清空内存缓存，不再回写仓库状态，避免与触发方的清理逻辑重复落库。
     */
    @EventListener
    public void onBossAuthLost(BossAuthLostEvent event) {
        clearAuthenticatedCache("auth_required");
    }

    public void requireLoginOrThrow(String sessionId) {
        if (isLoggedIn(sessionId)) return;
        throw new BossAuthRequiredException("Boss 直聘未登录，请先完成二维码登录。", loginPrompt());
    }

    private Map<String, Object> qrLoginStatus(String sessionId, String qrSessionId) {
        Map<String, Object> result = bossCliService.qrStatus(qrSessionId);
        Map<String, Object> data = Boolean.TRUE.equals(result.get("ok")) ? asMap(result.get("data")) : new LinkedHashMap<String, Object>();
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("qrSessionId", qrSessionId);
        response.put("status", data.get("status"));
        response.put("updatedAt", data.get("updated_at"));
        response.put("expiresAt", data.get("expires_at"));
        response.put("imageBase64", data.get("image_base64"));
        response.put("imageMime", data.get("image_mime"));
        response.put("qrVersion", data.get("qr_version"));
        response.put("error", Boolean.TRUE.equals(result.get("ok")) ? data.get("error") : result.get("error"));
        response.put("ok", "logged_in".equals(String.valueOf(data.get("status"))));
        response.put("provider", BOSS_PROVIDER);
        if (Boolean.TRUE.equals(response.get("ok"))) {
            rememberCurrentCredential(response);
        } else if ("expired".equals(String.valueOf(response.get("status"))) || "error".equals(String.valueOf(response.get("status")))) {
            clearAuthenticatedCache(String.valueOf(response.get("status")));
            authStateRepository.updateStatus(PROVIDER, String.valueOf(response.get("status")), metadata(response));
        }
        if (Boolean.TRUE.equals(response.get("ok")) || "expired".equals(String.valueOf(response.get("status"))) || "error".equals(String.valueOf(response.get("status")))) {
            if (sessionId != null) sessionToQr.remove(sessionId);
        }
        return response;
    }

    private Map<String, Object> validateLoginState(String sessionId, boolean force) {
        restorePersistedCredentialIfNeeded();
        if (!force && isCachedAuthenticated()) return loggedInResponse(true, "Boss 登录态缓存有效。");
        synchronized (authStatusLock) {
            if (!force && isCachedAuthenticated()) return loggedInResponse(true, "Boss 登录态缓存有效。");
            Map<String, Object> status = bossCliService.status();
            boolean authenticated = isStatusAuthenticated(status);
            if (authenticated) {
                markAuthenticated();
                persistCurrentCredential("logged_in", status);
            } else {
                clearAuthenticatedCache("auth_required");
                authStateRepository.updateStatus(PROVIDER, "auth_required", metadata(status));
            }
            return status;
        }
    }

    private void restorePersistedCredentialIfNeeded() {
        if (bossCliService.hasLocalCredential()) return;
        Map<String, Object> saved = findSavedState();
        if (saved == null) return;
        String credentialJson = stringValue(saved.get("credentialJson"));
        if (credentialJson != null && !credentialJson.trim().isEmpty()) {
            bossCliService.writeCredential(credentialJson);
        }
    }

    private Map<String, Object> findSavedState() {
        try {
            return authStateRepository.findByProvider(PROVIDER);
        } catch (DataAccessException e) {
            return null;
        }
    }

    private boolean isCachedAuthenticated() {
        return cachedAuthenticated && System.currentTimeMillis() - lastAuthenticatedAt <= authCacheTtlMillis();
    }

    private boolean isValidationDue() {
        return System.currentTimeMillis() - lastValidationAt >= authCacheTtlMillis();
    }

    private void markAuthenticated() {
        long now = System.currentTimeMillis();
        cachedAuthenticated = true;
        lastAuthenticatedAt = now;
        lastValidationAt = now;
        lastStatus = "logged_in";
    }

    private void clearAuthenticatedCache(String status) {
        cachedAuthenticated = false;
        lastAuthenticatedAt = 0L;
        lastValidationAt = System.currentTimeMillis();
        lastStatus = status == null ? "auth_required" : status;
    }

    private Map<String, Object> loggedInResponse(boolean cached, String message) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        response.put("authRequired", false);
        response.put("provider", BOSS_PROVIDER);
        response.put("status", "logged_in");
        response.put("ok", true);
        response.put("authenticated", true);
        response.put("cached", cached);
        response.put("message", message);
        response.put("lastStatus", lastStatus);
        response.put("lastValidatedAt", lastValidationAt <= 0L ? null : Instant.ofEpochMilli(lastValidationAt).toString());
        return response;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) return (Map<String, Object>) value;
        return new LinkedHashMap<String, Object>();
    }

    private void persistCurrentCredential(String status, Map<String, Object> source) {
        String credentialJson = bossCliService.readCredentialJson();
        // 扫码成功落库时本地登录标记可能尚未生成：先物化一份 logged_in 标记，
        // 确保 credential_json 可靠落库、进程重启后可从库回填，避免“登录完下次又要登录”。
        if ((credentialJson == null || credentialJson.trim().isEmpty()) && "logged_in".equals(status)) {
            credentialJson = bossCliService.ensureLoginMarker();
        }
        if (credentialJson == null || credentialJson.trim().isEmpty()) {
            authStateRepository.updateStatus(PROVIDER, status, metadata(source));
            return;
        }
        authStateRepository.save(PROVIDER, status, credentialJson, metadata(source));
    }

    private boolean isStatusAuthenticated(Map<String, Object> status) {
        if (status == null || status.isEmpty()) return false;
        if (Boolean.TRUE.equals(status.get("ok")) || Boolean.TRUE.equals(status.get("authenticated"))) return true;
        Object data = status.get("data");
        if (data instanceof Map) {
            Map map = (Map) data;
            return Boolean.TRUE.equals(map.get("authenticated")) || "logged_in".equals(String.valueOf(map.get("status")));
        }
        return "logged_in".equals(String.valueOf(status.get("status")));
    }

    private Map<String, Object> metadata(Map<String, Object> source) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("provider", PROVIDER);
        metadata.put("syncedAt", Instant.now().toString());
        metadata.put("lastStatus", lastStatus);
        metadata.put("lastValidatedAt", lastValidationAt <= 0L ? null : Instant.ofEpochMilli(lastValidationAt).toString());
        metadata.put("credentialPersisted", bossCliService.hasLocalCredential());
        if (source != null) {
            metadata.put("status", source.get("status"));
            metadata.put("ok", source.get("ok"));
            metadata.put("updatedAt", source.get("updatedAt"));
            metadata.put("expiresAt", source.get("expiresAt"));
            metadata.put("source", source.get("source"));
        }
        return metadata;
    }

    private Map<String, Object> authFailureSource(String source) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("status", "auth_required");
        data.put("ok", false);
        data.put("source", source);
        return data;
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

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
