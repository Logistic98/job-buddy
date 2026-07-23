package com.jobbuddy.backend.modules.auth.service.impl;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.common.security.PermissionCodes;
import com.jobbuddy.backend.modules.auth.exception.AuthorizationDeniedException;
import com.jobbuddy.backend.modules.auth.mapper.RbacMapper;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class RbacDelegationPolicy {
  private final RbacMapper mapper;
  private final UserAuthRepository userRepository;

  public RbacDelegationPolicy(RbacMapper mapper, UserAuthRepository userRepository) {
    this.mapper = mapper;
    this.userRepository = userRepository;
  }

  public void requireActorTenant(String tenantId, AuthenticatedUser actor) {
    if (actor == null || actor.getTenantId() == null || !actor.getTenantId().equals(tenantId)) {
      throw new AuthorizationDeniedException("操作者不属于当前租户");
    }
  }

  public List<String> assignableRoleIds(String tenantId, AuthenticatedUser actor) {
    requireActorTenant(tenantId, actor);
    Map<String, Set<String>> rolePermissions = new LinkedHashMap<String, Set<String>>();
    for (Map<String, Object> role : mapper.listRoles(tenantId)) {
      rolePermissions.put(text(role.get("roleId")), new LinkedHashSet<String>());
    }
    for (Map<String, Object> assignment : mapper.listRolePermissionAssignments(tenantId)) {
      Set<String> permissions = rolePermissions.get(text(assignment.get("roleId")));
      if (permissions != null) {
        permissions.add(text(assignment.get("permissionCode")));
      }
    }
    Map<String, Boolean> definitions = permissionDefinitions();
    List<String> result = new ArrayList<String>();
    for (Map.Entry<String, Set<String>> role : rolePermissions.entrySet()) {
      if (canAssignPermissions(actor, role.getValue(), definitions)) {
        result.add(role.getKey());
      }
    }
    return result;
  }

  public List<String> assignableMenuIds(String tenantId, AuthenticatedUser actor) {
    requireActorTenant(tenantId, actor);
    Map<String, Boolean> definitions = permissionDefinitions();
    List<String> result = new ArrayList<String>();
    for (Map<String, Object> menu : mapper.listMenus(tenantId)) {
      String permission = text(menu.get("permissionCode"));
      if (permission.isEmpty() || canGrantPermission(actor, permission, definitions)) {
        result.add(text(menu.get("menuId")));
      }
    }
    return result;
  }

  public Set<String> assignablePermissionCodes(String tenantId, AuthenticatedUser actor) {
    requireActorTenant(tenantId, actor);
    Set<String> result = new LinkedHashSet<String>();
    for (Map.Entry<String, Boolean> entry : permissionDefinitions().entrySet()) {
      if (Boolean.TRUE.equals(entry.getValue())
          && safePermissions(actor).contains(entry.getKey())) {
        result.add(entry.getKey());
      }
    }
    return result;
  }

  public void validateRoleMenuChange(
      String tenantId,
      AuthenticatedUser actor,
      List<String> currentMenuIds,
      List<String> requestedMenuIds) {
    requireActorTenant(tenantId, actor);
    Map<String, Boolean> definitions = permissionDefinitions();
    Set<String> currentPermissions = permissionsForMenus(tenantId, currentMenuIds);
    if (containsNonGrantable(currentPermissions, definitions) && !isPlatformActor(actor)) {
      throw new AuthorizationDeniedException("受保护角色只能由平台控制主体修改");
    }
    Set<String> added = new LinkedHashSet<String>(requestedMenuIds);
    added.removeAll(new LinkedHashSet<String>(currentMenuIds));
    for (String permission : permissionsForMenus(tenantId, new ArrayList<String>(added))) {
      if (!canGrantPermission(actor, permission, definitions)) {
        throw new AuthorizationDeniedException("不能授予超出操作者委派上限的权限: " + permission);
      }
    }
  }

  public void validateMenuPermissionChange(
      String tenantId,
      AuthenticatedUser actor,
      String currentPermission,
      String requestedPermission) {
    requireActorTenant(tenantId, actor);
    Map<String, Boolean> definitions = permissionDefinitions();
    String current = text(currentPermission);
    String requested = text(requestedPermission);
    if (!current.isEmpty() && !isGrantable(current, definitions) && !isPlatformActor(actor)) {
      throw new AuthorizationDeniedException("受保护菜单只能由平台控制主体修改");
    }
    if (!requested.isEmpty()
        && !requested.equals(current)
        && !canGrantPermission(actor, requested, definitions)) {
      throw new AuthorizationDeniedException("不能配置超出操作者委派上限的权限: " + requested);
    }
  }

  public void validateUserRoleChange(
      String tenantId,
      AuthenticatedUser actor,
      String targetUserId,
      List<String> requestedRoleIds) {
    requireActorTenant(tenantId, actor);
    Map<String, Boolean> definitions = permissionDefinitions();
    if (actor.getUserId() != null && actor.getUserId().equals(targetUserId)) {
      throw new AuthorizationDeniedException("不能通过用户管理接口修改自己的角色");
    }
    Set<String> current = new LinkedHashSet<String>(userRepository.findPermissions(targetUserId));
    if (!current.isEmpty() && !canManageTarget(actor, current, definitions)) {
      throw new AuthorizationDeniedException("不能修改同级或更高权限账号的角色");
    }
    Set<String> requested = permissionsForRoles(tenantId, requestedRoleIds);
    if (!canAssignPermissions(actor, requested, definitions)) {
      throw new AuthorizationDeniedException("不能分配超出操作者委派上限的角色");
    }
  }

  public void validatePasswordReset(String tenantId, AuthenticatedUser actor, String targetUserId) {
    requireActorTenant(tenantId, actor);
    Map<String, Boolean> definitions = permissionDefinitions();
    if (actor.getUserId() != null && actor.getUserId().equals(targetUserId)) {
      throw new AuthorizationDeniedException("请通过个人密码修改流程更新自己的密码");
    }
    Set<String> targetPermissions =
        new LinkedHashSet<String>(userRepository.findPermissions(targetUserId));
    if (!canManageTarget(actor, targetPermissions, definitions)) {
      throw new AuthorizationDeniedException("不能重置同级、更高权限或受保护账号的密码");
    }
  }

  private boolean canManageTarget(
      AuthenticatedUser actor, Set<String> targetPermissions, Map<String, Boolean> definitions) {
    if (containsNonGrantable(targetPermissions, definitions)) {
      return false;
    }
    Set<String> actorPermissions = safePermissions(actor);
    return actorPermissions.containsAll(targetPermissions)
        && !actorPermissions.equals(targetPermissions);
  }

  private boolean canAssignPermissions(
      AuthenticatedUser actor, Set<String> requestedPermissions, Map<String, Boolean> definitions) {
    for (String permission : requestedPermissions) {
      if (!canGrantPermission(actor, permission, definitions)) {
        return false;
      }
    }
    return true;
  }

  private boolean canGrantPermission(
      AuthenticatedUser actor, String permission, Map<String, Boolean> definitions) {
    return safePermissions(actor).contains(permission)
        && (isGrantable(permission, definitions) || isPlatformActor(actor));
  }

  private boolean isPlatformActor(AuthenticatedUser actor) {
    return actor != null && actor.hasPermission(PermissionCodes.PLATFORM_MANAGE);
  }

  private boolean containsNonGrantable(Set<String> permissions, Map<String, Boolean> definitions) {
    for (String permission : permissions) {
      if (!isGrantable(permission, definitions)) {
        return true;
      }
    }
    return false;
  }

  private boolean isGrantable(String permission, Map<String, Boolean> definitions) {
    Boolean value = definitions.get(permission);
    return Boolean.TRUE.equals(value);
  }

  private Map<String, Boolean> permissionDefinitions() {
    Map<String, Boolean> result = new LinkedHashMap<String, Boolean>();
    for (Map<String, Object> row : userRepository.listPermissionDefinitions()) {
      result.put(text(row.get("permissionCode")), Boolean.TRUE.equals(row.get("grantable")));
    }
    return result;
  }

  private Set<String> permissionsForRoles(String tenantId, List<String> roleIds) {
    Set<String> result = new LinkedHashSet<String>();
    for (String roleId : roleIds) {
      result.addAll(permissionsForRole(tenantId, roleId));
    }
    return result;
  }

  private Set<String> permissionsForRole(String tenantId, String roleId) {
    return permissionsForMenus(tenantId, mapper.findRoleMenuIds(tenantId, roleId));
  }

  private Set<String> permissionsForMenus(String tenantId, List<String> menuIds) {
    Set<String> result = new LinkedHashSet<String>();
    for (String menuId : menuIds == null ? Collections.<String>emptyList() : menuIds) {
      Map<String, Object> menu = mapper.findMenu(tenantId, menuId);
      if (menu == null) {
        throw new IllegalArgumentException("包含不存在或跨租户的菜单");
      }
      String permission = text(menu.get("permissionCode"));
      if (!permission.isEmpty()) {
        result.add(permission);
      }
    }
    return result;
  }

  private Set<String> safePermissions(AuthenticatedUser actor) {
    return actor == null || actor.getPermissions() == null
        ? Collections.<String>emptySet()
        : new LinkedHashSet<String>(actor.getPermissions());
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }
}
