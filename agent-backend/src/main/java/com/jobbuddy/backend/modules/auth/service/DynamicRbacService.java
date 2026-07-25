package com.jobbuddy.backend.modules.auth.service;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.request.RbacMenuRequest;
import com.jobbuddy.backend.modules.auth.dto.request.RbacRoleRequest;
import com.jobbuddy.backend.modules.auth.dto.response.RbacMenuResponse;
import com.jobbuddy.backend.modules.auth.dto.response.RbacRoleResponse;
import java.util.List;

/**
 * 在保留受保护管理入口的前提下修改租户角色与菜单。
 *
 * <p>可分配资源受操作者委派边界限制，写操作不得越过认证租户。
 */
public interface DynamicRbacService {
  /**
   * 查询角色列表。
   *
   * @param tenantId 租户标识
   * @return 角色列表
   */
  List<RbacRoleResponse> listRoles(String tenantId);

  /**
   * 查询可分配角色列表。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @return 可分配角色列表
   */
  List<RbacRoleResponse> listAssignableRoles(String tenantId, AuthenticatedUser actor);

  /**
   * 创建角色。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param request 请求对象
   * @return 创建后的角色
   */
  RbacRoleResponse createRole(String tenantId, AuthenticatedUser actor, RbacRoleRequest request);

  /**
   * 更新角色。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param roleId 角色标识
   * @param request 请求对象
   * @return 更新后的角色
   */
  RbacRoleResponse updateRole(
      String tenantId, AuthenticatedUser actor, String roleId, RbacRoleRequest request);

  /**
   * 替换角色菜单。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param roleId 角色标识
   * @param menuIds 菜单标识列表
   * @return 更新后的角色菜单
   */
  RbacRoleResponse replaceRoleMenus(
      String tenantId, AuthenticatedUser actor, String roleId, List<String> menuIds);

  /**
   * 删除角色。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param roleId 角色标识
   */
  void deleteRole(String tenantId, AuthenticatedUser actor, String roleId);

  /**
   * 查询菜单列表。
   *
   * @param tenantId 租户标识
   * @return 菜单列表
   */
  List<RbacMenuResponse> listMenus(String tenantId);

  /**
   * 查询可分配菜单列表。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @return 可分配菜单列表
   */
  List<RbacMenuResponse> listAssignableMenus(String tenantId, AuthenticatedUser actor);

  /**
   * 创建菜单。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param request 请求对象
   * @return 创建后的菜单
   */
  RbacMenuResponse createMenu(String tenantId, AuthenticatedUser actor, RbacMenuRequest request);

  /**
   * 更新菜单。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param menuId 菜单标识
   * @param request 请求对象
   * @return 更新后的菜单
   */
  RbacMenuResponse updateMenu(
      String tenantId, AuthenticatedUser actor, String menuId, RbacMenuRequest request);

  /**
   * 删除菜单。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param menuId 菜单标识
   */
  void deleteMenu(String tenantId, AuthenticatedUser actor, String menuId);

  /**
   * 获取用户角色标识列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 用户角色标识列表
   */
  List<String> userRoleIds(String tenantId, String userId);

  /**
   * 获取用户角色名称。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 用户角色名称
   */
  List<String> userRoleNames(String tenantId, String userId);

  /**
   * 替换用户角色。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param userId 用户标识
   * @param roleIds 角色标识列表
   */
  void replaceUserRoles(
      String tenantId, AuthenticatedUser actor, String userId, List<String> roleIds);

  /**
   * 修复或拒绝会移除全部管理入口的角色变更。
   *
   * @param tenantId 租户标识
   */
  void protectManagementAccess(String tenantId);
}
