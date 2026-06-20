package com.jobbuddy.backend.modules.auth.service;

import java.util.Map;

public interface BossAuthService {
    void restorePersistedLoginState();
    Map<String, Object> loginPrompt();
    Map<String, Object> startQrLogin(String sessionId);
    Map<String, Object> loginStatus(String sessionId, String qrSessionIdOverride);
    Map<String, Object> cancelLogin(String sessionId, String qrSessionIdOverride);
    boolean isLoggedIn(String sessionId);
    void rememberCurrentCredential(Map<String, Object> source);
    void markLoginInvalid(Map<String, Object> source);
    void requireLoginOrThrow(String sessionId);
}
