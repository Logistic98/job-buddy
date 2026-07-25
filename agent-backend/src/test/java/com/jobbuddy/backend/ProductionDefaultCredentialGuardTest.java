package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import com.jobbuddy.backend.modules.auth.security.ProductionDefaultCredentialGuard;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

/**
 * 验证 ProductionDefaultCredentialGuard 的核心行为、异常路径与边界条件。
 */
class ProductionDefaultCredentialGuardTest {

  /**
   * 验证 ProductionDefaultCredentialGuard 的输入校验与拒绝边界。
   */
  @Test
  void productionRejectsUnchangedBootstrapPassword() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setEnvironment("production");
    when(repository.findUserByUsername("admin"))
        .thenReturn(
            Map.of(
                "enabled", true, "passwordHash", new BCryptPasswordEncoder().encode("12345678")));

    ProductionDefaultCredentialGuard guard =
        new ProductionDefaultCredentialGuard(repository, properties);

    assertThrows(
        IllegalStateException.class,
        () -> guard.run(new DefaultApplicationArguments(new String[0])));
  }

  /**
   * 验证 ProductionDefaultCredentialGuard 中登录的身份认证与会话边界。
   */
  @Test
  void developmentKeepsBootstrapLoginAvailable() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setEnvironment("development");
    ProductionDefaultCredentialGuard guard =
        new ProductionDefaultCredentialGuard(repository, properties);

    assertDoesNotThrow(() -> guard.run(new DefaultApplicationArguments(new String[0])));
  }

  /**
   * 验证 ProductionDefaultCredentialGuard 中认证的输入校验与拒绝边界。
   */
  @Test
  void productionRejectsDisabledAuthentication() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setEnvironment("production");
    properties.getAuth().setEnabled(false);
    ProductionDefaultCredentialGuard guard =
        new ProductionDefaultCredentialGuard(repository, properties);

    assertThrows(
        IllegalStateException.class,
        () -> guard.run(new DefaultApplicationArguments(new String[0])));
  }
}
