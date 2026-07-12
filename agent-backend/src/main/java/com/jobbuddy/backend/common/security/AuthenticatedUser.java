package com.jobbuddy.backend.common.security;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class AuthenticatedUser {
  private String userId;
  private String username;
  private String displayName;

  /** Primary role for client display; authorization uses the roles and permissions sets. */
  private String role;

  private String tenantId;
  private String tenantCode;
  private Set<String> roles = new LinkedHashSet<String>();
  private Set<String> permissions = new LinkedHashSet<String>();
  private List<AuthenticatedMenu> menus = new ArrayList<AuthenticatedMenu>();

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

  public void setRoles(Set<String> roles) {
    this.roles = roles == null ? new LinkedHashSet<String>() : new LinkedHashSet<String>(roles);
  }

  public void setPermissions(Set<String> permissions) {
    this.permissions =
        permissions == null ? new LinkedHashSet<String>() : new LinkedHashSet<String>(permissions);
  }

  public void setMenus(List<AuthenticatedMenu> menus) {
    this.menus =
        menus == null
            ? new ArrayList<AuthenticatedMenu>()
            : new ArrayList<AuthenticatedMenu>(menus);
  }

  public boolean isSystem() {
    return "system".equalsIgnoreCase(role) || "local".equalsIgnoreCase(role);
  }

  public boolean hasPermission(String permission) {
    return isSystem() || (permission != null && permissions.contains(permission));
  }
}
