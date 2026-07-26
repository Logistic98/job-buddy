package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.request.RbacMenuRequest;
import com.jobbuddy.backend.modules.auth.mapper.RbacMapper;
import com.jobbuddy.backend.modules.auth.service.DynamicRbacService;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import com.jobbuddy.backend.modules.auth.service.impl.DynamicRbacServiceImpl;
import com.jobbuddy.backend.modules.auth.service.impl.RbacDelegationPolicy;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 DynamicRbacService 的核心行为、异常路径与边界条件。
 */
class DynamicRbacServiceTest {
  /**
   * 验证 DynamicRbacService 中角色的主要成功路径。
   */
  @Test
  void listRolesLoadsMenuAssignmentsWithOneBatchQuery() {
    RbacMapper mapper = mock(RbacMapper.class);
    when(mapper.listRoles("tenant-a")).thenReturn(List.of(role("role-a"), role("role-b")));
    when(mapper.listRoleMenuAssignments("tenant-a"))
        .thenReturn(
            List.of(
                Map.of("roleId", "role-a", "menuId", "menu-a"),
                Map.of("roleId", "role-a", "menuId", "menu-b")));
    DynamicRbacService service =
        new DynamicRbacServiceImpl(
            mapper, mock(UserLoginService.class), mock(RbacDelegationPolicy.class));

    var roles = service.listRoles("tenant-a");

    assertEquals(2, roles.size());
    assertEquals(List.of("menu-a", "menu-b"), roles.get(0).getMenuIds());
    assertEquals(List.of(), roles.get(1).getMenuIds());
    verify(mapper, never())
        .findRoleMenuIds(
            org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString());
  }

