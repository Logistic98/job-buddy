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

/**
 * 在类型化仓储边界内持久化加密的外部认证状态。
 *
 * <p>JSON 解码集中在此处，避免 Service 依赖 Mapper 行结构。
 */
@Repository
public class AuthStateRepository {
  private final AuthStateMapper mapper;
  private final JsonCodec jsonCodec;
  private final BossCredentialCipher credentialCipher;

  /**
   * 创建认证状态存储访问实例。
   *
   * @param mapper 数据映射
   * @param jsonCodec JSON 编解码器
   * @param credentialCipher 凭据加密器
   */
  public AuthStateRepository(
      AuthStateMapper mapper, JsonCodec jsonCodec, BossCredentialCipher credentialCipher) {
    this.mapper = mapper;
    this.jsonCodec = jsonCodec;
    this.credentialCipher = credentialCipher;
  }

  /**
   * 按提供方查询认证状态。
   *
   * @param provider 提供器
   * @return 通过提供器
   */
  public Map<String, Object> findByProvider(String provider) {
    return findByProvider(AuthenticationScope.tenantId(), AuthenticationScope.userId(), provider);
  }

  /**
   * 按提供方查询认证状态。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param provider 提供器
   * @return 通过提供器
   */
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

  /**
   * 保存认证状态存储访问。
   *
   * @param provider 提供器
   * @param status 状态
   * @param credentialJson 凭据 JSON
   * @param metadata 元数据
   */
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

  /**
   * 保存认证状态存储访问。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param provider 提供器
   * @param status 状态
   * @param credentialJson 凭据 JSON
   * @param metadata 元数据
   */
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

  /**
   * 更新状态。
   *
   * @param provider 提供器
   * @param status 状态
   * @param metadata 元数据
   */
  public void updateStatus(String provider, String status, Map<String, Object> metadata) {
    updateStatus(
        AuthenticationScope.tenantId(), AuthenticationScope.userId(), provider, status, metadata);
  }

  /**
   * 更新状态。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param provider 提供器
   * @param status 状态
   * @param metadata 元数据
   */
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

  /**
   * 清除指定提供方的持久化凭据并保留无敏感信息的状态记录。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param provider 提供器
   * @param status 状态
   * @param metadata 元数据
   * @return 是否清除了已有记录
   */
  public boolean clearCredential(
      String tenantId,
      String userId,
      String provider,
      String status,
      Map<String, Object> metadata) {
    requireOwner(tenantId, userId);
    if (provider == null || provider.trim().isEmpty())
      throw new IllegalArgumentException("provider 不能为空");
    return mapper.clearCredential(
            tenantId.trim(),
            userId.trim(),
            provider.trim(),
            status == null ? "logged_out" : status.trim(),
            jsonCodec.toJson(metadata),
            Instant.now())
        == 1;
  }

  /**
   * 保存二维码会话。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param chatSessionId 对话会话标识
   * @param qrSessionId 二维码会话标识
   * @param toolSessionToken 工具会话令牌
   * @param expiresAt 过期时间
   */
  public void saveQrSession(
      String tenantId,
      String userId,
      String chatSessionId,
      String qrSessionId,
      String toolSessionToken,
      Instant expiresAt) {
    requireOwner(tenantId, userId);
    if (qrSessionId == null || qrSessionId.trim().isEmpty())
      throw new IllegalArgumentException("qrSessionId 不能为空");
    if (expiresAt == null || !expiresAt.isAfter(Instant.now()))
      throw new IllegalArgumentException("Boss 二维码会话必须具有未来过期时间");
    if (toolSessionToken == null || toolSessionToken.trim().isEmpty())
      throw new IllegalArgumentException("Boss 二维码工具会话令牌不能为空");
    Instant now = Instant.now();
    Map<String, Object> row = new HashMap<String, Object>();
    row.put("tenantId", tenantId.trim());
    row.put("userId", userId.trim());
    row.put("chatSessionId", chatSessionId == null ? null : chatSessionId.trim());
    row.put("qrSessionId", qrSessionId.trim());
    row.put("toolSessionToken", toolSessionToken.trim());
    row.put("toolSessionVersion", 1);
    row.put("expiresAt", expiresAt);
    row.put("createdAt", now);
    row.put("updatedAt", now);
    mapper.deleteExpiredQrSessions(now);
    mapper.upsertQrSession(row);
  }

  /**
   * 更新二维码会话令牌。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param qrSessionId 二维码会话标识
   * @param toolSessionToken 工具会话令牌
   * @param currentVersion 当前版本
   */
  public void updateQrSessionToken(
      String tenantId,
      String userId,
      String qrSessionId,
      String toolSessionToken,
      int currentVersion) {
    requireOwner(tenantId, userId);
    if (toolSessionToken == null || toolSessionToken.trim().isEmpty()) return;
    int updated =
        mapper.updateQrSessionToken(
            qrSessionId.trim(),
            tenantId.trim(),
            userId.trim(),
            toolSessionToken.trim(),
            Math.max(1, currentVersion),
            Math.max(1, currentVersion + 1),
            Instant.now());
    if (updated != 1) throw new IllegalStateException("Boss 登录状态已由另一入口更新，请重新获取最新状态");
  }

  /**
   * 查找二维码会话。
   *
   * @param qrSessionId 二维码会话标识
   * @return 二维码会话
   */
  public Map<String, Object> findQrSession(String qrSessionId) {
    if (qrSessionId == null || qrSessionId.trim().isEmpty()) return null;
    return mapper.findQrSession(qrSessionId.trim());
  }

  /**
   * 查找活动二维码会话。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 活动二维码会话
   */
  public Map<String, Object> findActiveQrSession(String tenantId, String userId) {
    requireOwner(tenantId, userId);
    return mapper.findActiveQrSession(tenantId.trim(), userId.trim(), Instant.now());
  }

  /**
   * 按对话标识查询二维码会话。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param chatSessionId 对话会话标识
   * @return 二维码会话通过对话
   */
  public Map<String, Object> findQrSessionByChat(
      String tenantId, String userId, String chatSessionId) {
    requireOwner(tenantId, userId);
    if (chatSessionId == null || chatSessionId.trim().isEmpty()) return null;
    return mapper.findQrSessionByChat(
        tenantId.trim(), userId.trim(), chatSessionId.trim(), Instant.now());
  }

  /**
   * 删除二维码会话。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param qrSessionId 二维码会话标识
   * @return 是否删除了二维码会话
   */
  public boolean deleteQrSession(String tenantId, String userId, String qrSessionId) {
    requireOwner(tenantId, userId);
    if (qrSessionId == null || qrSessionId.trim().isEmpty()) return false;
    return mapper.deleteQrSession(qrSessionId.trim(), tenantId.trim(), userId.trim()) == 1;
  }

  /**
   * 删除指定属主的全部二维码会话。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 删除记录数
   */
  public int deleteQrSessionsForOwner(String tenantId, String userId) {
    requireOwner(tenantId, userId);
    return mapper.deleteQrSessionsForOwner(tenantId.trim(), userId.trim());
  }

  /**
   * 校验并获取属主。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   */
  private void requireOwner(String tenantId, String userId) {
    if (tenantId == null
        || tenantId.trim().isEmpty()
        || userId == null
        || userId.trim().isEmpty()) {
      throw new IllegalArgumentException("认证状态读写必须提供 tenantId 和 userId");
    }
  }

  /**
   * 将输入转换为字符串。
   *
   * @param value 待处理值
   * @return 字符串值
   */
  private String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }
}
