package com.jobbuddy.backend.modules.auth.service.impl;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.response.PermissionDefinitionResponse;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 在业务层集中执行权限定义查询与委派范围过滤。 */
@Service
public class PermissionDelegationService {
  private final UserAuthRepository userAuthRepository;
  private final RbacDelegationPolicy delegationPolicy;

  public PermissionDelegationService(
      UserAuthRepository userAuthRepository, RbacDelegationPolicy delegationPolicy) {
    this.userAuthRepository = userAuthRepository;
    this.delegationPolicy = delegationPolicy;
  }

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
