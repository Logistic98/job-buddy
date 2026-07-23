package com.jobbuddy.backend.modules.auth.service;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.request.RbacMenuRequest;
import com.jobbuddy.backend.modules.auth.dto.request.RbacRoleRequest;
import com.jobbuddy.backend.modules.auth.dto.response.RbacMenuResponse;
import com.jobbuddy.backend.modules.auth.dto.response.RbacRoleResponse;
import java.util.List;

public interface DynamicRbacService {
  List<RbacRoleResponse> listRoles(String tenantId);

  List<RbacRoleResponse> listAssignableRoles(String tenantId, AuthenticatedUser actor);

  RbacRoleResponse createRole(String tenantId, AuthenticatedUser actor, RbacRoleRequest request);

  RbacRoleResponse updateRole(
      String tenantId, AuthenticatedUser actor, String roleId, RbacRoleRequest request);

  RbacRoleResponse replaceRoleMenus(
      String tenantId, AuthenticatedUser actor, String roleId, List<String> menuIds);

  void deleteRole(String tenantId, AuthenticatedUser actor, String roleId);

  List<RbacMenuResponse> listMenus(String tenantId);

  List<RbacMenuResponse> listAssignableMenus(String tenantId, AuthenticatedUser actor);

  RbacMenuResponse createMenu(String tenantId, AuthenticatedUser actor, RbacMenuRequest request);

  RbacMenuResponse updateMenu(
      String tenantId, AuthenticatedUser actor, String menuId, RbacMenuRequest request);

  void deleteMenu(String tenantId, AuthenticatedUser actor, String menuId);

  List<String> userRoleIds(String tenantId, String userId);

  List<String> userRoleNames(String tenantId, String userId);

  void replaceUserRoles(
      String tenantId, AuthenticatedUser actor, String userId, List<String> roleIds);

  void protectManagementAccess(String tenantId);
}
