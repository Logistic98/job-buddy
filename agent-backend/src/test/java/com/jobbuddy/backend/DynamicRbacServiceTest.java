package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.modules.auth.dto.request.RbacMenuRequest;
import com.jobbuddy.backend.modules.auth.mapper.RbacMapper;
import com.jobbuddy.backend.modules.auth.service.DynamicRbacService;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import com.jobbuddy.backend.modules.auth.service.impl.DynamicRbacServiceImpl;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class DynamicRbacServiceTest {
  @Test
  void rejectsCrossTenantRolesWhenReplacingUserRoles() {
    RbacMapper mapper = mock(RbacMapper.class);
    when(mapper.countRolesByIds("tenant-a", Arrays.asList("role-b"))).thenReturn(0);
    DynamicRbacService service = new DynamicRbacServiceImpl(mapper, mock(UserLoginService.class));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.replaceUserRoles("tenant-a", "user-a", Arrays.asList("role-b")));
    verify(mapper, never()).deleteUserRoles("tenant-a", "user-a");
  }

  @Test
  void rejectsMenuParentCycle() {
    RbacMapper mapper = mock(RbacMapper.class);
    when(mapper.findMenu("tenant-a", "menu-a")).thenReturn(menu("menu-a", ""));
    when(mapper.findMenu("tenant-a", "menu-b")).thenReturn(menu("menu-b", "menu-a"));
    DynamicRbacService service = new DynamicRbacServiceImpl(mapper, mock(UserLoginService.class));
    RbacMenuRequest request = request("menu-a");

    assertThrows(
        IllegalArgumentException.class, () -> service.updateMenu("tenant-a", "menu-a", request));
    verify(mapper, never())
        .updateMenu(
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.anyInt(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.anyBoolean(),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void roleMenuAssignmentAutomaticallyIncludesParentMenus() {
    RbacMapper mapper = mock(RbacMapper.class);
    when(mapper.findRole("tenant-a", "role-a")).thenReturn(role("role-a"));
    when(mapper.countMenusByIds("tenant-a", Arrays.asList("menu-child"))).thenReturn(1);
    when(mapper.findMenu("tenant-a", "menu-child")).thenReturn(menu("menu-child", "menu-parent"));
    when(mapper.findMenu("tenant-a", "menu-parent")).thenReturn(menu("menu-parent", ""));
    when(mapper.countManagementUsers("tenant-a")).thenReturn(1);
    DynamicRbacService service = new DynamicRbacServiceImpl(mapper, mock(UserLoginService.class));

    service.replaceRoleMenus("tenant-a", "role-a", Arrays.asList("menu-child"));

    verify(mapper)
        .insertRoleMenu(
            org.mockito.ArgumentMatchers.eq("tenant-a"),
            org.mockito.ArgumentMatchers.eq("role-a"),
            org.mockito.ArgumentMatchers.eq("menu-child"),
            org.mockito.ArgumentMatchers.any());
    verify(mapper)
        .insertRoleMenu(
            org.mockito.ArgumentMatchers.eq("tenant-a"),
            org.mockito.ArgumentMatchers.eq("role-a"),
            org.mockito.ArgumentMatchers.eq("menu-parent"),
            org.mockito.ArgumentMatchers.any());
  }

  @Test
  void rejectsUnsafeExternalMenuUrls() {
    DynamicRbacService service =
        new DynamicRbacServiceImpl(mock(RbacMapper.class), mock(UserLoginService.class));
    RbacMenuRequest request = request("");
    request.setMenuType("external");
    request.setExternalUrl("javascript:alert(1)");
    assertThrows(IllegalArgumentException.class, () -> service.createMenu("tenant-a", request));
  }

  @Test
  void rejectsDeletingReferencedRole() {
    RbacMapper mapper = mock(RbacMapper.class);
    when(mapper.findRole("tenant-a", "role-a")).thenReturn(role("role-a"));
    when(mapper.countRoleUsers("tenant-a", "role-a")).thenReturn(1);
    DynamicRbacService service = new DynamicRbacServiceImpl(mapper, mock(UserLoginService.class));

    assertThrows(IllegalArgumentException.class, () -> service.deleteRole("tenant-a", "role-a"));
    verify(mapper, never()).deleteRole("tenant-a", "role-a");
  }

  @Test
  void protectsLastEffectiveManagementAccount() {
    RbacMapper mapper = mock(RbacMapper.class);
    when(mapper.countManagementUsers("tenant-a")).thenReturn(0);
    DynamicRbacService service = new DynamicRbacServiceImpl(mapper, mock(UserLoginService.class));
    assertThrows(IllegalArgumentException.class, () -> service.protectManagementAccess("tenant-a"));
  }

  private RbacMenuRequest request(String parentId) {
    RbacMenuRequest request = new RbacMenuRequest();
    request.setParentId(parentId);
    request.setMenuCode("menu-code");
    request.setMenuName("Menu");
    request.setMenuType("page");
    request.setVisible(true);
    request.setEnabled(true);
    return request;
  }

  private Map<String, Object> menu(String id, String parent) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("menuId", id);
    row.put("parentId", parent);
    row.put("enabled", true);
    row.put("visible", true);
    return row;
  }

  private Map<String, Object> role(String id) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("roleId", id);
    row.put("enabled", true);
    return row;
  }
}
