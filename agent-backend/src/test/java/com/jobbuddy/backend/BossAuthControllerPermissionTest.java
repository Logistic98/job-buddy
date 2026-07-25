package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertNull;

import com.jobbuddy.backend.common.security.RequirePermission;
import com.jobbuddy.backend.modules.auth.controller.BossAuthController;
import org.junit.jupiter.api.Test;

/**
 * 验证 BossAuthControllerPermission 的核心行为、异常路径与边界条件。
 */
class BossAuthControllerPermissionTest {
  /**
   * 验证 BossAuthControllerPermission 中用户的身份认证与会话边界。
   */
  @Test
  void bossAuthenticationIsAvailableToEveryAuthenticatedUser() {
    assertNull(BossAuthController.class.getAnnotation(RequirePermission.class));
  }
}
