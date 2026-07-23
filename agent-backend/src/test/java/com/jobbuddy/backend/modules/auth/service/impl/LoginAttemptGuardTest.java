package com.jobbuddy.backend.modules.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jobbuddy.backend.modules.auth.exception.LoginRateLimitException;
import java.time.Clock;
import org.junit.jupiter.api.Test;

class LoginAttemptGuardTest {

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

  @Test
  void limitsDistributedUsernameAttemptsFromOneSource() {
    LoginAttemptGuard guard = new LoginAttemptGuard(null, Clock.systemUTC(), 300L, 20, 2, 8);

    acquireAndClose(guard, "user-a", "192.0.2.1");
    acquireAndClose(guard, "user-b", "192.0.2.1");

    assertThrows(
        LoginRateLimitException.class, () -> acquireAndClose(guard, "user-c", "192.0.2.1"));
  }

  private void acquireAndClose(LoginAttemptGuard guard, String account, String source) {
    try (LoginAttemptGuard.AttemptLease ignored = guard.acquire(account, source)) {
      // Acquiring the lease is the behavior under test.
    }
  }
}
