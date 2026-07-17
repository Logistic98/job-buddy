package com.jobbuddy.backend.modules.auth.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginCancelResponse;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginQrResponse;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginStatusResponse;

public interface BossAuthService {
  BossLoginStatusResponse loginPrompt();

  BossLoginQrResponse startQrLogin(String sessionId);

  BossLoginStatusResponse loginStatus(String sessionId, String qrSessionIdOverride);

  BossLoginCancelResponse cancelLogin(String sessionId, String qrSessionIdOverride);

  boolean isLoggedIn(String sessionId);

  void rememberCurrentCredential(JsonNode source);

  void markLoginInvalid(JsonNode source);

  void requireLoginOrThrow(String sessionId);
}
