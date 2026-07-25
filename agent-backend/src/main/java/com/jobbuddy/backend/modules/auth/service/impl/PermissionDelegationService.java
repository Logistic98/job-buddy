package com.jobbuddy.backend.modules.auth.service.impl;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.response.PermissionDefinitionResponse;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/**
 * 在业务层集中执行权限定义查询与委派范围过滤。
 */
@Service
public class PermissionDelegationService {
  private final UserAuthRepository userAuthRepository;
  private final RbacDelegationPolicy delegationPolicy;

  /**
   * 创建权限委派服务实例。
   *
   * @param userAuthRepository 用户认证存储访问
   * @param delegationPolicy 委派策略
   */
  public PermissionDelegationService(
      UserAuthRepository userAuthRepository, RbacDelegationPolicy delegationPolicy) {
    this.userAuthRepository = userAuthRepository;
    this.delegationPolicy = delegationPolicy;
  }

  /**
   * 查询可分配权限列表。
   *
   * @param tenantId 租户标识
   * @param actor 操作人
   * @return 可分配权限列表
   */
  public List<PermissionDefinitionResponse> listAssignablePermissions(
      String tenantId, AuthenticatedUser actor) {
    Set<String> allowed = delegationPolicy.assignablePermissionCodes(tenantId, actor);
    List<PermissionDefinitionResponse> result = new ArrayList<PermissionDefinitionResponse>();
    for (Map<String, Object> row : userAuthRepository.listPermissionDefinitions()) {
      String permissionCode = String.valueOf(row.get("permissionCode"));
      if (allowed.contains(permissionCode)) {
        result.add(
            new PermissionDefinitionResponse(
                permissionCode, String.valueOf(row.get("permissionName"))));
      }
    }
    return result;
  }
}
