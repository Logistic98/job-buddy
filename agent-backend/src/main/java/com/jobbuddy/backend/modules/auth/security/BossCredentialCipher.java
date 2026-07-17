package com.jobbuddy.backend.modules.auth.security;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;

/** Encrypts persisted Boss credentials with AES-256-GCM and row-bound associated data. */
@Component
public class BossCredentialCipher {
  private static final String PREFIX = "enc:v1:";
  private static final int IV_BYTES = 12;
  private static final int TAG_BITS = 128;

  private final JobBuddyProperties properties;
  private final SecureRandom secureRandom = new SecureRandom();

  public BossCredentialCipher(JobBuddyProperties properties) {
    this.properties = properties;
  }

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

  private byte[] aad(String tenantId, String userId, String provider) {
    String value =
        required(tenantId, "tenantId")
            + "\u0000"
            + required(userId, "userId")
            + "\u0000"
            + required(provider, "provider");
    return value.getBytes(StandardCharsets.UTF_8);
  }

  private String required(String value, String field) {
    if (value == null || value.trim().isEmpty())
      throw new IllegalArgumentException(field + " 不能为空");
    return value.trim();
  }

  private String configuredKey() {
    if (properties == null || properties.getAuth() == null) return null;
    String value = properties.getAuth().getBossCredentialEncryptionKey();
    return value == null ? null : value.trim();
  }
}
