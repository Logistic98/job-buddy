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
import com.jobbuddy.backend.modules.auth.service.impl.RbacDelegationPolicy;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
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

/**
 * 动态 RBAC 管理接口。
 *
 * <p>所有查询和变更均限定在当前租户内，并由权限拦截器与委派策略共同限制可管理范围。
 */
@Tag(name = "动态 RBAC 管理接口", description = "管理当前租户的角色、菜单及其授权关系")
@RestController
@RequestMapping("/api/admin/rbac")
public class RbacAdminController {
  private final DynamicRbacService service;
  private final UserAuthRepository userRepository;
  private final RbacDelegationPolicy delegationPolicy;

  public RbacAdminController(
      DynamicRbacService service,
      UserAuthRepository userRepository,
      RbacDelegationPolicy delegationPolicy) {
    this.service = service;
    this.userRepository = userRepository;
    this.delegationPolicy = delegationPolicy;
  }

  /** 查询当前租户的角色及其菜单授权。 */
  @Operation(summary = "查询角色列表", description = "返回当前租户的全部角色及每个角色已关联的菜单标识。")
  @GetMapping("/roles")
  @RequirePermission(PermissionCodes.ROLES_MANAGE)
  public ApiResponse<List<RbacRoleResponse>> roles(HttpServletRequest request) {
    return ApiResponse.success(service.listRoles(tenant(request)));
  }

  /** 查询当前操作者可分配给角色的菜单。 */
  @Operation(summary = "查询可分配菜单", description = "根据当前操作者的委派范围返回可用于角色授权的菜单。")
  @GetMapping("/roles/menus")
  @RequirePermission(PermissionCodes.ROLES_MANAGE)
  public ApiResponse<List<RbacMenuResponse>> assignableMenus(HttpServletRequest request) {
    return ApiResponse.success(
        service.listAssignableMenus(tenant(request), AuthenticatedUserContext.user(request)));
  }

  /** 创建租户角色并保存初始菜单授权。 */
  @Operation(summary = "创建角色", description = "在当前租户创建角色，并保存请求中指定的初始菜单授权。")
  @PostMapping("/roles")
  @RequirePermission(PermissionCodes.ROLES_MANAGE)
  public ApiResponse<RbacRoleResponse> createRole(
      @RequestBody RbacRoleRequest body, HttpServletRequest request) {
    return ApiResponse.success(
        service.createRole(tenant(request), AuthenticatedUserContext.user(request), body));
  }

  /** 更新租户角色的基础信息，并在提供菜单列表时同步更新授权。 */
  @Operation(summary = "更新角色", description = "更新指定角色；请求包含 menuIds 时同时替换该角色的菜单授权。")
  @PutMapping("/roles/{roleId}")
  @RequirePermission(PermissionCodes.ROLES_MANAGE)
  public ApiResponse<RbacRoleResponse> updateRole(
      @Parameter(description = "角色标识", example = "role_recruiter") @PathVariable String roleId,
      @RequestBody RbacRoleRequest body,
      HttpServletRequest request) {
    return ApiResponse.success(
        service.updateRole(tenant(request), AuthenticatedUserContext.user(request), roleId, body));
  }

  /** 完整替换指定角色的菜单授权。 */
  @Operation(summary = "替换角色菜单", description = "使用请求中的 menuIds 完整替换指定角色的菜单授权，并自动补齐所需祖先菜单。")
  @PutMapping("/roles/{roleId}/menus")
  @RequirePermission(PermissionCodes.ROLES_MANAGE)
  public ApiResponse<RbacRoleResponse> replaceRoleMenus(
      @Parameter(description = "角色标识", example = "role_recruiter") @PathVariable String roleId,
      @RequestBody RbacRoleRequest body,
      HttpServletRequest request) {
    return ApiResponse.success(
        service.replaceRoleMenus(
            tenant(request),
            AuthenticatedUserContext.user(request),
            roleId,
            body == null ? null : body.getMenuIds()));
  }

