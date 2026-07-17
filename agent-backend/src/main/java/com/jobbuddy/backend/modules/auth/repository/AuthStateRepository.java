package com.jobbuddy.backend.modules.auth.repository;

import com.jobbuddy.backend.common.security.AuthenticationScope;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.mapper.AuthStateMapper;
import com.jobbuddy.backend.modules.auth.security.BossCredentialCipher;
import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class AuthStateRepository {
  private final AuthStateMapper mapper;
  private final JsonCodec jsonCodec;
  private final BossCredentialCipher credentialCipher;

  public AuthStateRepository(
      AuthStateMapper mapper, JsonCodec jsonCodec, BossCredentialCipher credentialCipher) {
    this.mapper = mapper;
    this.jsonCodec = jsonCodec;
    this.credentialCipher = credentialCipher;
  }

  public Map<String, Object> findByProvider(String provider) {
    return findByProvider(AuthenticationScope.tenantId(), AuthenticationScope.userId(), provider);
  }

  public Map<String, Object> findByProvider(String tenantId, String userId, String provider) {
    Map<String, Object> row = mapper.findByProvider(tenantId, userId, provider);
    if (row == null) return null;
    Map<String, Object> result = new LinkedHashMap<String, Object>(row);
    String storedCredential = string(row.get("credentialJson"));
    String plaintext = credentialCipher.decrypt(storedCredential, tenantId, userId, provider);
    result.put("credentialJson", plaintext);
    result.put("metadata", jsonCodec.toMap(string(row.get("metadataJson"))));
    return result;
  }

  public void save(
      String provider, String status, String credentialJson, Map<String, Object> metadata) {
    save(
        AuthenticationScope.tenantId(),
        AuthenticationScope.userId(),
        provider,
        status,
        credentialJson,
        metadata);
  }

  public void save(
      String tenantId,
      String userId,
      String provider,
      String status,
      String credentialJson,
      Map<String, Object> metadata) {
    Map<String, Object> row = new HashMap<String, Object>();
    Instant now = Instant.now();
    requireOwner(tenantId, userId);
    row.put("tenantId", tenantId);
    row.put("userId", userId);
    row.put("provider", provider);
    row.put("status", status);
    row.put("credentialJson", credentialCipher.encrypt(credentialJson, tenantId, userId, provider));
    row.put("metadataJson", jsonCodec.toJson(metadata));
    row.put("createdAt", now);
    row.put("updatedAt", now);
    if (mapper.countByProvider(tenantId, userId, provider) > 0) mapper.updateState(row);
    else mapper.insertState(row);
  }

  public void updateStatus(String provider, String status, Map<String, Object> metadata) {
    updateStatus(
        AuthenticationScope.tenantId(), AuthenticationScope.userId(), provider, status, metadata);
  }

  public void updateStatus(
      String tenantId,
      String userId,
      String provider,
      String status,
      Map<String, Object> metadata) {
    Map<String, Object> existing = findByProvider(tenantId, userId, provider);
    save(
        tenantId,
        userId,
        provider,
        status,
        existing == null ? null : (String) existing.get("credentialJson"),
        metadata);
  }

  public void saveQrSession(
      String tenantId, String userId, String chatSessionId, String qrSessionId, Instant expiresAt) {
    requireOwner(tenantId, userId);
    if (qrSessionId == null || qrSessionId.trim().isEmpty())
      throw new IllegalArgumentException("qrSessionId 不能为空");
    if (expiresAt == null || !expiresAt.isAfter(Instant.now()))
      throw new IllegalArgumentException("Boss 二维码会话必须具有未来过期时间");
    Instant now = Instant.now();
    Map<String, Object> row = new HashMap<String, Object>();
    row.put("tenantId", tenantId.trim());
    row.put("userId", userId.trim());
    row.put("chatSessionId", chatSessionId == null ? null : chatSessionId.trim());
    row.put("qrSessionId", qrSessionId.trim());
    row.put("expiresAt", expiresAt);
    row.put("createdAt", now);
    row.put("updatedAt", now);
    mapper.deleteExpiredQrSessions(now);
    mapper.upsertQrSession(row);
  }

  public Map<String, Object> findQrSession(String qrSessionId) {
    if (qrSessionId == null || qrSessionId.trim().isEmpty()) return null;
    return mapper.findQrSession(qrSessionId.trim());
  }

  public Map<String, Object> findActiveQrSession(String tenantId, String userId) {
    requireOwner(tenantId, userId);
    return mapper.findActiveQrSession(tenantId.trim(), userId.trim(), Instant.now());
  }

  public Map<String, Object> findQrSessionByChat(
      String tenantId, String userId, String chatSessionId) {
    requireOwner(tenantId, userId);
    if (chatSessionId == null || chatSessionId.trim().isEmpty()) return null;
    return mapper.findQrSessionByChat(
        tenantId.trim(), userId.trim(), chatSessionId.trim(), Instant.now());
  }

  public boolean deleteQrSession(String tenantId, String userId, String qrSessionId) {
    requireOwner(tenantId, userId);
    if (qrSessionId == null || qrSessionId.trim().isEmpty()) return false;
    return mapper.deleteQrSession(qrSessionId.trim(), tenantId.trim(), userId.trim()) == 1;
  }

  private void requireOwner(String tenantId, String userId) {
    if (tenantId == null
        || tenantId.trim().isEmpty()
        || userId == null
        || userId.trim().isEmpty()) {
      throw new IllegalArgumentException("认证状态读写必须提供 tenantId 和 userId");
    }
  }

  private String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
