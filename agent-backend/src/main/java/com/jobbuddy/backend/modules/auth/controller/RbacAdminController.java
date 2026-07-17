package com.jobbuddy.backend.modules.auth.controller;

import com.jobbuddy.backend.common.dto.response.BooleanResultResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.common.security.PermissionCodes;
import com.jobbuddy.backend.common.security.RequirePermission;
import com.jobbuddy.backend.modules.auth.dto.request.RbacMenuRequest;
import com.jobbuddy.backend.modules.auth.dto.request.RbacRoleRequest;
import com.jobbuddy.backend.modules.auth.dto.response.PermissionDefinitionResponse;
import com.jobbuddy.backend.modules.auth.dto.response.RbacMenuResponse;
import com.jobbuddy.backend.modules.auth.dto.response.RbacRoleResponse;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import com.jobbuddy.backend.modules.auth.service.DynamicRbacService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "动态 RBAC 管理接口")
@RestController
@RequestMapping("/api/admin/rbac")
public class RbacAdminController {
  private final DynamicRbacService service;
  private final UserAuthRepository userRepository;

  public RbacAdminController(DynamicRbacService service, UserAuthRepository userRepository) {
    this.service = service;
    this.userRepository = userRepository;
  }

  @GetMapping("/roles")
  @RequirePermission(PermissionCodes.ROLES_MANAGE)
  public ApiResponse<List<RbacRoleResponse>> roles(HttpServletRequest request) {
    return ApiResponse.success(service.listRoles(tenant(request)));
  }

  @GetMapping("/roles/menus")
  @RequirePermission(PermissionCodes.ROLES_MANAGE)
  public ApiResponse<List<RbacMenuResponse>> assignableMenus(HttpServletRequest request) {
    return ApiResponse.success(service.listMenus(tenant(request)));
  }

  @PostMapping("/roles")
  @RequirePermission(PermissionCodes.ROLES_MANAGE)
  public ApiResponse<RbacRoleResponse> createRole(
      @RequestBody RbacRoleRequest body, HttpServletRequest request) {
    return ApiResponse.success(service.createRole(tenant(request), body));
  }

  @PutMapping("/roles/{roleId}")
  @RequirePermission(PermissionCodes.ROLES_MANAGE)
  public ApiResponse<RbacRoleResponse> updateRole(
      @PathVariable String roleId, @RequestBody RbacRoleRequest body, HttpServletRequest request) {
    return ApiResponse.success(service.updateRole(tenant(request), roleId, body));
  }

  @PutMapping("/roles/{roleId}/menus")
  @RequirePermission(PermissionCodes.ROLES_MANAGE)
  public ApiResponse<RbacRoleResponse> replaceRoleMenus(
      @PathVariable String roleId, @RequestBody RbacRoleRequest body, HttpServletRequest request) {
    return ApiResponse.success(
        service.replaceRoleMenus(tenant(request), roleId, body == null ? null : body.getMenuIds()));
  }

  @DeleteMapping("/roles/{roleId}")
  @RequirePermission(PermissionCodes.ROLES_MANAGE)
  public ApiResponse<BooleanResultResponse> deleteRole(
      @PathVariable String roleId, HttpServletRequest request) {
    service.deleteRole(tenant(request), roleId);
    return ApiResponse.success(new BooleanResultResponse(true));
  }

  @GetMapping("/menus")
  @RequirePermission(PermissionCodes.MENUS_MANAGE)
  public ApiResponse<List<RbacMenuResponse>> menus(HttpServletRequest request) {
    return ApiResponse.success(service.listMenus(tenant(request)));
  }

  @PostMapping("/menus")
  @RequirePermission(PermissionCodes.MENUS_MANAGE)
  public ApiResponse<RbacMenuResponse> createMenu(
      @RequestBody RbacMenuRequest body, HttpServletRequest request) {
    return ApiResponse.success(service.createMenu(tenant(request), body));
  }

  @PutMapping("/menus/{menuId}")
  @RequirePermission(PermissionCodes.MENUS_MANAGE)
  public ApiResponse<RbacMenuResponse> updateMenu(
      @PathVariable String menuId, @RequestBody RbacMenuRequest body, HttpServletRequest request) {
    return ApiResponse.success(service.updateMenu(tenant(request), menuId, body));
  }

  @DeleteMapping("/menus/{menuId}")
  @RequirePermission(PermissionCodes.MENUS_MANAGE)
  public ApiResponse<BooleanResultResponse> deleteMenu(
      @PathVariable String menuId, HttpServletRequest request) {
    service.deleteMenu(tenant(request), menuId);
    return ApiResponse.success(new BooleanResultResponse(true));
  }

  @Operation(summary = "查询菜单可关联的权限码")
  @GetMapping("/permissions")
  @RequirePermission(PermissionCodes.MENUS_MANAGE)
  public ApiResponse<List<PermissionDefinitionResponse>> permissions() {
    List<PermissionDefinitionResponse> result = new ArrayList<PermissionDefinitionResponse>();
    for (Map<String, Object> row : userRepository.listPermissionDefinitions()) {
      result.add(
          new PermissionDefinitionResponse(
              String.valueOf(row.get("permissionCode")),
              String.valueOf(row.get("permissionName"))));
    }
    return ApiResponse.success(result);
  }

  private String tenant(HttpServletRequest request) {
    return AuthenticatedUserContext.tenantId(request);
  }
}
