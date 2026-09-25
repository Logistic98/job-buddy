package com.jobbuddy.backend.modules.auth.service.impl;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.exception.AuthorizationDeniedException;
import com.jobbuddy.backend.modules.auth.mapper.RbacMapper;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RbacDelegationPolicyTest {

  @Test
  void assignableRolesLoadPermissionsInBatches() {
    RbacMapper mapper = mock(RbacMapper.class);
    UserAuthRepository users = mock(UserAuthRepository.class);
    when(mapper.listRoles("tenant-a"))
        .thenReturn(
            List.of(
                Map.of("roleId", "role-basic"),
                Map.of("roleId", "role-user-manager"),
                Map.of("roleId", "role-role-manager")));
    when(mapper.listRolePermissionAssignments("tenant-a"))
        .thenReturn(
            List.of(
                Map.of("roleId", "role-user-manager", "permissionCode", "users:manage"),
                Map.of("roleId", "role-role-manager", "permissionCode", "roles:manage")));
    when(users.listPermissionDefinitions())
        .thenReturn(List.of(permission("users:manage", true), permission("roles:manage", true)));
    RbacDelegationPolicy policy = new RbacDelegationPolicy(mapper, users);

    var assignable = policy.assignableRoleIds("tenant-a", actor("manager", Set.of("users:manage")));

    assertEquals(List.of("role-basic", "role-user-manager"), assignable);
    verify(users, times(1)).listPermissionDefinitions();
    verify(mapper, never())
        .findRoleMenuIds(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
    verify(mapper, never())
        .findMenu(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void platformAdministratorCanAssignProtectedAdministratorRole() {
    RbacMapper mapper = mock(RbacMapper.class);
    UserAuthRepository users = mock(UserAuthRepository.class);
    when(mapper.listRoles("tenant-a"))
        .thenReturn(List.of(Map.of("roleId", "role-admin"), Map.of("roleId", "role-user")));
    when(mapper.listRolePermissionAssignments("tenant-a"))
        .thenReturn(
            List.of(
                Map.of("roleId", "role-admin", "permissionCode", "platform:manage"),
                Map.of("roleId", "role-user", "permissionCode", "chat:use")));
    when(users.listPermissionDefinitions())
        .thenReturn(List.of(permission("platform:manage", false), permission("chat:use", true)));
    RbacDelegationPolicy policy = new RbacDelegationPolicy(mapper, users);

    var assignable =
        policy.assignableRoleIds(
            "tenant-a", actor("platform-admin", Set.of("platform:manage", "chat:use")));

    assertEquals(List.of("role-admin", "role-user"), assignable);
  }

  @Test
  void assignableMenusReadPermissionDefinitionsOnce() {
    RbacMapper mapper = mock(RbacMapper.class);
    UserAuthRepository users = mock(UserAuthRepository.class);
    when(mapper.listMenus("tenant-a"))
        .thenReturn(
            List.of(
                Map.of("menuId", "menu-basic", "permissionCode", ""),
                Map.of("menuId", "menu-users", "permissionCode", "users:manage"),
                Map.of("menuId", "menu-roles", "permissionCode", "roles:manage")));
    when(users.listPermissionDefinitions())
        .thenReturn(List.of(permission("users:manage", true), permission("roles:manage", true)));
    RbacDelegationPolicy policy = new RbacDelegationPolicy(mapper, users);

    var assignable = policy.assignableMenuIds("tenant-a", actor("manager", Set.of("users:manage")));

    assertEquals(List.of("menu-basic", "menu-users"), assignable);
    verify(users, times(1)).listPermissionDefinitions();
  }

  @Test
  void cannotAssignRoleWithPermissionActorDoesNotOwn() {
    RbacMapper mapper = mock(RbacMapper.class);
    UserAuthRepository users = mock(UserAuthRepository.class);
    when(users.listPermissionDefinitions())
        .thenReturn(List.of(permission("users:manage", true), permission("roles:manage", true)));
    when(users.findPermissions("target")).thenReturn(List.of());
    when(mapper.findRoleMenuIds("tenant-a", "role-manager")).thenReturn(List.of("menu-roles"));
    when(mapper.findMenu("tenant-a", "menu-roles"))
        .thenReturn(Map.of("menuId", "menu-roles", "permissionCode", "roles:manage"));
    RbacDelegationPolicy policy = new RbacDelegationPolicy(mapper, users);

    assertThrows(
        AuthorizationDeniedException.class,
        () ->
            policy.validateUserRoleChange(
                "tenant-a",
                actor("manager", Set.of("users:manage")),
                "target",
                List.of("role-manager")));
  }

  @Test
  void canResetAnyPasswordWithinActorTenant() {
    RbacMapper mapper = mock(RbacMapper.class);
    UserAuthRepository users = mock(UserAuthRepository.class);
    RbacDelegationPolicy policy = new RbacDelegationPolicy(mapper, users);
    AuthenticatedUser actor = actor("manager", Set.of("users:manage", "roles:manage"));

    assertDoesNotThrow(() -> policy.validatePasswordChange("tenant-a", actor, "manager"));
    assertDoesNotThrow(() -> policy.validatePasswordChange("tenant-a", actor, "peer"));
    assertDoesNotThrow(() -> policy.validatePasswordChange("tenant-a", actor, "lower"));
    verify(users, never()).findPermissions(org.mockito.ArgumentMatchers.anyString());
  }

  @Test
  void platformAdministratorCanDelegateOwnedProtectedPermission() {
    RbacMapper mapper = mock(RbacMapper.class);
    UserAuthRepository users = mock(UserAuthRepository.class);
    when(users.listPermissionDefinitions())
        .thenReturn(List.of(permission("platform:manage", false)));
    when(users.findPermissions("target")).thenReturn(List.of());
    when(mapper.findRoleMenuIds("tenant-a", "platform-role")).thenReturn(List.of("platform-menu"));
    when(mapper.findMenu("tenant-a", "platform-menu"))
        .thenReturn(Map.of("menuId", "platform-menu", "permissionCode", "platform:manage"));
    RbacDelegationPolicy policy = new RbacDelegationPolicy(mapper, users);

    assertDoesNotThrow(
        () ->
            policy.validateUserRoleChange(
                "tenant-a",
                actor("manager", Set.of("platform:manage")),
                "target",
                List.of("platform-role")));
  }

  /**
   * 验证操作人。
   *
   * @param userId 用户标识
   * @param permissions 权限列表
   * @return 测试操作用户
   */
  private AuthenticatedUser actor(String userId, Set<String> permissions) {
    AuthenticatedUser actor = new AuthenticatedUser();
    actor.setUserId(userId);
    actor.setTenantId("tenant-a");
    actor.setPermissions(permissions);
    return actor;
  }

  @Test
  void roleAndMenuChangesRejectProtectedOrUnownedPermissions() {
    RbacMapper mapper = mock(RbacMapper.class);
    UserAuthRepository users = mock(UserAuthRepository.class);
    when(users.listPermissionDefinitions())
        .thenReturn(
            List.of(
                permission("chat:use", true),
                permission("roles:manage", true),
                permission("platform:manage", false)));
    when(mapper.findMenu("tenant-a", "chat")).thenReturn(Map.of("permissionCode", "chat:use"));
    when(mapper.findMenu("tenant-a", "roles")).thenReturn(Map.of("permissionCode", "roles:manage"));
    when(mapper.findMenu("tenant-a", "platform"))
        .thenReturn(Map.of("permissionCode", "platform:manage"));
    when(mapper.findMenu("tenant-a", "missing")).thenReturn(null);
    RbacDelegationPolicy policy = new RbacDelegationPolicy(mapper, users);
    var manager = actor("manager", Set.of("chat:use"));
    assertDoesNotThrow(
        () -> policy.validateRoleMenuChange("tenant-a", manager, List.of(), List.of("chat")));
    assertThrows(
        AuthorizationDeniedException.class,
        () -> policy.validateRoleMenuChange("tenant-a", manager, List.of(), List.of("roles")));
    assertThrows(
        AuthorizationDeniedException.class,
        () -> policy.validateRoleMenuChange("tenant-a", manager, List.of("platform"), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> policy.validateRoleMenuChange("tenant-a", manager, List.of(), List.of("missing")));
    assertThrows(
        AuthorizationDeniedException.class,
        () -> policy.validateMenuPermissionChange("tenant-a", manager, "platform:manage", ""));
    assertThrows(
        AuthorizationDeniedException.class,
        () -> policy.validateMenuPermissionChange("tenant-a", manager, "chat:use", "roles:manage"));
    assertDoesNotThrow(
        () -> policy.validateMenuPermissionChange("tenant-a", manager, "chat:use", "chat:use"));
    assertEquals(Set.of("chat:use"), policy.assignablePermissionCodes("tenant-a", manager));
    assertThrows(
        AuthorizationDeniedException.class, () -> policy.requireActorTenant("other", manager));
    assertThrows(
        AuthorizationDeniedException.class, () -> policy.requireActorTenant("tenant-a", null));
  }

  @Test
  void userRoleChangesRejectSelfPeersAndProtectedTargets() {
    RbacMapper mapper = mock(RbacMapper.class);
    UserAuthRepository users = mock(UserAuthRepository.class);
    when(users.listPermissionDefinitions())
        .thenReturn(
            List.of(
                permission("chat:use", true),
                permission("roles:manage", true),
                permission("platform:manage", false)));
    RbacDelegationPolicy policy = new RbacDelegationPolicy(mapper, users);
    var manager = actor("manager", Set.of("chat:use", "roles:manage"));
    assertThrows(
        AuthorizationDeniedException.class,
        () -> policy.validateUserRoleChange("tenant-a", manager, "manager", List.of()));
    when(users.findPermissions("target")).thenReturn(List.of("chat:use", "roles:manage"));
    assertThrows(
        AuthorizationDeniedException.class,
        () -> policy.validateUserRoleChange("tenant-a", manager, "target", List.of()));
    when(users.findPermissions("target")).thenReturn(List.of("platform:manage"));
    assertThrows(
        AuthorizationDeniedException.class,
        () -> policy.validateUserRoleChange("tenant-a", manager, "target", List.of()));
    when(users.findPermissions("target")).thenReturn(List.of("chat:use"));
    assertDoesNotThrow(
        () -> policy.validateUserRoleChange("tenant-a", manager, "target", List.of()));
  }

  /**
   * 验证权限。
   *
   * @param code 编码
   * @param grantable 可授予权限集合
   * @return 测试权限
   */
  private Map<String, Object> permission(String code, boolean grantable) {
    return Map.of("permissionCode", code, "grantable", grantable);
  }
}
