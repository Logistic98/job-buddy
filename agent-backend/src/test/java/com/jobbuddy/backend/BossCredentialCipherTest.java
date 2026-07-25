package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.auth.security.BossCredentialCipher;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import org.junit.jupiter.api.Test;

/**
 * 验证 BossCredentialCipher 的核心行为、异常路径与边界条件。
 */
class BossCredentialCipherTest {

  /**
   * 验证 BossCredentialCipher 中凭据的权限与租户隔离边界。
   */
  @Test
  void encryptsCredentialWithRandomNonceAndOwnerBoundAad() {
    BossCredentialCipher cipher = cipherWithKey();
    String credential = "{\"cookies\":{\"wt2\":\"secret\"}}";

    String first = cipher.encrypt(credential, "tenant-a", "user-a", "jackwener/boss-cli");
    String second = cipher.encrypt(credential, "tenant-a", "user-a", "jackwener/boss-cli");

    assertTrue(first.startsWith("enc:v1:"));
    assertNotEquals(first, second);
    assertEquals(credential, cipher.decrypt(first, "tenant-a", "user-a", "jackwener/boss-cli"));
    assertThrows(
        IllegalStateException.class,
        () -> cipher.decrypt(first, "tenant-a", "user-b", "jackwener/boss-cli"));
  }

  /**
   * 验证 BossCredentialCipher 中凭据的输入校验与拒绝边界。
   */
  @Test
  void refusesCredentialPersistenceWithoutExplicitKey() {
    BossCredentialCipher cipher = new BossCredentialCipher(new JobBuddyProperties());

    assertThrows(
        IllegalStateException.class,
        () -> cipher.encrypt("{\"cookies\":{}}", "tenant-a", "user-a", "jackwener/boss-cli"));
    assertThrows(
        IllegalStateException.class,
        () -> cipher.decrypt("{\"cookies\":{}}", "tenant-a", "user-a", "jackwener/boss-cli"));
  }

  /**
   * 创建使用指定密钥的凭据加密器。
   *
   * @return 带测试密钥的凭据加密器
   */
  private BossCredentialCipher cipherWithKey() {
    JobBuddyProperties properties = new JobBuddyProperties();
    byte[] key = "0123456789abcdef0123456789abcdef".getBytes(StandardCharsets.UTF_8);
    properties.getAuth().setBossCredentialEncryptionKey(Base64.getEncoder().encodeToString(key));
    return new BossCredentialCipher(properties);
  }
}
