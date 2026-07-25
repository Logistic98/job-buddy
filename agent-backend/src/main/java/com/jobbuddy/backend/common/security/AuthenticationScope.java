package com.jobbuddy.backend.common.security;

/**
 * 定义认证作用域。
 */
public final class AuthenticationScope {
  private static final ThreadLocal<Owner> CURRENT = new ThreadLocal<Owner>();

  /**
   * 创建认证作用域实例。
   */
  private AuthenticationScope() {}

  /**
   * 设置认证作用域。
   *
   * @param user 用户
   */
  public static void set(AuthenticatedUser user) {
    if (user == null) {
      clear();
      throw new IllegalArgumentException("认证用户不能为空");
    }
    set(user.getTenantId(), user.getUserId());
  }

  /**
   * 设置认证作用域。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   */
  public static void set(String tenantId, String userId) {
    CURRENT.set(new Owner(requireValue(tenantId, "tenantId"), requireValue(userId, "userId")));
  }

  /**
   * 读取当前租户标识。
   *
   * @return 租户标识
   */
  public static String tenantId() {
    return requireOwner().tenantId;
  }

  /**
   * 读取当前用户标识。
   *
   * @return 用户标识
   */
  public static String userId() {
    return requireOwner().userId;
  }

  /**
   * 判断是否绑定身份。
   *
   * @return 当前线程是否绑定认证信息
   */
  public static boolean isBound() {
    return CURRENT.get() != null;
  }

  /**
   * 清理认证作用域。
   */
  public static void clear() {
    CURRENT.remove();
  }

  /**
   * 校验并获取属主。
   *
   * @return 校验并获取属主
   */
  private static Owner requireOwner() {
    Owner owner = CURRENT.get();
    if (owner == null) throw new IllegalStateException("缺少认证上下文");
    return owner;
  }

  /**
   * 校验并获取值。
   *
   * @param value 待处理值
   * @param field 字段
   * @return 校验并获取值
   */
  private static String requireValue(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException("认证上下文缺少 " + field);
    }
    return value.trim();
  }

  /**
   * 定义属主。
   */
  private static final class Owner {
    private final String tenantId;
    private final String userId;

    /**
     * 创建属主实例。
     *
     * @param tenantId 租户标识
     * @param userId 用户标识
     */
    private Owner(String tenantId, String userId) {
      this.tenantId = tenantId;
      this.userId = userId;
    }
  }
}
