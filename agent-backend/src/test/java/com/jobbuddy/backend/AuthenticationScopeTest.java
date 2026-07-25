package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jobbuddy.backend.common.security.AuthenticationScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * 验证 AuthenticationScope 的核心行为、异常路径与边界条件。
 */
class AuthenticationScopeTest {

  /**
   * 清理请求级认证作用域。
   */
  @AfterEach
  void clearScope() {
    AuthenticationScope.clear();
  }

  /**
   * 验证 AuthenticationScope 的权限与租户隔离边界。
   */
  @Test
  void missingContextMustNotFallbackToAdministrator() {
    assertThrows(IllegalStateException.class, AuthenticationScope::tenantId);
    assertThrows(IllegalStateException.class, AuthenticationScope::userId);
  }

  /**
   * 验证 AuthenticationScope 的权限与租户隔离边界。
   */
  @Test
  void blankOwnerFieldsMustBeRejected() {
    assertThrows(IllegalArgumentException.class, () -> AuthenticationScope.set("", "user-a"));
    assertThrows(IllegalArgumentException.class, () -> AuthenticationScope.set("tenant-a", " "));
  }

  /**
   * 验证 AuthenticationScope 的权限与租户隔离边界。
   */
  @Test
  void explicitOwnerIsAvailableUntilCleared() {
    AuthenticationScope.set(" tenant-a ", " user-a ");
    assertEquals("tenant-a", AuthenticationScope.tenantId());
    assertEquals("user-a", AuthenticationScope.userId());
  }
}
