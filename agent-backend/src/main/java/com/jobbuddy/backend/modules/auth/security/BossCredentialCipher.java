package com.jobbuddy.backend.modules.auth.security;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/**
 * 使用 AES-256-GCM 和行绑定关联数据加密持久化 Boss 凭据。
 */
@Component
public class BossCredentialCipher {
  private static final String PREFIX = "enc:v1:";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final JobBuddyProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();

  /**
   * 创建 Boss 凭据加密器实例。
   *
   * @param properties 配置属性
   */
  public BossCredentialCipher(JobBuddyProperties properties) {
    this.properties = properties;
  }

  /**
   * 加密 Boss 凭据。
   *
   * @param plaintext 凭据明文
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param provider 提供器
   * @return 密文
   */
  public String encrypt(String plaintext, String tenantId, String userId, String provider) {
    if (plaintext == null || plaintext.trim().isEmpty()) return null;
    try {
      byte[] iv = new byte[IV_BYTES];
      secureRandom.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad(tenantId, userId, provider));
      byte[] encrypted = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
      return PREFIX
          + Base64.getUrlEncoder().withoutPadding().encodeToString(iv)
          + ":"
          + Base64.getUrlEncoder().withoutPadding().encodeToString(encrypted);
    } catch (Exception e) {
      throw new IllegalStateException("Boss 凭据加密失败", e);
    }
  }

  /**
   * 解密 Boss 凭据。
   *
   * @param value 待处理值
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param provider 提供器
   * @return 凭据明文
   */
  public String decrypt(String value, String tenantId, String userId, String provider) {
    if (value == null || value.trim().isEmpty()) return null;
    if (!value.startsWith(PREFIX)) throw new IllegalStateException("Boss 凭据存储格式无效");
    try {
      String[] parts = value.split(":", 4);
      if (parts.length != 4) throw new IllegalArgumentException("无效的密文格式");
      byte[] iv = Base64.getUrlDecoder().decode(parts[2]);
      byte[] encrypted = Base64.getUrlDecoder().decode(parts[3]);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, iv));
      cipher.updateAAD(aad(tenantId, userId, provider));
      return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("Boss 凭据解密失败", e);
    }
  }

  /**
   * 读取或构造业务键。
   *
   * @return 业务键
   */
  private SecretKeySpec key() {
    String configured = configuredKey();
    if (configured == null || configured.isEmpty()) {
      throw new IllegalStateException("未配置 JOB_BUDDY_BOSS_CREDENTIAL_ENCRYPTION_KEY，Boss 凭据持久化已关闭");
    }
    try {
      byte[] decoded = Base64.getDecoder().decode(configured);
      if (decoded.length != 32) throw new IllegalArgumentException("密钥必须是 32 字节");
      return new SecretKeySpec(decoded, "AES");
    } catch (IllegalArgumentException e) {
      throw new IllegalStateException(
          "JOB_BUDDY_BOSS_CREDENTIAL_ENCRYPTION_KEY 必须是 32 字节密钥的 Base64 编码", e);
    }
  }

  /**
   * 构造凭据加密的附加认证数据。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param provider 提供器
   * @return 附加认证数据
   */
  private byte[] aad(String tenantId, String userId, String provider) {
    String value =
        required(tenantId, "tenantId")
            + "\u0000"
            + required(userId, "userId")
            + "\u0000"
            + required(provider, "provider");
    return value.getBytes(StandardCharsets.UTF_8);
  }

  /**
   * 读取并校验必填配置。
   *
   * @param value 待处理值
   * @param field 字段
   * @return 必填配置值
   */
  private String required(String value, String field) {
    if (value == null || value.trim().isEmpty())
      throw new IllegalArgumentException(field + " 不能为空");
    return value.trim();
  }

  /**
   * 解析配置的凭据加密密钥。
   *
   * @return 凭据加密密钥
   */
  private String configuredKey() {
    if (properties == null || properties.getAuth() == null) return null;
    String value = properties.getAuth().getBossCredentialEncryptionKey();
    return value == null ? null : value.trim();
  }
}
