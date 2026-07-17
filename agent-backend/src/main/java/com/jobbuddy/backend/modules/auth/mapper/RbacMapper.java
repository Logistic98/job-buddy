package com.jobbuddy.backend.modules.auth.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface RbacMapper {
  List<Map<String, Object>> listRoles(@Param("tenantId") String tenantId);

  Map<String, Object> findRole(@Param("tenantId") String tenantId, @Param("roleId") String roleId);

  List<String> findRoleMenuIds(@Param("tenantId") String tenantId, @Param("roleId") String roleId);

  int insertRole(@Param("role") Map<String, Object> role);

  int updateRole(
      @Param("tenantId") String tenantId,
      @Param("roleId") String roleId,
      @Param("roleCode") String roleCode,
      @Param("roleName") String roleName,
      @Param("description") String description,
      @Param("enabled") boolean enabled,
      @Param("now") Instant now);

  int deleteRole(@Param("tenantId") String tenantId, @Param("roleId") String roleId);

  int countRoleUsers(@Param("tenantId") String tenantId, @Param("roleId") String roleId);

  int deleteRoleMenus(@Param("tenantId") String tenantId, @Param("roleId") String roleId);

  int insertRoleMenu(
      @Param("tenantId") String tenantId,
      @Param("roleId") String roleId,
      @Param("menuId") String menuId,
      @Param("now") Instant now);

  List<String> findUserIdsByRole(
      @Param("tenantId") String tenantId, @Param("roleId") String roleId);

  List<Map<String, Object>> listMenus(@Param("tenantId") String tenantId);

  Map<String, Object> findMenu(@Param("tenantId") String tenantId, @Param("menuId") String menuId);

  int countMenusByIds(@Param("tenantId") String tenantId, @Param("menuIds") List<String> menuIds);

  int insertMenu(@Param("menu") Map<String, Object> menu);

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

  int deleteMenu(@Param("tenantId") String tenantId, @Param("menuId") String menuId);

  int countMenuChildren(@Param("tenantId") String tenantId, @Param("menuId") String menuId);

  int countMenuRoles(@Param("tenantId") String tenantId, @Param("menuId") String menuId);

  List<String> findUserIdsByMenu(
      @Param("tenantId") String tenantId, @Param("menuId") String menuId);

  List<String> findUserRoleIds(@Param("tenantId") String tenantId, @Param("userId") String userId);

  List<String> findUserRoleNames(
      @Param("tenantId") String tenantId, @Param("userId") String userId);

  int countRolesByIds(@Param("tenantId") String tenantId, @Param("roleIds") List<String> roleIds);

  int deleteUserRoles(@Param("tenantId") String tenantId, @Param("userId") String userId);

  int insertUserRole(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("roleId") String roleId,
      @Param("now") Instant now);

  int countManagementUsers(@Param("tenantId") String tenantId);

  int countPermissionCode(@Param("permissionCode") String permissionCode);
}
