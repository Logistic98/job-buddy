package com.jobbuddy.backend.modules.auth.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * RBAC 角色、菜单、权限及用户关联的 MyBatis 数据访问接口。
 */
public interface RbacMapper {
  /**
   * 查询角色列表。
   *
   * @param tenantId 租户标识
   * @return 角色列表
   */
  List<Map<String, Object>> listRoles(@Param("tenantId") String tenantId);

  /**
   * 查询角色菜单分配关系。
   *
   * @param tenantId 租户标识
   * @return 角色菜单分配关系
   */
  List<Map<String, Object>> listRoleMenuAssignments(@Param("tenantId") String tenantId);

  /**
   * 查询角色权限分配关系。
   *
   * @param tenantId 租户标识
   * @return 角色权限分配关系
   */
  List<Map<String, Object>> listRolePermissionAssignments(@Param("tenantId") String tenantId);

  /**
   * 查找角色。
   *
   * @param tenantId 租户标识
   * @param roleId 角色标识
   * @return 角色
   */
  Map<String, Object> findRole(@Param("tenantId") String tenantId, @Param("roleId") String roleId);

  /**
   * 查找角色菜单标识列表。
   *
   * @param tenantId 租户标识
   * @param roleId 角色标识
   * @return 角色菜单标识列表
   */
  List<String> findRoleMenuIds(@Param("tenantId") String tenantId, @Param("roleId") String roleId);

  /**
   * 新增角色。
   *
   * @param role 角色
   * @return 角色
   */
  int insertRole(@Param("role") Map<String, Object> role);

  /**
   * 更新角色。
   *
   * @param tenantId 租户标识
   * @param roleId 角色标识
   * @param roleCode 角色编码
   * @param roleName 角色名称
   * @param description 说明文本
   * @param enabled 启用状态
   * @param now 当前时间
   * @return 角色
   */
  int updateRole(
      @Param("tenantId") String tenantId,
      @Param("roleId") String roleId,
      @Param("roleCode") String roleCode,
      @Param("roleName") String roleName,
      @Param("description") String description,
      @Param("enabled") boolean enabled,
      @Param("now") Instant now);

  /**
   * 删除角色。
   *
   * @param tenantId 租户标识
   * @param roleId 角色标识
   * @return 角色
   */
  int deleteRole(@Param("tenantId") String tenantId, @Param("roleId") String roleId);

  /**
   * 统计角色用户列表。
   *
   * @param tenantId 租户标识
   * @param roleId 角色标识
   * @return 统计数量
   */
  int countRoleUsers(@Param("tenantId") String tenantId, @Param("roleId") String roleId);

  /**
   * 删除角色菜单列表。
   *
   * @param tenantId 租户标识
   * @param roleId 角色标识
   * @return 角色 Menus
   */
  int deleteRoleMenus(@Param("tenantId") String tenantId, @Param("roleId") String roleId);

  /**
   * 新增角色菜单。
   *
   * @param tenantId 租户标识
   * @param roleId 角色标识
   * @param menuId 菜单标识
   * @param now 当前时间
   * @return 角色菜单
   */
  int insertRoleMenu(
      @Param("tenantId") String tenantId,
      @Param("roleId") String roleId,
      @Param("menuId") String menuId,
      @Param("now") Instant now);

  /**
   * 查询绑定指定角色的用户标识列表。
   *
   * @param tenantId 租户标识
   * @param roleId 角色标识
   * @return 用户标识列表通过角色
   */
  List<String> findUserIdsByRole(
      @Param("tenantId") String tenantId, @Param("roleId") String roleId);

  /**
   * 查询菜单列表。
   *
   * @param tenantId 租户标识
   * @return 菜单列表
   */
  List<Map<String, Object>> listMenus(@Param("tenantId") String tenantId);

  /**
   * 查找菜单。
   *
   * @param tenantId 租户标识
   * @param menuId 菜单标识
   * @return 菜单
   */
  Map<String, Object> findMenu(@Param("tenantId") String tenantId, @Param("menuId") String menuId);

