package com.jobbuddy.backend.modules.auth.service;

import com.jobbuddy.backend.modules.auth.dto.request.RbacMenuRequest;
import com.jobbuddy.backend.modules.auth.dto.request.RbacRoleRequest;
import com.jobbuddy.backend.modules.auth.dto.response.RbacMenuResponse;
import com.jobbuddy.backend.modules.auth.dto.response.RbacRoleResponse;
import java.util.List;

public interface DynamicRbacService {
  List<RbacRoleResponse> listRoles(String tenantId);

  RbacRoleResponse createRole(String tenantId, RbacRoleRequest request);

  RbacRoleResponse updateRole(String tenantId, String roleId, RbacRoleRequest request);

  RbacRoleResponse replaceRoleMenus(String tenantId, String roleId, List<String> menuIds);

  void deleteRole(String tenantId, String roleId);

  List<RbacMenuResponse> listMenus(String tenantId);

  RbacMenuResponse createMenu(String tenantId, RbacMenuRequest request);

  RbacMenuResponse updateMenu(String tenantId, String menuId, RbacMenuRequest request);

  void deleteMenu(String tenantId, String menuId);

  List<String> userRoleIds(String tenantId, String userId);

  List<String> userRoleNames(String tenantId, String userId);

  void replaceUserRoles(String tenantId, String userId, List<String> roleIds);

  void protectManagementAccess(String tenantId);
}
