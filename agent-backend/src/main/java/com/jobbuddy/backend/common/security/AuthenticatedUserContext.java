package com.jobbuddy.backend.common.security;

import jakarta.servlet.http.HttpServletRequest;

public final class AuthenticatedUserContext {
  public static final String USER_ATTRIBUTE = "jobBuddy.authenticatedUser";

  private AuthenticatedUserContext() {}

  public static String userId(HttpServletRequest request) {
    AuthenticatedUser user = user(request);
    String userId = user.getUserId();
    if (userId == null || userId.trim().isEmpty()) {
      throw new IllegalArgumentException("未登录或登录已过期");
    }
    return userId;
  }

  public static String tenantId(HttpServletRequest request) {
    AuthenticatedUser user = user(request);
    String tenantId = user.getTenantId();
    if (tenantId == null || tenantId.trim().isEmpty()) {
      throw new IllegalArgumentException("当前账号缺少租户归属");
    }
    return tenantId;
  }

  public static AuthenticatedUser user(HttpServletRequest request) {
    Object value = request == null ? null : request.getAttribute(USER_ATTRIBUTE);
    if (value instanceof AuthenticatedUser) {
      return (AuthenticatedUser) value;
    }
    throw new IllegalArgumentException("未登录或登录已过期");
  }
}
