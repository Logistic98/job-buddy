package com.jobbuddy.backend.common.security;

import jakarta.servlet.http.HttpServletRequest;

/**
 * 定义已认证用户上下文。
 */
public final class AuthenticatedUserContext {
  public static final String USER_ATTRIBUTE = "jobBuddy.authenticatedUser";

  /**
   * 创建已认证用户上下文实例。
   */
  private AuthenticatedUserContext() {}

  /**
   * 读取当前用户标识。
   *
   * @param request 请求参数
   * @return 用户标识
   */
  public static String userId(HttpServletRequest request) {
    AuthenticatedUser user = user(request);
    String userId = user.getUserId();
    if (userId == null || userId.trim().isEmpty()) {
      throw new IllegalArgumentException("未登录或登录已过期");
    }
    return userId;
  }

  /**
   * 读取当前租户标识。
   *
   * @param request 请求参数
   * @return 租户标识
   */
  public static String tenantId(HttpServletRequest request) {
    AuthenticatedUser user = user(request);
    String tenantId = user.getTenantId();
    if (tenantId == null || tenantId.trim().isEmpty()) {
      throw new IllegalArgumentException("当前账号缺少租户归属");
    }
    return tenantId;
  }

  /**
   * 从请求上下文获取认证用户。
   *
   * @param request 请求参数
   * @return 当前认证用户
   */
  public static AuthenticatedUser user(HttpServletRequest request) {
    Object value = request == null ? null : request.getAttribute(USER_ATTRIBUTE);
    if (value instanceof AuthenticatedUser) {
      return (AuthenticatedUser) value;
    }
    throw new IllegalArgumentException("未登录或登录已过期");
  }
}
