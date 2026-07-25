package com.jobbuddy.backend.common.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 定义已认证用户。
 */
@Data
@NoArgsConstructor
public class AuthenticatedUser {
  private String userId;
  private String username;
  private String displayName;

  /**
   * 客户端展示用主角色；授权以角色与权限集合为准。
   */
  private String role;

  private String tenantId;
  private String tenantCode;
  private Set<String> roles = new LinkedHashSet<String>();
  private Set<String> permissions = new LinkedHashSet<String>();
  private List<AuthenticatedMenu> menus = new ArrayList<AuthenticatedMenu>();

  /**
   * 创建已认证用户实例。
   *
   * @param userId 用户标识
   * @param username 用户名
   * @param displayName 展示名称
   * @param role 角色
   */
  public AuthenticatedUser(String userId, String username, String displayName, String role) {
    this(
        userId,
        username,
        displayName,
        role,
        "default-tenant",
        "default",
        Collections.<String>emptySet());
  }

  /**
   * 创建已认证用户实例。
   *
   * @param userId 用户标识
   * @param username 用户名
   * @param displayName 展示名称
   * @param role 角色
   * @param tenantId 租户标识
   * @param tenantCode 租户编码
   * @param permissions 权限列表
   */
  public AuthenticatedUser(
      String userId,
      String username,
      String displayName,
      String role,
      String tenantId,
      String tenantCode,
      Set<String> permissions) {
    this(
        userId,
        username,
        displayName,
        role,
        tenantId,
        tenantCode,
        Collections.<String>emptySet(),
        permissions,
        Collections.<AuthenticatedMenu>emptyList());
  }

  /**
   * 创建已认证用户实例。
   *
   * @param userId 用户标识
   * @param username 用户名
   * @param displayName 展示名称
   * @param role 角色
   * @param tenantId 租户标识
   * @param tenantCode 租户编码
   * @param roles 角色列表
   * @param permissions 权限列表
   * @param menus 菜单列表
   */
  public AuthenticatedUser(
      String userId,
      String username,
      String displayName,
      String role,
      String tenantId,
      String tenantCode,
      Set<String> roles,
      Set<String> permissions,
      List<AuthenticatedMenu> menus) {
    this.userId = userId;
    this.username = username;
    this.displayName = displayName;
    this.role = role;
    this.tenantId = tenantId;
    this.tenantCode = tenantCode;
    setRoles(roles);
    setPermissions(permissions);
    setMenus(menus);
  }

  /**
   * 设置角色列表。
   *
   * @param roles 角色列表
   */
  public void setRoles(Set<String> roles) {
    this.roles = roles == null ? new LinkedHashSet<String>() : new LinkedHashSet<String>(roles);
  }

  /**
   * 设置权限列表。
   *
   * @param permissions 权限列表
   */
  public void setPermissions(Set<String> permissions) {
    this.permissions =
        permissions == null ? new LinkedHashSet<String>() : new LinkedHashSet<String>(permissions);
  }

  /**
   * 设置菜单列表。
   *
   * @param menus 菜单列表
   */
  public void setMenus(List<AuthenticatedMenu> menus) {
    this.menus =
        menus == null
            ? new ArrayList<AuthenticatedMenu>()
            : new ArrayList<AuthenticatedMenu>(menus);
  }

  /**
   * 判断是否系统。
   *
   * @return 是否为系统身份
   */
  public boolean isSystem() {
    return "system".equalsIgnoreCase(role) || "local".equalsIgnoreCase(role);
  }

  /**
   * 判断是否存在权限。
   *
   * @param permission 权限
   * @return 是否具有指定权限
   */
  public boolean hasPermission(String permission) {
    return isSystem() || (permission != null && permissions.contains(permission));
  }
}
