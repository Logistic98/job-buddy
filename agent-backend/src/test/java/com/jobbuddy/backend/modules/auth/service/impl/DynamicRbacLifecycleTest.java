package com.jobbuddy.backend.modules.auth.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.request.RbacMenuRequest;
import com.jobbuddy.backend.modules.auth.dto.request.RbacRoleRequest;
import com.jobbuddy.backend.modules.auth.mapper.RbacMapper;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.dao.DataIntegrityViolationException;

class DynamicRbacLifecycleTest {
  private final RbacMapper mapper = mock(RbacMapper.class);
  private final UserLoginService login = mock(UserLoginService.class);
  private final RbacDelegationPolicy policy = mock(RbacDelegationPolicy.class);
  private final DynamicRbacServiceImpl service = new DynamicRbacServiceImpl(mapper, login, policy);
  private final AuthenticatedUser actor = new AuthenticatedUser();

  @Test
  void roleCreationNormalizesAndPersistsOnlyValidatedAssignment() {
    AtomicReference<Map<String, Object>> saved = new AtomicReference<>();
    doAnswer(
            call -> {
              saved.set(call.getArgument(0));
              return 1;
            })
        .when(mapper)
        .insertRole(any());
    when(mapper.findRole(eq("tenant"), anyString())).thenAnswer(call -> saved.get());
    var response = service.createRole("tenant", actor, roleRequest());
    assertEquals("editor", response.getRoleCode());
    assertEquals("Editor", response.getRoleName());
    assertTrue(response.isEnabled());
    assertEquals("tenant", saved.get().get("tenantId"));
    verify(policy).validateRoleMenuChange("tenant", actor, List.of(), List.of());
    verify(mapper).deleteRoleMenus("tenant", response.getRoleId());
  }

  @Test
  void roleUpdateEvictsEachAffectedUserOnce() {
    when(mapper.findRole("tenant", "role")).thenReturn(Map.of("roleId", "role", "enabled", true));
    when(mapper.findUserIdsByRole("tenant", "role")).thenReturn(List.of("user", "user"));
    when(mapper.countManagementUsers("tenant")).thenReturn(1);
    service.updateRole("tenant", actor, "role", roleRequest());
    verify(policy).validateRoleMenuChange("tenant", actor, List.of(), List.of());
    verify(login, times(1)).evictUserSessionCache("user");
    verify(mapper)
        .updateRole(eq("tenant"), eq("role"), eq("editor"), eq("Editor"), eq(""), eq(true), any());
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void referencedRoleCannotBeDeleted(boolean referenced) {
    when(mapper.findRole("tenant", "role")).thenReturn(Map.of("roleId", "role"));
    when(mapper.countRoleUsers("tenant", "role")).thenReturn(referenced ? 1 : 0);
    when(mapper.countManagementUsers("tenant")).thenReturn(1);
    if (referenced)
      assertThrows(
          IllegalArgumentException.class, () -> service.deleteRole("tenant", actor, "role"));
    else service.deleteRole("tenant", actor, "role");
    verify(mapper, times(referenced ? 0 : 1)).deleteRole("tenant", "role");
  }

  @Test
  void menuCreationPersistsDefaultsAndPermissionValidation() {
    AtomicReference<Map<String, Object>> saved = new AtomicReference<>();
    doAnswer(
            call -> {
              saved.set(call.getArgument(0));
              return 1;
            })
        .when(mapper)
        .insertMenu(any());
    when(mapper.findMenu(eq("tenant"), anyString())).thenAnswer(call -> saved.get());
    var response = service.createMenu("tenant", actor, menuRequest());
    assertEquals("dashboard", response.getMenuCode());
    assertEquals("Dashboard", response.getMenuName());
    assertEquals("page", response.getMenuType());
    assertTrue(response.isVisible());
    assertTrue(response.isEnabled());
    assertEquals(0, response.getDisplayOrder());
    verify(policy).validateMenuPermissionChange("tenant", actor, null, null);
  }

  @Test
  void menuUpdateEvictsAuthorizationCachesAfterValidation() {
    when(mapper.findMenu("tenant", "menu"))
        .thenReturn(Map.of("menuId", "menu", "permissionCode", "old"));
    when(mapper.findUserIdsByMenu("tenant", "menu")).thenReturn(List.of("user"));
    when(mapper.countManagementUsers("tenant")).thenReturn(1);
    assertEquals("menu", service.updateMenu("tenant", actor, "menu", menuRequest()).getMenuId());
    verify(policy).validateMenuPermissionChange("tenant", actor, "old", null);
    verify(login).evictUserSessionCache("user");
  }

  @ParameterizedTest
  @ValueSource(strings = {"child", "role", "none"})
  void menuDeletionHonorsChildAndRoleReferences(String reference) {
    when(mapper.findMenu("tenant", "menu")).thenReturn(Map.of("menuId", "menu"));
    when(mapper.countMenuChildren("tenant", "menu")).thenReturn(reference.equals("child") ? 1 : 0);
    when(mapper.countMenuRoles("tenant", "menu")).thenReturn(reference.equals("role") ? 1 : 0);
    when(mapper.countManagementUsers("tenant")).thenReturn(1);
    if (reference.equals("none")) service.deleteMenu("tenant", actor, "menu");
    else
      assertThrows(
          IllegalArgumentException.class, () -> service.deleteMenu("tenant", actor, "menu"));
    verify(mapper, times(reference.equals("none") ? 1 : 0)).deleteMenu("tenant", "menu");
  }

  @Test
  void assignableListsAreFilteredByDelegationPolicy() {
    when(mapper.listRoles("tenant"))
        .thenReturn(List.of(Map.of("roleId", "allowed"), Map.of("roleId", "denied")));
    when(policy.assignableRoleIds("tenant", actor)).thenReturn(List.of("allowed"));
    when(mapper.listMenus("tenant"))
        .thenReturn(List.of(Map.of("menuId", "allowed"), Map.of("menuId", "denied")));
    when(policy.assignableMenuIds("tenant", actor)).thenReturn(List.of("allowed"));
    assertEquals(
        List.of("allowed"),
        service.listAssignableRoles("tenant", actor).stream().map(r -> r.getRoleId()).toList());
    assertEquals(
        List.of("allowed"),
        service.listAssignableMenus("tenant", actor).stream().map(m -> m.getMenuId()).toList());
  }

  @Test
  void duplicateCodesReturnBusinessError() {
    doThrow(new DataIntegrityViolationException("duplicate")).when(mapper).insertRole(any());
    doThrow(new DataIntegrityViolationException("duplicate")).when(mapper).insertMenu(any());
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> service.createRole("tenant", actor, roleRequest()))
            .getMessage()
            .contains("角色编码"));
    assertTrue(
        assertThrows(
                IllegalArgumentException.class,
                () -> service.createMenu("tenant", actor, menuRequest()))
            .getMessage()
            .contains("菜单编码"));
  }

  private RbacRoleRequest roleRequest() {
    RbacRoleRequest request = new RbacRoleRequest();
    request.setRoleCode(" editor ");
    request.setRoleName(" Editor ");
    return request;
  }

  private RbacMenuRequest menuRequest() {
    RbacMenuRequest request = new RbacMenuRequest();
    request.setMenuCode(" dashboard ");
    request.setMenuName(" Dashboard ");
    request.setMenuType("page");
    request.setRoutePath("/dashboard");
    return request;
  }
}
