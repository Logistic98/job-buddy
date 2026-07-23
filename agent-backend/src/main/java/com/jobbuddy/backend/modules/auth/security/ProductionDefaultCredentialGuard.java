package com.jobbuddy.backend.modules.auth.security;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import java.util.Locale;
import java.util.Map;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/**
 * Prevents a production deployment from accepting the public bootstrap password.
 *
 * <p>Released Flyway migrations remain immutable. Operators rotate the accounts through the
 * existing user-management API; development keeps the documented bootstrap login unchanged.
 */
@Component
public class ProductionDefaultCredentialGuard implements ApplicationRunner {
  private static final String BOOTSTRAP_PASSWORD = "12345678";
  private static final String[] BOOTSTRAP_USERS = {"admin", "user"};

  private final UserAuthRepository repository;
  private final JobBuddyProperties properties;
  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  public ProductionDefaultCredentialGuard(
      UserAuthRepository repository, JobBuddyProperties properties) {
    this.repository = repository;
    this.properties = properties;
  }

  @Override
  public void run(ApplicationArguments args) {
    if (!isProduction()) return;
    if (!properties.getAuth().isEnabled()) {
      throw new IllegalStateException("生产环境禁止关闭用户认证，请设置 JOB_BUDDY_AUTH_ENABLED=true");
    }
    for (String username : BOOTSTRAP_USERS) {
      Map<String, Object> user = repository.findUserByUsername(username);
      if (user == null || !Boolean.parseBoolean(String.valueOf(user.get("enabled")))) continue;
      String passwordHash = String.valueOf(user.get("passwordHash"));
      if (passwordEncoder.matches(BOOTSTRAP_PASSWORD, passwordHash)) {
        throw new IllegalStateException("生产环境检测到未轮换的默认账号密码，请先在受控环境重置 admin 和 user 密码");
      }
    }
  }

  private boolean isProduction() {
    String environment =
        String.valueOf(properties.getEnvironment()).trim().toLowerCase(Locale.ROOT);
    return "prod".equals(environment) || "production".equals(environment);
  }
}
