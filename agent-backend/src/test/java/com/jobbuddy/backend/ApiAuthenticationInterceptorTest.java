package com.jobbuddy.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobbuddy.backend.common.security.AuthSessionCookie;
import jakarta.servlet.http.Cookie;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

/**
 * 验证 ApiAuthenticationInterceptor 的核心业务契约。
 */
@SpringBootTest(
    classes = AgentBackendApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:agent_backend_auth_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:/schema-test.sql",
      "spring.flyway.enabled=false",
      "job-buddy.auth.enabled=true"
    })
@AutoConfigureMockMvc
class ApiAuthenticationInterceptorTest {
  private static final String TOKEN = "auth-test-token";
  private static final String USER_TOKEN = "auth-user-token";

  @Autowired private MockMvc mockMvc;

  @Autowired private JdbcTemplate jdbcTemplate;

  /**
   * 初始化测试所需依赖与认证上下文。
   */
  @BeforeEach
  void setUp() {
    jdbcTemplate.update("DELETE FROM user_login_session");
    jdbcTemplate.update("DELETE FROM role_menu");
    jdbcTemplate.update("DELETE FROM user_role");
    jdbcTemplate.update("DELETE FROM rbac_menu");
    jdbcTemplate.update("DELETE FROM rbac_role");
    jdbcTemplate.update("DELETE FROM app_user");
    jdbcTemplate.update(
        "INSERT INTO app_user(user_id, username, password_hash, display_name, role, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        "user-auth-1",
        "auth_user",
        "$2a$10$012345678901234567890uT08Y9MtBz6Xklj1m0K4E2NQ0BzTAb1K",
        "Auth User",
        "admin",
        Boolean.TRUE,
        Timestamp.from(Instant.now()),
        Timestamp.from(Instant.now()));
    jdbcTemplate.update(
        "MERGE INTO permission_definition(permission_code, permission_name, grantable, display_order) KEY(permission_code) VALUES ('platform:manage','平台设置',FALSE,900)");
    jdbcTemplate.update(
        "INSERT INTO rbac_role(role_id,tenant_id,role_code,role_name,enabled) VALUES ('role-manager','default-tenant','manager','Manager',TRUE)");
    jdbcTemplate.update(
        "INSERT INTO rbac_menu(menu_id,tenant_id,menu_code,menu_name,menu_type,permission_code,visible,enabled) VALUES ('menu-settings','default-tenant','settings','Settings','page','platform:manage',TRUE,TRUE)");
    jdbcTemplate.update(
        "INSERT INTO user_role(tenant_id,user_id,role_id) VALUES ('default-tenant','user-auth-1','role-manager')");
    jdbcTemplate.update(
        "INSERT INTO role_menu(tenant_id,role_id,menu_id) VALUES ('default-tenant','role-manager','menu-settings')");
    jdbcTemplate.update(
        "INSERT INTO user_login_session(token, user_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
        TOKEN,
        "user-auth-1",
        Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)),
        Timestamp.from(Instant.now()));
  }

  /**
   * 验证 ApiAuthenticationInterceptor 的输入校验与拒绝边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void protectedApiRejectsAnonymousRequests() throws Exception {
    mockMvc
        .perform(get("/api/settings"))
        .andExpect(status().isUnauthorized())
        .andExpect(jsonPath("$.code").value(401));
  }

  /**
   * 验证 ApiAuthenticationInterceptor 的身份认证与会话边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void protectedApiAcceptsValidBearerToken() throws Exception {
    mockMvc
        .perform(get("/api/settings").header("Authorization", "Bearer " + TOKEN))
        .andExpect(status().isOk())
        .andExpect(header().doesNotExist("Set-Cookie"))
        .andExpect(jsonPath("$.code").value(200));
  }

  /**
   * 验证 ApiAuthenticationInterceptor 中会话的身份认证与会话边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void protectedApiAcceptsValidSessionCookie() throws Exception {
    mockMvc
        .perform(get("/api/settings").cookie(new Cookie(AuthSessionCookie.NAME, TOKEN)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));
  }

  /**
   * 验证 ApiAuthenticationInterceptor 中用户的权限与租户隔离边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void ordinaryUserWithoutPermissionIsForbiddenFromAdminAndBusinessApis() throws Exception {
    jdbcTemplate.update(
        "INSERT INTO app_user(user_id, username, password_hash, display_name, role, enabled, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, ?, ?)",
        "user-auth-2",
        "normal_user",
        "hash",
        "Normal User",
        "user",
        Boolean.TRUE,
        Timestamp.from(Instant.now()),
        Timestamp.from(Instant.now()));
    jdbcTemplate.update(
        "INSERT INTO user_login_session(token, user_id, expires_at, created_at) VALUES (?, ?, ?, ?)",
        USER_TOKEN,
        "user-auth-2",
        Timestamp.from(Instant.now().plus(1, ChronoUnit.HOURS)),
        Timestamp.from(Instant.now()));

    mockMvc
        .perform(get("/api/settings").header("Authorization", "Bearer " + USER_TOKEN))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
    mockMvc
        .perform(get("/api/chat/sessions").header("Authorization", "Bearer " + USER_TOKEN))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value(403));
  }

  /**
   * 验证 ApiAuthenticationInterceptor 的核心业务契约。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void publicHealthEndpointStaysAnonymous() throws Exception {
    mockMvc
        .perform(get("/api/health"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200));
  }
}
