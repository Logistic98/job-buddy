package com.jobbuddy.backend.common.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/**
 * 解析并管理工作台持久登录 Cookie。
 */
public final class AuthSessionCookie {
  public static final String NAME = "job_buddy_session";
  private static final Duration SESSION_MAX_AGE = Duration.ofDays(7);

  /**
   * 创建认证会话 Cookie 实例。
   */
  private AuthSessionCookie() {}

  /**
   * 解析令牌。
   *
   * @param request 请求参数
   * @return 令牌
   */
  public static String resolveToken(HttpServletRequest request) {
    String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
    if (authorization != null) {
      String value = authorization.trim();
      if (value.toLowerCase().startsWith("bearer ")) {
        return value.substring(7).trim();
      }
      if (!value.isEmpty()) return value;
    }

    Cookie[] cookies = request.getCookies();
    if (cookies == null) return "";
    for (Cookie cookie : cookies) {
      if (NAME.equals(cookie.getName()))
        return cookie.getValue() == null ? "" : cookie.getValue().trim();
    }
    return "";
  }

  /**
   * 写入会话 Cookie。
   *
   * @param response 响应数据
   * @param token 令牌
   * @param secure 是否使用 HTTPS
   */
  public static void write(HttpServletResponse response, String token, boolean secure) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, SESSION_MAX_AGE, secure).toString());
  }

  /**
   * 清理认证会话 Cookie。
   *
   * @param response 响应数据
   * @param secure 是否使用 HTTPS
   */
  public static void clear(HttpServletResponse response, boolean secure) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, secure).toString());
  }

  /**
   * 构造会话 Cookie。
   *
   * @param value 待处理值
   * @param maxAge 最大有效期
   * @param secure 是否使用 HTTPS
   * @return 会话 Cookie
   */
  private static ResponseCookie cookie(String value, Duration maxAge, boolean secure) {
    return ResponseCookie.from(NAME, value == null ? "" : value)
        .httpOnly(true)
        .secure(secure)
        .sameSite("Lax")
        .path("/")
        .maxAge(maxAge)
        .build();
  }
}
