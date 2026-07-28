package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.mapper.AuthStateMapper;
import com.jobbuddy.backend.modules.auth.repository.AuthStateRepository;
import com.jobbuddy.backend.modules.auth.security.BossCredentialCipher;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证 AuthStateRepositoryEncryption 的核心行为、异常路径与边界条件。
 */
class AuthStateRepositoryEncryptionTest {

  /**
   * 验证 AuthStateRepositoryEncryption 中凭据的安全保护边界。
   */
  @Test
  void saveMustWriteCiphertextInsteadOfCredentialJson() {
    AuthStateMapper mapper = mock(AuthStateMapper.class);
    when(mapper.countByProvider("tenant-a", "user-a", "jackwener/boss-cli")).thenReturn(0);
    AuthStateRepository repository = repository(mapper, propertiesWithKey());
    ArgumentCaptor<Map<String, Object>> state = mapCaptor();

    repository.save(
        "tenant-a",
        "user-a",
        "jackwener/boss-cli",
        "logged_in",
        "{\"cookies\":{\"wt2\":\"secret\"}}",
        Collections.<String, Object>emptyMap());

    verify(mapper).insertState(state.capture());
    String stored = String.valueOf(state.getValue().get("credentialJson"));
    assertTrue(stored.startsWith("enc:v1:"));
    assertTrue(!stored.contains("secret"));
  }

  /**
   * 验证 AuthStateRepositoryEncryption 中凭据的安全保护边界。
   */
  @Test
  void plaintextCredentialMustBeRejected() {
    AuthStateMapper mapper = mock(AuthStateMapper.class);
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("credentialJson", "{\"cookies\":{}}");
    row.put("metadataJson", "{}");
    when(mapper.findByProvider("tenant-a", "user-a", "jackwener/boss-cli")).thenReturn(row);
    AuthStateRepository repository = repository(mapper, propertiesWithKey());

    assertThrows(
        IllegalStateException.class,
        () -> repository.findByProvider("tenant-a", "user-a", "jackwener/boss-cli"));
  }

  /**
   * 验证二维码会话令牌更新必须携带当前版本，避免旧轮询覆盖较新的登录阶段。
   */
  @Test
  void qrSessionTokenRotationMustUseCompareAndSetVersion() {
    AuthStateMapper mapper = mock(AuthStateMapper.class);
    when(mapper.updateQrSessionToken(
            eq("qr-a"), eq("tenant-a"), eq("user-a"), eq("rotated-token"), eq(3), eq(4), any()))
        .thenReturn(1);
    AuthStateRepository repository = repository(mapper, propertiesWithKey());

    repository.updateQrSessionToken("tenant-a", "user-a", "qr-a", "rotated-token", 3);

    verify(mapper)
        .updateQrSessionToken(
            eq("qr-a"), eq("tenant-a"), eq("user-a"), eq("rotated-token"), eq(3), eq(4), any());
  }

  /**
   * 验证存储访问。
   *
   * @param mapper 数据映射
   * @param properties 配置属性
   * @return 使用测试密钥的认证状态仓库
   */
  private AuthStateRepository repository(AuthStateMapper mapper, JobBuddyProperties properties) {
    return new AuthStateRepository(mapper, new JsonCodec(), new BossCredentialCipher(properties));
  }

  /**
   * 验证配置属性带有键。
   *
   * @return 带测试密钥的配置
   */
  private JobBuddyProperties propertiesWithKey() {
    JobBuddyProperties properties = new JobBuddyProperties();
    byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    properties.getAuth().setBossCredentialEncryptionKey(Base64.getEncoder().encodeToString(key));
    return properties;
  }

  /**
   * 创建映射参数捕获器。
   *
   * @return 映射参数捕获器
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private ArgumentCaptor<Map<String, Object>> mapCaptor() {
    return (ArgumentCaptor) ArgumentCaptor.forClass(Map.class);
  }
}
