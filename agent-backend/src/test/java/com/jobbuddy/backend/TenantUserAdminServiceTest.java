package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.request.ManagedUserUpdateRequest;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import com.jobbuddy.backend.modules.auth.service.DynamicRbacService;
import com.jobbuddy.backend.modules.auth.service.TenantUserAdminService;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import com.jobbuddy.backend.modules.auth.service.impl.RbacDelegationPolicy;
import com.jobbuddy.backend.modules.auth.service.impl.TenantUserAdminServiceImpl;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenantUserAdminServiceTest {
  @Test
  void listUsersLoadsRolesAndPermissionsWithBatchQueries() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    DynamicRbacService rbacService = mock(DynamicRbacService.class);
    when(repository.listUsers("tenant-1"))
        .thenReturn(List.of(user("user-1", true), user("user-2", false)));
    when(repository.listUserRoleAssignments("tenant-1"))
        .thenReturn(
            List.of(
                Map.of("userId", "user-1", "roleId", "role-a", "roleName", "Role A"),
                Map.of("userId", "user-1", "roleId", "role-b", "roleName", "Role B")));
    when(repository.listUserEffectivePermissionAssignments("tenant-1"))
        .thenReturn(
            List.of(
                Map.of("userId", "user-1", "permissionCode", "users:manage"),
                Map.of("userId", "user-2", "permissionCode", "jobs:read")));
    TenantUserAdminService service =
        new TenantUserAdminServiceImpl(
            repository,
            mock(UserLoginService.class),
            rbacService,
            mock(RbacDelegationPolicy.class));

    var users = service.listUsers("tenant-1");

    assertEquals(2, users.size());
    assertEquals(List.of("role-a", "role-b"), users.get(0).getRoleIds());
    assertEquals(List.of("Role A", "Role B"), users.get(0).getRoleNames());
    assertEquals(List.of("users:manage"), users.get(0).getPermissions());
    assertEquals(List.of(), users.get(1).getRoleIds());
    assertEquals(List.of("jobs:read"), users.get(1).getPermissions());
    verify(rbacService, never()).userRoleIds(anyString(), anyString());
    verify(rbacService, never()).userRoleNames(anyString(), anyString());
    verify(repository, never()).findPermissions(anyString());
  }

  @Test
  void disablingUserMustPreserveAtLeastOneManagementAccount() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    UserLoginService loginService = mock(UserLoginService.class);
    DynamicRbacService rbacService = mock(DynamicRbacService.class);
    when(repository.findUserById("tenant-1", "manager-1")).thenReturn(user("manager-1", true));
    org.mockito.Mockito.doThrow(new IllegalArgumentException("must keep manager"))
        .when(rbacService)
        .protectManagementAccess("tenant-1");
    TenantUserAdminService service =
        new TenantUserAdminServiceImpl(
            repository, loginService, rbacService, mock(RbacDelegationPolicy.class));
    ManagedUserUpdateRequest request = new ManagedUserUpdateRequest();
    request.setEnabled(false);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.update("tenant-1", actor(), "manager-1", request));
    verify(rbacService).protectManagementAccess("tenant-1");
  }

  @Test
  void updateChangesGloballyUniqueUsernameAndKeepsUnchangedRoles() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    UserLoginService loginService = mock(UserLoginService.class);
    DynamicRbacService rbacService = mock(DynamicRbacService.class);
    when(repository.findUserById("tenant-1", "manager-1")).thenReturn(user("manager-1", true));
    when(rbacService.userRoleIds("tenant-1", "manager-1")).thenReturn(List.of("role-admin"));
    ManagedUserUpdateRequest request = new ManagedUserUpdateRequest();
    request.setUsername("manager_new");
    request.setDisplayName("New Manager");
    request.setEnabled(true);
    request.setRoleIds(List.of("role-admin"));
    TenantUserAdminService service =
        new TenantUserAdminServiceImpl(
            repository, loginService, rbacService, mock(RbacDelegationPolicy.class));

    service.update("tenant-1", actor(), "manager-1", request);

    verify(repository)
        .updateUser("tenant-1", "manager-1", "manager_new", "New Manager", "user", true);
    verify(rbacService, never())
        .replaceUserRoles(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyList());
    verify(loginService).invalidateUserSessions("manager-1");
  }

  @Test
  void updateRejectsUsernameOwnedByAnotherUserIgnoringCase() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    when(repository.findUserById("tenant-1", "manager-1")).thenReturn(user("manager-1", true));
    when(repository.findUserByUsername("TakenUser")).thenReturn(user("other-user", true));
    ManagedUserUpdateRequest request = new ManagedUserUpdateRequest();
    request.setUsername("TakenUser");
    TenantUserAdminService service =
        new TenantUserAdminServiceImpl(
            repository,
            mock(UserLoginService.class),
            mock(DynamicRbacService.class),
            mock(RbacDelegationPolicy.class));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.update("tenant-1", actor(), "manager-1", request));
  }

  @Test
  void passwordMustBeBetweenEightAndSixteenCharacters() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    when(repository.findUserById("tenant-1", "user-1")).thenReturn(user("user-1", true));
    TenantUserAdminService service =
        new TenantUserAdminServiceImpl(
            repository,
            mock(UserLoginService.class),
            mock(DynamicRbacService.class),
            mock(RbacDelegationPolicy.class));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.resetPassword("tenant-1", actor(), "user-1", "1234567"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.resetPassword("tenant-1", actor(), "user-1", "12345678901234567"));
    verify(repository, never()).updatePasswordHash(anyString(), anyString());

    service.resetPassword("tenant-1", actor(), "user-1", "12345678");
    service.resetPassword("tenant-1", actor(), "user-1", "1234567890123456");
    verify(repository, times(2))
        .updatePasswordHash(org.mockito.ArgumentMatchers.eq("user-1"), anyString());
  }

  @Test
  void cannotManageUserFromAnotherTenant() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    TenantUserAdminService service =
        new TenantUserAdminServiceImpl(
            repository,
            mock(UserLoginService.class),
            mock(DynamicRbacService.class),
            mock(RbacDelegationPolicy.class));
    ManagedUserUpdateRequest request = new ManagedUserUpdateRequest();
    request.setEnabled(false);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.update("tenant-1", actor(), "foreign-user", request));
  }

  private Map<String, Object> user(String userId, boolean enabled) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("userId", userId);
    row.put("username", "manager");
    row.put("displayName", "Manager");
    row.put("role", "user");
    row.put("enabled", enabled);
    return row;
  }

  private AuthenticatedUser actor() {
    AuthenticatedUser actor = new AuthenticatedUser();
    actor.setUserId("manager-1");
    actor.setTenantId("tenant-1");
    return actor;
  }
}
