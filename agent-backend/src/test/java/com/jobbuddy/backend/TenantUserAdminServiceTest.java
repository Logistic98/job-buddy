package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.modules.auth.dto.request.ManagedUserUpdateRequest;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import com.jobbuddy.backend.modules.auth.service.DynamicRbacService;
import com.jobbuddy.backend.modules.auth.service.TenantUserAdminService;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import com.jobbuddy.backend.modules.auth.service.impl.TenantUserAdminServiceImpl;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class TenantUserAdminServiceTest {
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
        new TenantUserAdminServiceImpl(repository, loginService, rbacService);
    ManagedUserUpdateRequest request = new ManagedUserUpdateRequest();
    request.setEnabled(false);

    assertThrows(
        IllegalArgumentException.class, () -> service.update("tenant-1", "manager-1", request));
    verify(rbacService).protectManagementAccess("tenant-1");
  }

  @Test
  void passwordMustBeBetweenEightAndSixteenCharacters() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    when(repository.findUserById("tenant-1", "user-1")).thenReturn(user("user-1", true));
    TenantUserAdminService service =
        new TenantUserAdminServiceImpl(
            repository, mock(UserLoginService.class), mock(DynamicRbacService.class));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.resetPassword("tenant-1", "user-1", "1234567"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.resetPassword("tenant-1", "user-1", "12345678901234567"));
    verify(repository, never()).updatePasswordHash(anyString(), anyString());

    service.resetPassword("tenant-1", "user-1", "12345678");
    service.resetPassword("tenant-1", "user-1", "1234567890123456");
    verify(repository, times(2))
        .updatePasswordHash(org.mockito.ArgumentMatchers.eq("user-1"), anyString());
  }

  @Test
  void cannotManageUserFromAnotherTenant() {
    UserAuthRepository repository = mock(UserAuthRepository.class);
    TenantUserAdminService service =
        new TenantUserAdminServiceImpl(
            repository, mock(UserLoginService.class), mock(DynamicRbacService.class));
    ManagedUserUpdateRequest request = new ManagedUserUpdateRequest();
    request.setEnabled(false);
    assertThrows(
        IllegalArgumentException.class, () -> service.update("tenant-1", "foreign-user", request));
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
}
