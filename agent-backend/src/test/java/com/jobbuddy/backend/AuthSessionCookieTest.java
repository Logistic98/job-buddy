package com.jobbuddy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.jobbuddy.backend.common.security.AuthSessionCookie;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

/**
 * 验证 AuthSessionCookie 的核心行为、异常路径与边界条件。
 */
class AuthSessionCookieTest {
  /**
   * 验证 AuthSessionCookie 的持久化与状态变更规则。
   */
  @Test
  void resolvesTokenFromPersistentCookie() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie(AuthSessionCookie.NAME, "cookie-token"));

    assertThat(AuthSessionCookie.resolveToken(request)).isEqualTo("cookie-token");
  }

  /**
   * 验证 AuthSessionCookie 的身份认证与会话边界。
   */
  @Test
  void bearerTokenTakesPrecedenceOverCookie() {
    MockHttpServletRequest request = new MockHttpServletRequest();
    request.addHeader("Authorization", "Bearer header-token");
    request.setCookies(new Cookie(AuthSessionCookie.NAME, "cookie-token"));

    assertThat(AuthSessionCookie.resolveToken(request)).isEqualTo("header-token");
  }

  /**
   * 验证会话 Cookie 的 Secure、HttpOnly 写入与清除规则。
   */
  @Test
  void writesAndClearsSecureHttpOnlyCookie() {
    MockHttpServletResponse loginResponse = new MockHttpServletResponse();
    AuthSessionCookie.write(loginResponse, "session-token", true);

    String loginCookie = loginResponse.getHeader("Set-Cookie");
    assertThat(loginCookie)
        .contains(AuthSessionCookie.NAME + "=session-token")
        .contains("Max-Age=604800")
        .contains("Path=/")
        .contains("Secure")
        .contains("HttpOnly")
        .contains("SameSite=Lax");

    MockHttpServletResponse logoutResponse = new MockHttpServletResponse();
    AuthSessionCookie.clear(logoutResponse, false);
    assertThat(logoutResponse.getHeader("Set-Cookie"))
        .contains(AuthSessionCookie.NAME + "=")
        .contains("Max-Age=0");
  }
}
