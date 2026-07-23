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
  void cannotResetSelfOrPeerPasswordButCanResetStrictlyLowerAccount() {
    RbacMapper mapper = mock(RbacMapper.class);
    UserAuthRepository users = mock(UserAuthRepository.class);
    when(users.listPermissionDefinitions())
        .thenReturn(List.of(permission("users:manage", true), permission("roles:manage", true)));
    when(users.findPermissions("peer")).thenReturn(List.of("users:manage", "roles:manage"));
    when(users.findPermissions("lower")).thenReturn(List.of("users:manage"));
    RbacDelegationPolicy policy = new RbacDelegationPolicy(mapper, users);
    AuthenticatedUser actor = actor("manager", Set.of("users:manage", "roles:manage"));

    assertThrows(
        AuthorizationDeniedException.class,
        () -> policy.validatePasswordReset("tenant-a", actor, "manager"));
    assertThrows(
        AuthorizationDeniedException.class,
        () -> policy.validatePasswordReset("tenant-a", actor, "peer"));
    assertDoesNotThrow(() -> policy.validatePasswordReset("tenant-a", actor, "lower"));
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

  private AuthenticatedUser actor(String userId, Set<String> permissions) {
    AuthenticatedUser actor = new AuthenticatedUser();
    actor.setUserId(userId);
    actor.setTenantId("tenant-a");
    actor.setPermissions(permissions);
    return actor;
  }

  private Map<String, Object> permission(String code, boolean grantable) {
    return Map.of("permissionCode", code, "grantable", grantable);
  }
}