  /**
   * 验证 DynamicRbacService 中用户的权限与租户隔离边界。
   */
  @Test
  void rejectsCrossTenantRolesWhenReplacingUserRoles() {
    RbacMapper mapper = mock(RbacMapper.class);
    when(mapper.countRolesByIds("tenant-a", Arrays.asList("role-b"))).thenReturn(0);
    DynamicRbacService service =
        new DynamicRbacServiceImpl(
            mapper, mock(UserLoginService.class), mock(RbacDelegationPolicy.class));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.replaceUserRoles("tenant-a", actor(), "user-a", Arrays.asList("role-b")));
    verify(mapper, never()).deleteUserRoles("tenant-a", "user-a");
  }

  /**
   * 验证 DynamicRbacService 中菜单的输入校验与拒绝边界。
   */
  @Test
  void rejectsMenuParentCycle() {
    RbacMapper mapper = mock(RbacMapper.class);
    when(mapper.findMenu("tenant-a", "menu-a")).thenReturn(menu("menu-a", ""));
    when(mapper.findMenu("tenant-a", "menu-b")).thenReturn(menu("menu-b", "menu-a"));
    DynamicRbacService service =
        new DynamicRbacServiceImpl(
            mapper, mock(UserLoginService.class), mock(RbacDelegationPolicy.class));
    RbacMenuRequest request = request("menu-a");

    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateMenu("tenant-a", actor(), "menu-a", request));
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

  /**
   * 验证 DynamicRbacService 中角色的核心业务契约。
   */
  @Test
  void roleMenuAssignmentAutomaticallyIncludesParentMenus() {
    RbacMapper mapper = mock(RbacMapper.class);
    when(mapper.findRole("tenant-a", "role-a")).thenReturn(role("role-a"));
    when(mapper.countMenusByIds("tenant-a", Arrays.asList("menu-child"))).thenReturn(1);
    when(mapper.findMenu("tenant-a", "menu-child")).thenReturn(menu("menu-child", "menu-parent"));
    when(mapper.findMenu("tenant-a", "menu-parent")).thenReturn(menu("menu-parent", ""));
    when(mapper.countManagementUsers("tenant-a")).thenReturn(1);
    DynamicRbacService service =
        new DynamicRbacServiceImpl(
            mapper, mock(UserLoginService.class), mock(RbacDelegationPolicy.class));

    service.replaceRoleMenus("tenant-a", actor(), "role-a", Arrays.asList("menu-child"));

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

  /**
   * 验证 DynamicRbacService 中菜单的安全保护边界。
   */
  @Test
  void rejectsUnsafeExternalMenuUrls() {
    DynamicRbacService service =
        new DynamicRbacServiceImpl(
            mock(RbacMapper.class), mock(UserLoginService.class), mock(RbacDelegationPolicy.class));
    RbacMenuRequest request = request("");
    request.setMenuType("external");
    request.setExternalUrl("javascript:alert(1)");
    assertThrows(
        IllegalArgumentException.class, () -> service.createMenu("tenant-a", actor(), request));
  }

  /**
   * 验证菜单修改立即刷新授权缓存，同时保留用户的有效登录会话。
   */
  @Test
  void updatingMenuEvictsAuthorizationCacheWithoutInvalidatingSessions() {
    RbacMapper mapper = mock(RbacMapper.class);
    UserLoginService loginService = mock(UserLoginService.class);
    Map<String, Object> menu = menu("menu-a", "");
    menu.put("menuCode", "menu-code");
    menu.put("menuName", "Menu");
    menu.put("menuType", "page");
    menu.put("routePath", "/chat");
    menu.put("componentKey", "chat");
    menu.put("displayOrder", 1);
    when(mapper.findMenu("tenant-a", "menu-a")).thenReturn(menu);
    when(mapper.findUserIdsByMenu("tenant-a", "menu-a")).thenReturn(List.of("admin"));
    when(mapper.countManagementUsers("tenant-a")).thenReturn(1);
    RbacMenuRequest request = request("");
    request.setRoutePath("/chat");
    request.setComponentKey("chat");
    DynamicRbacService service =
        new DynamicRbacServiceImpl(mapper, loginService, mock(RbacDelegationPolicy.class));

    service.updateMenu("tenant-a", actor(), "menu-a", request);

    verify(loginService).evictUserSessionCache("admin");
    verify(loginService, never()).invalidateUserSessions("admin");
  }

  /**
   * 验证 DynamicRbacService 中权限的权限与租户隔离边界。
   */
  @Test
  void rejectsStandaloneActionPermissionNodes() {
    DynamicRbacService service =
        new DynamicRbacServiceImpl(
            mock(RbacMapper.class), mock(UserLoginService.class), mock(RbacDelegationPolicy.class));
    RbacMenuRequest request = request("");
    request.setMenuType("action");

    assertThrows(
        IllegalArgumentException.class, () -> service.createMenu("tenant-a", actor(), request));
  }

  /**
   * 验证 DynamicRbacService 中角色的输入校验与拒绝边界。
   */
  @Test
  void rejectsDeletingReferencedRole() {
    RbacMapper mapper = mock(RbacMapper.class);
    when(mapper.findRole("tenant-a", "role-a")).thenReturn(role("role-a"));
    when(mapper.countRoleUsers("tenant-a", "role-a")).thenReturn(1);
    DynamicRbacService service =
        new DynamicRbacServiceImpl(
            mapper, mock(UserLoginService.class), mock(RbacDelegationPolicy.class));

    assertThrows(
        IllegalArgumentException.class, () -> service.deleteRole("tenant-a", actor(), "role-a"));
    verify(mapper, never()).deleteRole("tenant-a", "role-a");
  }

  /**
   * 验证 DynamicRbacService 的数量、长度与分页边界。
   */
  @Test
  void protectsLastEffectiveManagementAccount() {
    RbacMapper mapper = mock(RbacMapper.class);
    when(mapper.countManagementUsers("tenant-a")).thenReturn(0);
    DynamicRbacService service =
        new DynamicRbacServiceImpl(
            mapper, mock(UserLoginService.class), mock(RbacDelegationPolicy.class));
    assertThrows(IllegalArgumentException.class, () -> service.protectManagementAccess("tenant-a"));
  }

  /**
   * 验证请求。
   *
   * @param parentId 父级标识
   * @return 测试请求
   */
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

  /**
   * 验证菜单。
   *
   * @param id 标识
   * @param parent 父级
   * @return 测试菜单
   */
  private Map<String, Object> menu(String id, String parent) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("menuId", id);
    row.put("parentId", parent);
    row.put("enabled", true);
    row.put("visible", true);
    return row;
  }

  /**
   * 验证角色。
   *
   * @param id 标识
   * @return 消息角色
   */
  private Map<String, Object> role(String id) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("roleId", id);
    row.put("enabled", true);
    return row;
  }

  /**
   * 验证操作人。
   *
   * @return 测试操作用户
   */
  private AuthenticatedUser actor() {
    AuthenticatedUser actor = new AuthenticatedUser();
    actor.setUserId("manager-a");
    actor.setTenantId("tenant-a");
    return actor;
  }
}
