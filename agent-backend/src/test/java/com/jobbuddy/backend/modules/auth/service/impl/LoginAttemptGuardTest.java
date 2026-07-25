package com.jobbuddy.backend.modules.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jobbuddy.backend.modules.auth.exception.LoginRateLimitException;
import java.time.Clock;
import org.junit.jupiter.api.Test;

/**
 * 验证 LoginAttemptGuard 的核心行为、异常路径与边界条件。
 */
class LoginAttemptGuardTest {

  /**
   * 验证 LoginAttemptGuard 的去重与幂等边界。
   */
  @Test
  void limitsRepeatedAttemptsPerAccountAndClearsAccountWindowAfterSuccess() {
    LoginAttemptGuard guard = new LoginAttemptGuard(null, Clock.systemUTC(), 300L, 2, 20, 8);

    acquireAndClose(guard, "same-user", "127.0.0.1");
    acquireAndClose(guard, "same-user", "127.0.0.1");
    assertThrows(
        LoginRateLimitException.class, () -> acquireAndClose(guard, "same-user", "127.0.0.1"));

    guard.recordSuccess("same-user");
    assertDoesNotThrow(() -> acquireAndClose(guard, "same-user", "127.0.0.1"));
  }

  /**
   * 验证 LoginAttemptGuard 中用户的数量、长度与分页边界。
   */
  @Test
  void limitsDistributedUsernameAttemptsFromOneSource() {
    LoginAttemptGuard guard = new LoginAttemptGuard(null, Clock.systemUTC(), 300L, 20, 2, 8);

    acquireAndClose(guard, "user-a", "192.0.2.1");
    acquireAndClose(guard, "user-b", "192.0.2.1");

    assertThrows(
        LoginRateLimitException.class, () -> acquireAndClose(guard, "user-c", "192.0.2.1"));
  }

  /**
   * 验证获取并关闭。
   *
   * @param guard 守卫
   * @param account 账号
   * @param source 源数据
   */
  private void acquireAndClose(LoginAttemptGuard guard, String account, String source) {
    try (LoginAttemptGuard.AttemptLease ignored = guard.acquire(account, source)) {
      // 本用例只验证租约获取行为，关闭由 try-with-resources 负责。
    }
  }
}
