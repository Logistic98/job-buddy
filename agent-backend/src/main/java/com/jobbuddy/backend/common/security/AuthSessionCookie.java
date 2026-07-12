package com.jobbuddy.backend.common.security;

import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.time.Duration;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;

/** Resolves and manages the persistent workbench login cookie. */
public final class AuthSessionCookie {
  public static final String NAME = "job_buddy_session";
  private static final Duration SESSION_MAX_AGE = Duration.ofDays(7);

  private AuthSessionCookie() {}

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

  public static void write(HttpServletResponse response, String token, boolean secure) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie(token, SESSION_MAX_AGE, secure).toString());
  }

  public static void clear(HttpServletResponse response, boolean secure) {
    response.addHeader(HttpHeaders.SET_COOKIE, cookie("", Duration.ZERO, secure).toString());
  }

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
