package com.jobbuddy.backend.modules.resume.service.impl;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * 简历附件资源的签名令牌工具：签发带过期时间的 HMAC 令牌，并在读取时校验签名、 归属用户与对象路径，避免对象存储路径直接暴露给前端。
 */
class ResumeAssetTokenSigner {

  static final java.util.Set<String> ALLOWED_ASSET_SUFFIXES =
      new java.util.HashSet<String>(java.util.Arrays.asList("jpg", "jpeg", "png", "webp"));

  private final JobBuddyProperties properties;
  private final JsonCodec jsonCodec;
  private final byte[] assetSigningKey;

  /**
   * 创建简历资源令牌签名器实例。
   *
   * @param properties 配置属性
   * @param jsonCodec JSON 编解码器
   */
  ResumeAssetTokenSigner(JobBuddyProperties properties, JsonCodec jsonCodec) {
    this.properties = properties;
    this.jsonCodec = jsonCodec;
    this.assetSigningKey = initAssetSigningKey(properties);
  }

  /**
   * 签发资源访问令牌。
   *
   * @param objectName 对象存储名称
   * @param userId 用户标识
   * @return 附件访问签名
   */
  String signAssetToken(String objectName, String userId) {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("objectName", objectName);
    payload.put("userId", userId);
    payload.put(
        "exp", Long.valueOf(Instant.now().plusSeconds(assetUrlTtlSeconds()).getEpochSecond()));
    String payloadJson = jsonCodec.toJson(payload);
    String encodedPayload = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
    String encodedSignature = base64Url(hmacSha256(encodedPayload));
    return encodedPayload + "." + encodedSignature;
  }

  /**
   * 校验并获取资源对象名称。
   *
   * @param token 认证令牌
   * @param userId 用户标识
   * @return 校验后的并获取资源对象名称
   */
  String requireAssetObjectName(String token, String userId) {
    if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("非法资源标识");
    String[] parts = token.split("\\.", -1);
    if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
      throw new IllegalArgumentException("非法资源标识");
    }
    byte[] expected = hmacSha256(parts[0]);
    byte[] actual;
    try {
      actual = Base64.getUrlDecoder().decode(parts[1]);
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("非法资源标识", e);
    }
    if (!MessageDigest.isEqual(expected, actual)) {
      throw new IllegalArgumentException("非法资源标识");
    }
    Map<String, Object> payload;
    try {
      payload =
          jsonCodec.toMap(
              new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
    } catch (IllegalArgumentException e) {
      throw new IllegalArgumentException("非法资源标识", e);
    }
    String effectiveUser = defaultUser(userId);
    String tokenUser = stringOf(payload.get("userId"));
    String objectName = stringOf(payload.get("objectName"));
    long exp = longValue(payload.get("exp"), 0L);
    if (exp < Instant.now().getEpochSecond()) throw new IllegalArgumentException("资源链接已过期");
    if (!effectiveUser.equals(tokenUser)) throw new IllegalArgumentException("无权访问该资源");
    String ownerPrefix = effectiveUser;
    if (!objectName.startsWith(ownerPrefix + "/assets/"))
      throw new IllegalArgumentException("非法资源路径");
    String suffix = extractSuffix(objectName);
    if (!ALLOWED_ASSET_SUFFIXES.contains(suffix)) throw new IllegalArgumentException("非法资源类型");
    return objectName;
  }

  /**
   * 提取令牌签名后缀。
   *
   * @param name 名称
   * @return 令牌签名后缀
   */
  static String extractSuffix(String name) {
    int idx = name.lastIndexOf('.');
    return idx <= 0 || idx == name.length() - 1 ? "" : name.substring(idx + 1).toLowerCase();
  }

  /**
   * 获取资源地址有效期秒数。
   *
   * @return 资源地址有效期秒数
   */
  private long assetUrlTtlSeconds() {
    return Math.max(60L, properties.getAuth().getAssetUrlTtlSeconds());
  }

  /**
   * 初始化资源签名密钥。
   *
   * @param properties 配置属性
   * @return 附件签名密钥
   */
  private byte[] initAssetSigningKey(JobBuddyProperties properties) {
    String configured = properties.getAuth().getAssetUrlSigningKey();
    if (configured != null && !configured.trim().isEmpty()) {
      return sha256Bytes(configured.trim());
    }
    String minioSecret = properties.getMinio() == null ? "" : properties.getMinio().getSecretKey();
    if (minioSecret != null && !minioSecret.trim().isEmpty() && !minioSecret.contains("${")) {
      return sha256Bytes(minioSecret.trim());
    }
    byte[] random = new byte[32];
    new SecureRandom().nextBytes(random);
    return random;
  }

  /**
   * 计算 HMAC-SHA-256 签名。
   *
   * @param value 输入值
   * @return HMAC-SHA-256 签名
   */
  private byte[] hmacSha256(String value) {
    try {
      Mac mac = Mac.getInstance("HmacSHA256");
      mac.init(new SecretKeySpec(assetSigningKey, "HmacSHA256"));
      return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("资源签名失败", e);
    }
  }

  /**
   * 计算 SHA-256 摘要字节。
   *
   * @param value 输入值
   * @return SHA-256 摘要字节
   */
  private byte[] sha256Bytes(String value) {
    try {
      return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (Exception e) {
      throw new IllegalStateException("资源签名密钥初始化失败", e);
    }
  }

  /**
   * 执行 Base64 URL 安全编码。
   *
   * @param bytes 字节数
   * @return URL 安全的 Base64 文本
   */
  private String base64Url(byte[] bytes) {
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }

  /**
   * 获取默认用户。
   *
   * @param userId 用户标识
   * @return 默认用户
   */
  private String defaultUser(String userId) {
    return (userId == null || userId.isEmpty()) ? properties.getDefaultUserId() : userId;
  }

  /**
   * 将输入值转换为字符串。
   *
   * @param value 输入值
   * @return 转换后的字符串
   */
  private String stringOf(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  /**
   * 获取长整型值。
   *
   * @param value 输入值
   * @param fallback 降级结果
   * @return 长整型值
   */
  private long longValue(Object value, long fallback) {
    if (value instanceof Number) return ((Number) value).longValue();
    if (value == null) return fallback;
    try {
      return Long.parseLong(String.valueOf(value));
    } catch (NumberFormatException e) {
      return fallback;
    }
  }
}