  /**
   * 统计指定标识中的有效菜单数。
   *
   * @param tenantId 租户标识
   * @param menuIds 菜单标识列表
   * @return 统计数量
   */
  int countMenusByIds(@Param("tenantId") String tenantId, @Param("menuIds") List<String> menuIds);

  /**
   * 新增菜单。
   *
   * @param menu 菜单
   * @return 菜单
   */
  int insertMenu(@Param("menu") Map<String, Object> menu);

  /**
   * 更新菜单。
   *
   * @param tenantId 租户标识
   * @param menuId 菜单标识
   * @param parentId 父级标识
   * @param menuCode 菜单编码
   * @param menuName 菜单名称
   * @param menuType 菜单类型
   * @param routePath 路由路径
   * @param componentKey 组件键
   * @param externalUrl 外部 URL
   * @param iconKey 图标键
   * @param permissionCode 权限编码
   * @param displayOrder 展示顺序
   * @param visible 是否可见
   * @param enabled 启用状态
   * @param now 当前时间
   * @return 菜单
   */
  int updateMenu(
      @Param("tenantId") String tenantId,
      @Param("menuId") String menuId,
      @Param("parentId") String parentId,
      @Param("menuCode") String menuCode,
      @Param("menuName") String menuName,
      @Param("menuType") String menuType,
      @Param("routePath") String routePath,
      @Param("componentKey") String componentKey,
      @Param("externalUrl") String externalUrl,
      @Param("iconKey") String iconKey,
      @Param("permissionCode") String permissionCode,
      @Param("displayOrder") int displayOrder,
      @Param("visible") boolean visible,
      @Param("enabled") boolean enabled,
      @Param("now") Instant now);

  /**
   * 删除菜单。
   *
   * @param tenantId 租户标识
   * @param menuId 菜单标识
   * @return 菜单
   */
  int deleteMenu(@Param("tenantId") String tenantId, @Param("menuId") String menuId);

  /**
   * 统计菜单子节点。
   *
   * @param tenantId 租户标识
   * @param menuId 菜单标识
   * @return 统计数量
   */
  int countMenuChildren(@Param("tenantId") String tenantId, @Param("menuId") String menuId);

  /**
   * 统计菜单角色列表。
   *
   * @param tenantId 租户标识
   * @param menuId 菜单标识
   * @return 统计数量
   */
  int countMenuRoles(@Param("tenantId") String tenantId, @Param("menuId") String menuId);

  /**
   * 查询绑定指定菜单的用户标识列表。
   *
   * @param tenantId 租户标识
   * @param menuId 菜单标识
   * @return 用户标识列表通过菜单
   */
  List<String> findUserIdsByMenu(
      @Param("tenantId") String tenantId, @Param("menuId") String menuId);

  /**
   * 查找用户角色标识列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 用户角色标识列表
   */
  List<String> findUserRoleIds(@Param("tenantId") String tenantId, @Param("userId") String userId);

  /**
   * 查找用户角色名称列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 用户角色名称列表
   */
  List<String> findUserRoleNames(
      @Param("tenantId") String tenantId, @Param("userId") String userId);

  /**
   * 统计指定标识中的有效角色数。
   *
   * @param tenantId 租户标识
   * @param roleIds 角色标识列表
   * @return 统计数量
   */
  int countRolesByIds(@Param("tenantId") String tenantId, @Param("roleIds") List<String> roleIds);

  /**
   * 删除用户角色列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 用户并发数 Roles
   */
  int deleteUserRoles(@Param("tenantId") String tenantId, @Param("userId") String userId);

  /**
   * 新增用户并发数角色。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param roleId 角色标识
   * @param now 当前时间
   * @return 用户并发数角色
   */
  int insertUserRole(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("roleId") String roleId,
      @Param("now") Instant now);

  /**
   * 统计可管理用户列表。
   *
   * @param tenantId 租户标识
   * @return 统计数量
   */
  int countManagementUsers(@Param("tenantId") String tenantId);

  /**
   * 统计权限编码。
   *
   * @param permissionCode 权限编码
   * @return 统计数量
   */
  int countPermissionCode(@Param("permissionCode") String permissionCode);
}
