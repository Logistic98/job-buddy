package com.jobbuddy.backend.common.security;

public final class AuthenticationScope {
  private static final ThreadLocal<Owner> CURRENT = new ThreadLocal<Owner>();

  private AuthenticationScope() {}

  public static void set(AuthenticatedUser user) {
    if (user == null) {
      clear();
      throw new IllegalArgumentException("认证用户不能为空");
    }
    set(user.getTenantId(), user.getUserId());
  }

  public static void set(String tenantId, String userId) {
    CURRENT.set(new Owner(requireValue(tenantId, "tenantId"), requireValue(userId, "userId")));
  }

  public static String tenantId() {
    return requireOwner().tenantId;
  }

  public static String userId() {
    return requireOwner().userId;
  }

  public static boolean isBound() {
    return CURRENT.get() != null;
  }

  public static void clear() {
    CURRENT.remove();
  }

  private static Owner requireOwner() {
    Owner owner = CURRENT.get();
    if (owner == null) throw new IllegalStateException("缺少认证上下文");
    return owner;
  }

  private static String requireValue(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("认证上下文缺少 " + field);
    }
    return value.trim();
  }

  private static final class Owner {
    private final String tenantId;
    private final String userId;

    private Owner(String tenantId, String userId) {
      this.tenantId = tenantId;
      this.userId = userId;
    }
  }
}
