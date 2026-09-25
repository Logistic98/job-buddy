package com.jobbuddy.backend.modules.auth.controller;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.jobbuddy.backend.common.security.RequirePermission;
import org.junit.jupiter.api.Test;

class BossAuthControllerPermissionTest {
  @Test
  void bossAuthenticationIsAvailableToEveryAuthenticatedUser() {
    assertNull(BossAuthController.class.getAnnotation(RequirePermission.class));
  }
}
