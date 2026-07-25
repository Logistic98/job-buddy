package com.jobbuddy.backend.modules.auth.controller;

import com.jobbuddy.backend.common.dto.response.BooleanResultResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.common.security.PermissionCodes;
import com.jobbuddy.backend.common.security.RequirePermission;
import com.jobbuddy.backend.modules.auth.dto.request.ManagedUserCreateRequest;
import com.jobbuddy.backend.modules.auth.dto.request.ManagedUserUpdateRequest;
import com.jobbuddy.backend.modules.auth.dto.request.PasswordResetRequest;
import com.jobbuddy.backend.modules.auth.dto.request.UserRolesRequest;
import com.jobbuddy.backend.modules.auth.dto.response.ManagedUserResponse;
import com.jobbuddy.backend.modules.auth.dto.response.RbacRoleResponse;
import com.jobbuddy.backend.modules.auth.service.DynamicRbacService;
import com.jobbuddy.backend.modules.auth.service.TenantUserAdminService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 租户内用户管理接口，所有操作统一要求用户管理权限。
 */
@Tag(name = "租户用户管理接口")
@RestController
@RequestMapping("/api/admin/users")
@RequirePermission(PermissionCodes.USERS_MANAGE)
public class TenantUserAdminController {
  private final TenantUserAdminService service;
  private final DynamicRbacService rbacService;

  /**
   * 创建租户用户管理接口实例。
   *
   * @param service 服务
   * @param rbacService RBAC 服务
   */
  public TenantUserAdminController(TenantUserAdminService service, DynamicRbacService rbacService) {
    this.service = service;
    this.rbacService = rbacService;
  }

  /**
   * 查询本租户用户。
   *
   * @param request 请求对象
   * @return 本租户用户
   */
  @Operation(summary = "查询本租户用户")
  @GetMapping
  public ApiResponse<List<ManagedUserResponse>> users(HttpServletRequest request) {
    return ApiResponse.success(service.listUsers(AuthenticatedUserContext.tenantId(request)));
  }

  /**
   * 查询用户可绑定的角色。
   *
   * @param request 请求对象
   * @return 用户可绑定的角色
   */
  @Operation(summary = "查询用户可绑定的角色")
  @GetMapping("/roles")
  public ApiResponse<List<RbacRoleResponse>> roles(HttpServletRequest request) {
    return ApiResponse.success(
        rbacService.listAssignableRoles(
            AuthenticatedUserContext.tenantId(request), AuthenticatedUserContext.user(request)));
  }

  /**
   * 创建本租户用户。
   *
   * @param body 请求体
   * @param request 请求对象
   * @return 创建后的资源数据
   */
  @Operation(summary = "创建本租户用户")
  @PostMapping
  public ApiResponse<ManagedUserResponse> create(
      @RequestBody ManagedUserCreateRequest body, HttpServletRequest request) {
    return ApiResponse.success(
        service.create(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.user(request),
            body));
  }

  /**
   * 更新本租户用户。
   *
   * @param userId 用户标识
   * @param body 请求体
   * @param request 请求对象
   * @return 更新后的本租户用户
   */
  @Operation(summary = "更新本租户用户")
  @PutMapping("/{userId}")
  public ApiResponse<ManagedUserResponse> update(
      @PathVariable String userId,
      @RequestBody ManagedUserUpdateRequest body,
      HttpServletRequest request) {
    return ApiResponse.success(
        service.update(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.user(request),
            userId,
            body));
  }

  /**
   * 替换用户角色。
   *
   * @param userId 用户标识
   * @param body 请求体
   * @param request 请求对象
   * @return 更新后的用户角色
   */
  @Operation(summary = "替换用户角色")
  @PutMapping("/{userId}/roles")
  public ApiResponse<ManagedUserResponse> replaceRoles(
      @PathVariable String userId, @RequestBody UserRolesRequest body, HttpServletRequest request) {
    return ApiResponse.success(
        service.replaceRoles(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.user(request),
            userId,
            body == null ? null : body.getRoleIds()));
  }

  /**
   * 重置本租户用户密码。
   *
   * @param userId 用户标识
   * @param body 请求体
   * @param request 请求对象
   * @return 密码重置结果
   */
  @Operation(summary = "重置本租户用户密码")
  @PutMapping("/{userId}/password")
  public ApiResponse<BooleanResultResponse> resetPassword(
      @PathVariable String userId,
      @RequestBody PasswordResetRequest body,
      HttpServletRequest request) {
    service.resetPassword(
        AuthenticatedUserContext.tenantId(request),
        AuthenticatedUserContext.user(request),
        userId,
        body == null ? null : body.getPassword());
    return ApiResponse.success(new BooleanResultResponse(true));
  }
}
