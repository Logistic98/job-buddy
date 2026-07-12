package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.jobbuddy.backend.common.security.AuthenticationScope;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class AuthenticationScopeTest {

  @AfterEach
  void clearScope() {
    AuthenticationScope.clear();
  }

  @Test
  void missingContextMustNotFallbackToAdministrator() {
    assertThrows(IllegalStateException.class, AuthenticationScope::tenantId);
    assertThrows(IllegalStateException.class, AuthenticationScope::userId);
  }

  @Test
  void blankOwnerFieldsMustBeRejected() {
    assertThrows(IllegalArgumentException.class, () -> AuthenticationScope.set("", "user-a"));
    assertThrows(IllegalArgumentException.class, () -> AuthenticationScope.set("tenant-a", " "));
  }

  @Test
  void explicitOwnerIsAvailableUntilCleared() {
    AuthenticationScope.set(" tenant-a ", " user-a ");
    assertEquals("tenant-a", AuthenticationScope.tenantId());
    assertEquals("user-a", AuthenticationScope.userId());
  }
}
