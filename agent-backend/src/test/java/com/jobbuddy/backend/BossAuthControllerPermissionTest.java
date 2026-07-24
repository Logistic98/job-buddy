package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.jobbuddy.backend.common.security.RequirePermission;
import com.jobbuddy.backend.modules.auth.controller.BossAuthController;
import org.junit.jupiter.api.Test;

class BossAuthControllerPermissionTest {
  @Test
  void bossAuthenticationIsAvailableToEveryAuthenticatedUser() {
    assertNull(BossAuthController.class.getAnnotation(RequirePermission.class));
  }
}
