package com.jobbuddy.backend.modules.auth.service;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.request.ManagedUserCreateRequest;
import com.jobbuddy.backend.modules.auth.dto.request.ManagedUserUpdateRequest;
import com.jobbuddy.backend.modules.auth.dto.response.ManagedUserResponse;
import java.util.List;

/**
 * 在操作者角色委派边界内提供租户隔离的用户管理。
 */
public interface TenantUserAdminService {
  /**
   * 查询用户列表。
   *
   * @param tenantId 租户标识
   * @return 用户列表
   */
  List<ManagedUserResponse> listUsers(String tenantId);

  /**
   * 创建租户用户。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param request 请求对象
   * @return 创建后的资源数据
   */
  ManagedUserResponse create(
      String tenantId, AuthenticatedUser actor, ManagedUserCreateRequest request);

  /**
   * 更新租户用户。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param userId 用户标识
   * @param request 请求对象
   * @return 更新后的租户用户
   */
  ManagedUserResponse update(
      String tenantId, AuthenticatedUser actor, String userId, ManagedUserUpdateRequest request);

  /**
   * 替换角色。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param userId 用户标识
   * @param roleIds 角色标识列表
   * @return 更新后的用户角色
   */
  ManagedUserResponse replaceRoles(
      String tenantId, AuthenticatedUser actor, String userId, List<String> roleIds);

  /**
   * 校验旧密码并修改密码。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @param userId 用户标识
   * @param oldPassword 旧密码
   * @param newPassword 新密码
   */
  void changePassword(
      String tenantId,
      AuthenticatedUser actor,
      String userId,
      String oldPassword,
      String newPassword);
}