  /** 删除未被用户引用的租户角色。 */
  @Operation(summary = "删除角色", description = "删除指定角色；仍被用户引用或受委派边界保护时拒绝操作。")
  @DeleteMapping("/roles/{roleId}")
  @RequirePermission(PermissionCodes.ROLES_MANAGE)
  public ApiResponse<BooleanResultResponse> deleteRole(
      @Parameter(description = "角色标识", example = "role_recruiter") @PathVariable String roleId,
      HttpServletRequest request) {
    service.deleteRole(tenant(request), AuthenticatedUserContext.user(request), roleId);
    return ApiResponse.success(new BooleanResultResponse(true));
  }

  /** 查询当前租户的菜单定义。 */
  @Operation(summary = "查询菜单列表", description = "返回当前租户的菜单定义，供菜单树和授权页面使用。")
  @GetMapping("/menus")
  @RequirePermission(PermissionCodes.MENUS_MANAGE)
  public ApiResponse<List<RbacMenuResponse>> menus(HttpServletRequest request) {
    return ApiResponse.success(service.listMenus(tenant(request)));
  }

  /** 创建租户菜单。 */
  @Operation(summary = "创建菜单", description = "在当前租户创建目录、页面或外链菜单。")
  @PostMapping("/menus")
  @RequirePermission(PermissionCodes.MENUS_MANAGE)
  public ApiResponse<RbacMenuResponse> createMenu(
      @RequestBody RbacMenuRequest body, HttpServletRequest request) {
    return ApiResponse.success(
        service.createMenu(tenant(request), AuthenticatedUserContext.user(request), body));
  }

  /** 更新租户菜单的展示、路由和权限映射。 */
  @Operation(summary = "更新菜单", description = "更新指定菜单的层级、展示、路由、组件和权限码配置。")
  @PutMapping("/menus/{menuId}")
  @RequirePermission(PermissionCodes.MENUS_MANAGE)
  public ApiResponse<RbacMenuResponse> updateMenu(
      @Parameter(description = "菜单标识", example = "menu_settings") @PathVariable String menuId,
      @RequestBody RbacMenuRequest body,
      HttpServletRequest request) {
    return ApiResponse.success(
        service.updateMenu(tenant(request), AuthenticatedUserContext.user(request), menuId, body));
  }

  /** 删除没有子菜单且未被角色引用的菜单。 */
  @Operation(summary = "删除菜单", description = "删除指定菜单；包含子菜单、仍被角色引用或受委派边界保护时拒绝操作。")
  @DeleteMapping("/menus/{menuId}")
  @RequirePermission(PermissionCodes.MENUS_MANAGE)
  public ApiResponse<BooleanResultResponse> deleteMenu(
      @Parameter(description = "菜单标识", example = "menu_settings") @PathVariable String menuId,
      HttpServletRequest request) {
    service.deleteMenu(tenant(request), AuthenticatedUserContext.user(request), menuId);
    return ApiResponse.success(new BooleanResultResponse(true));
  }

  /** 查询当前操作者可以委派给菜单的权限码。 */
  @Operation(summary = "查询菜单可关联的权限码", description = "根据当前操作者的委派范围过滤并返回可绑定到菜单的权限码。")
  @GetMapping("/permissions")
  @RequirePermission(PermissionCodes.MENUS_MANAGE)
  public ApiResponse<List<PermissionDefinitionResponse>> permissions(HttpServletRequest request) {
    List<PermissionDefinitionResponse> result = new ArrayList<PermissionDefinitionResponse>();
    java.util.Set<String> allowed =
        delegationPolicy.assignablePermissionCodes(
            tenant(request), AuthenticatedUserContext.user(request));
    for (Map<String, Object> row : userRepository.listPermissionDefinitions()) {
      String permissionCode = String.valueOf(row.get("permissionCode"));
      if (allowed.contains(permissionCode)) {
        result.add(
            new PermissionDefinitionResponse(
                permissionCode, String.valueOf(row.get("permissionName"))));
      }
    }
    return ApiResponse.success(result);
  }

  private String tenant(HttpServletRequest request) {
    return AuthenticatedUserContext.tenantId(request);
  }
}
