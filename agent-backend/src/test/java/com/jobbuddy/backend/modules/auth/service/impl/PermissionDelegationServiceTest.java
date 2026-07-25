package com.jobbuddy.backend.modules.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.response.PermissionDefinitionResponse;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * 验证 PermissionDelegationService 的核心行为、异常路径与边界条件。
 */
class PermissionDelegationServiceTest {

  /**
   * 验证 PermissionDelegationService 中权限的权限与租户隔离边界。
   */
  @Test
  void filtersPermissionDefinitionsWithoutChangingRepositoryOrder() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    RbacDelegationPolicy policy = mock(RbacDelegationPolicy.class);
    AuthenticatedUser actor = new AuthenticatedUser();
    actor.setTenantId("tenant-a");
    actor.setUserId("manager-a");
    when(policy.assignablePermissionCodes("tenant-a", actor))
        .thenReturn(Set.of("resume:use", "job:use"));
    when(repository.listPermissionDefinitions())
        .thenReturn(
            List.of(
                permission("resume:use", "简历使用"),
                permission("system:admin", "系统管理"),
                permission("job:use", "岗位使用")));
    PermissionDelegationService service = new PermissionDelegationService(repository, policy);

    List<PermissionDefinitionResponse> result =
        service.listAssignablePermissions("tenant-a", actor);

    assertEquals(2, result.size());
    assertEquals("resume:use", result.get(0).getPermissionCode());
    assertEquals("job:use", result.get(1).getPermissionCode());
  }

  /**
   * 验证权限。
   *
   * @param code 编码
   * @param name 名称
   * @return 测试权限
   */
  private Map<String, Object> permission(String code, String name) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("permissionCode", code);
    row.put("permissionName", name);
    return row;
  }
}
