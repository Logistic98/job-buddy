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

/**
 * 计算认证操作者可委派的角色、菜单与权限子集。
 *
 * <p>操作者不能授予自身没有或被标记为不可委派的权限。
 */
@Component
public class RbacDelegationPolicy {
  private final RbacMapper mapper;
  private final UserAuthRepository userRepository;

  /**
   * 创建 RBAC 委派策略实例。
   *
   * @param mapper 数据映射
   * @param userRepository 用户存储访问
   */
  public RbacDelegationPolicy(RbacMapper mapper, UserAuthRepository userRepository) {
    this.mapper = mapper;
    this.userRepository = userRepository;
  }

  /**
   * 校验并获取操作人租户。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   */
  public void requireActorTenant(String tenantId, AuthenticatedUser actor) {
    if (actor == null || actor.getTenantId() == null || !actor.getTenantId().equals(tenantId)) {
      throw new AuthorizationDeniedException("操作者不属于当前租户");
    }
  }

  /**
   * 获取可分配角色标识。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @return 可分配角色标识
   */
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

  /**
   * 获取可分配菜单标识。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @return 可分配菜单标识
   */
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

  /**
   * 获取可分配权限编码。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @return 可分配权限编码
   */
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

  /**
   * 校验角色菜单变更。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param currentMenuIds 当前菜单标识列表
   * @param requestedMenuIds 申请分配的菜单标识
   */
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

  /**
   * 校验菜单权限变更。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param currentPermission 当前权限
   * @param requestedPermission 申请的权限
   */
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

  /**
   * 校验用户角色变更。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param targetUserId 目标用户标识
   * @param requestedRoleIds 申请分配的角色标识
   */
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

  /**
   * 校验密码修改请求。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param targetUserId 目标用户标识
   */
  public void validatePasswordChange(
      String tenantId, AuthenticatedUser actor, String targetUserId) {
    requireActorTenant(tenantId, actor);
  }

  /**
   * 判断是否可管理目标对象。
   *
   * @param actor 操作人
   * @param targetPermissions 目标权限集合
   * @param definitions 能力定义列表
   * @return 是否可管理目标对象
   */
  private boolean canManageTarget(
      AuthenticatedUser actor, Set<String> targetPermissions, Map<String, Boolean> definitions) {
    if (containsNonGrantable(targetPermissions, definitions)) {
      return false;
    }
    Set<String> actorPermissions = safePermissions(actor);
    return actorPermissions.containsAll(targetPermissions)
        && !actorPermissions.equals(targetPermissions);
  }

  /**
   * 判断是否可分配指定权限。
   *
   * @param actor 操作人
   * @param requestedPermissions 申请的权限集合
   * @param definitions 能力定义列表
   * @return 是否可分配指定权限
   */
  private boolean canAssignPermissions(
      AuthenticatedUser actor, Set<String> requestedPermissions, Map<String, Boolean> definitions) {
    for (String permission : requestedPermissions) {
      if (!canGrantPermission(actor, permission, definitions)) {
        return false;
      }
    }
    return true;
  }

  /**
   * 判断是否可授予指定权限。
   *
   * @param actor 操作人
   * @param permission 权限
   * @param definitions 能力定义列表
   * @return 是否可授予指定权限
   */
  private boolean canGrantPermission(
      AuthenticatedUser actor, String permission, Map<String, Boolean> definitions) {
    return safePermissions(actor).contains(permission)
        && (isGrantable(permission, definitions) || isPlatformActor(actor));
  }

  /**
   * 判断是否为平台级操作人。
   *
   * @param actor 操作人
   * @return 是否为平台级操作人
   */
  private boolean isPlatformActor(AuthenticatedUser actor) {
    return actor != null && actor.hasPermission(PermissionCodes.PLATFORM_MANAGE);
  }

  /**
   * 判断是否包含不可授予权限。
   *
   * @param permissions 权限集合
   * @param definitions 能力定义列表
   * @return 是否包含不可授予权限
   */
  private boolean containsNonGrantable(Set<String> permissions, Map<String, Boolean> definitions) {
    for (String permission : permissions) {
      if (!isGrantable(permission, definitions)) {
        return true;
      }
    }
    return false;
  }

  /**
   * 判断权限是否可授予。
   *
   * @param permission 权限
   * @param definitions 能力定义列表
   * @return 权限是否可授予是否成立
   */
  private boolean isGrantable(String permission, Map<String, Boolean> definitions) {
    Boolean value = definitions.get(permission);
    return Boolean.TRUE.equals(value);
  }

  /**
   * 获取权限定义列表。
   *
   * @return 权限定义列表
   */
  private Map<String, Boolean> permissionDefinitions() {
    Map<String, Boolean> result = new LinkedHashMap<String, Boolean>();
    for (Map<String, Object> row : userRepository.listPermissionDefinitions()) {
      result.put(text(row.get("permissionCode")), Boolean.TRUE.equals(row.get("grantable")));
    }
    return result;
  }

  /**
   * 查询角色拥有的权限。
   *
   * @param tenantId 租户标识
   * @param roleIds 角色标识列表
   * @return 角色拥有的权限
   */
  private Set<String> permissionsForRoles(String tenantId, List<String> roleIds) {
    Set<String> result = new LinkedHashSet<String>();
    for (String roleId : roleIds) {
      result.addAll(permissionsForRole(tenantId, roleId));
    }
    return result;
  }

  /**
   * 查询角色拥有的权限。
   *
   * @param tenantId 租户标识
   * @param roleId 角色标识
   * @return 角色拥有的权限
   */
  private Set<String> permissionsForRole(String tenantId, String roleId) {
    return permissionsForMenus(tenantId, mapper.findRoleMenuIds(tenantId, roleId));
  }

  /**
   * 查询访问菜单所需的权限。
   *
   * @param tenantId 租户标识
   * @param menuIds 菜单标识列表
   * @return 访问菜单所需的权限
   */
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

  /**
   * 获取安全数据权限。
   *
   * @param actor 操作人
   * @return 安全数据权限
   */
  private Set<String> safePermissions(AuthenticatedUser actor) {
    return actor == null || actor.getPermissions() == null
        ? Collections.<String>emptySet()
        : new LinkedHashSet<String>(actor.getPermissions());
  }

  /**
   * 获取文本。
   *
   * @param value 输入值
   * @return 文本内容
   */
  private String text(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }
}
