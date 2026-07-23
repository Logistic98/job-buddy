package com.jobbuddy.backend.modules.auth.service.impl;

import com.jobbuddy.backend.modules.auth.dto.request.RbacMenuRequest;
import com.jobbuddy.backend.modules.auth.dto.request.RbacRoleRequest;
import com.jobbuddy.backend.modules.auth.dto.response.RbacMenuResponse;
import com.jobbuddy.backend.modules.auth.dto.response.RbacRoleResponse;
import com.jobbuddy.backend.modules.auth.mapper.RbacMapper;
import com.jobbuddy.backend.modules.auth.service.DynamicRbacService;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DynamicRbacServiceImpl implements DynamicRbacService {
  private static final Set<String> MENU_TYPES = new LinkedHashSet<String>();

  static {
    Collections.addAll(MENU_TYPES, "directory", "page", "external", "action");
  }

  private final RbacMapper mapper;
  private final UserLoginService loginService;

  public DynamicRbacServiceImpl(RbacMapper mapper, UserLoginService loginService) {
    this.mapper = mapper;
    this.loginService = loginService;
  }

  @Override
  public List<RbacRoleResponse> listRoles(String tenantId) {
    List<RbacRoleResponse> result = new ArrayList<RbacRoleResponse>();
    for (Map<String, Object> row : mapper.listRoles(requireTenant(tenantId)))
      result.add(roleResponse(tenantId, row));
    return result;
  }

  @Transactional
  @Override
  public RbacRoleResponse createRole(String tenantId, RbacRoleRequest request) {
    tenantId = requireTenant(tenantId);
    validateRoleRequest(request);
    List<String> menuIds = normalizeIds(request.getMenuIds());
    validateMenus(tenantId, menuIds);
    menuIds = expandMenuAncestors(tenantId, menuIds);
    Instant now = Instant.now();
    String roleId = "role_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("roleId", roleId);
    row.put("tenantId", tenantId);
    row.put("roleCode", request.getRoleCode().trim());
    row.put("roleName", request.getRoleName().trim());
    row.put("description", trim(request.getDescription()));
    row.put("enabled", request.getEnabled() == null || request.getEnabled());
    row.put("createdAt", now);
    row.put("updatedAt", now);
    try {
      mapper.insertRole(row);
      replaceRoleMenusInternal(tenantId, roleId, menuIds);
    } catch (DataIntegrityViolationException exception) {
      throw new IllegalArgumentException("角色编码已存在或数据不合法");
    }
    return requiredRole(tenantId, roleId);
  }

  @Transactional
  @Override
  public RbacRoleResponse updateRole(String tenantId, String roleId, RbacRoleRequest request) {
    tenantId = requireTenant(tenantId);
    Map<String, Object> current = requiredRoleRow(tenantId, roleId);
    validateRoleRequest(request);
    List<String> affected = mapper.findUserIdsByRole(tenantId, roleId);
    try {
      mapper.updateRole(
          tenantId,
          roleId,
          request.getRoleCode().trim(),
          request.getRoleName().trim(),
          trim(request.getDescription()),
          request.getEnabled() == null ? bool(current.get("enabled")) : request.getEnabled(),
          Instant.now());
      if (request.getMenuIds() != null) {
        List<String> menuIds = normalizeIds(request.getMenuIds());
        validateMenus(tenantId, menuIds);
        replaceRoleMenusInternal(tenantId, roleId, expandMenuAncestors(tenantId, menuIds));
      }
      protectManagementAccess(tenantId);
    } catch (DataIntegrityViolationException exception) {
      throw new IllegalArgumentException("角色编码已存在或数据不合法");
    }
    invalidateUsers(affected);
    return requiredRole(tenantId, roleId);
  }

  @Transactional
  @Override
  public RbacRoleResponse replaceRoleMenus(String tenantId, String roleId, List<String> menuIds) {
    tenantId = requireTenant(tenantId);
    requiredRoleRow(tenantId, roleId);
    List<String> normalized = normalizeIds(menuIds);
    validateMenus(tenantId, normalized);
    normalized = expandMenuAncestors(tenantId, normalized);
    List<String> affected = mapper.findUserIdsByRole(tenantId, roleId);
    replaceRoleMenusInternal(tenantId, roleId, normalized);
    protectManagementAccess(tenantId);
    invalidateUsers(affected);
    return requiredRole(tenantId, roleId);
  }

  @Transactional
  @Override
  public void deleteRole(String tenantId, String roleId) {
    tenantId = requireTenant(tenantId);
    requiredRoleRow(tenantId, roleId);
    if (mapper.countRoleUsers(tenantId, roleId) > 0)
      throw new IllegalArgumentException("角色仍被用户引用，请先解除用户角色关系");
    mapper.deleteRole(tenantId, roleId);
    protectManagementAccess(tenantId);
  }

  @Override
  public List<RbacMenuResponse> listMenus(String tenantId) {
    List<RbacMenuResponse> result = new ArrayList<RbacMenuResponse>();
    for (Map<String, Object> row : mapper.listMenus(requireTenant(tenantId)))
      result.add(menuResponse(row));
    return result;
  }

  @Transactional
  @Override
  public RbacMenuResponse createMenu(String tenantId, RbacMenuRequest request) {
    tenantId = requireTenant(tenantId);
    validateMenuRequest(tenantId, null, request);
    Instant now = Instant.now();
    String menuId = "menu_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    Map<String, Object> row = menuMap(tenantId, menuId, request, now);
    try {
      mapper.insertMenu(row);
    } catch (DataIntegrityViolationException exception) {
      throw new IllegalArgumentException("菜单编码已存在或菜单数据不合法");
    }
    return menuResponse(requiredMenuRow(tenantId, menuId));
  }

  @Transactional
  @Override
  public RbacMenuResponse updateMenu(String tenantId, String menuId, RbacMenuRequest request) {
    tenantId = requireTenant(tenantId);
    requiredMenuRow(tenantId, menuId);
    validateMenuRequest(tenantId, menuId, request);
    List<String> affected = mapper.findUserIdsByMenu(tenantId, menuId);
    try {
      mapper.updateMenu(
          tenantId,
          menuId,
          emptyToNull(request.getParentId()),
          request.getMenuCode().trim(),
          request.getMenuName().trim(),
          request.getMenuType().trim(),
          emptyToNull(request.getRoutePath()),
          emptyToNull(request.getComponentKey()),
          emptyToNull(request.getExternalUrl()),
          emptyToNull(request.getIconKey()),
          emptyToNull(request.getPermissionCode()),
          request.getDisplayOrder() == null ? 0 : request.getDisplayOrder(),
          request.getVisible() == null || request.getVisible(),
          request.getEnabled() == null || request.getEnabled(),
          Instant.now());
      protectManagementAccess(tenantId);
    } catch (DataIntegrityViolationException exception) {
      throw new IllegalArgumentException("菜单编码已存在或菜单数据不合法");
    }
    invalidateUsers(affected);
    return menuResponse(requiredMenuRow(tenantId, menuId));
  }

  @Transactional
  @Override
  public void deleteMenu(String tenantId, String menuId) {
    tenantId = requireTenant(tenantId);
    requiredMenuRow(tenantId, menuId);
    if (mapper.countMenuChildren(tenantId, menuId) > 0)
      throw new IllegalArgumentException("菜单包含子节点，不能删除");
    if (mapper.countMenuRoles(tenantId, menuId) > 0)
      throw new IllegalArgumentException("菜单仍被角色引用，请先解除角色菜单关系");
    mapper.deleteMenu(tenantId, menuId);
    protectManagementAccess(tenantId);
  }

  @Override
  public List<String> userRoleIds(String tenantId, String userId) {
    return mapper.findUserRoleIds(requireTenant(tenantId), userId);
  }

  @Override
  public List<String> userRoleNames(String tenantId, String userId) {
    return mapper.findUserRoleNames(requireTenant(tenantId), userId);
  }

  @Transactional
  @Override
  public void replaceUserRoles(String tenantId, String userId, List<String> roleIds) {
    tenantId = requireTenant(tenantId);
    List<String> normalized = normalizeIds(roleIds);
    validateRoles(tenantId, normalized);
    mapper.deleteUserRoles(tenantId, userId);
    Instant now = Instant.now();
    for (String roleId : normalized) mapper.insertUserRole(tenantId, userId, roleId, now);
    protectManagementAccess(tenantId);
    loginService.invalidateUserSessions(userId);
  }

  @Override
  public void protectManagementAccess(String tenantId) {
    if (mapper.countManagementUsers(tenantId) < 1)
      throw new IllegalArgumentException("当前租户必须至少保留一个具备用户、角色和菜单管理能力的有效账号");
  }

  private void replaceRoleMenusInternal(String tenantId, String roleId, List<String> menuIds) {
    mapper.deleteRoleMenus(tenantId, roleId);
    Instant now = Instant.now();
    for (String menuId : menuIds) mapper.insertRoleMenu(tenantId, roleId, menuId, now);
  }

  private void validateRoleRequest(RbacRoleRequest request) {
    if (request == null) throw new IllegalArgumentException("角色信息不能为空");
    String code = trim(request.getRoleCode());
    if (!code.matches("[A-Za-z0-9._-]{2,64}"))
      throw new IllegalArgumentException("角色编码仅支持 2-64 位字母、数字、点、下划线和短横线");
    if (trim(request.getRoleName()).isEmpty()) throw new IllegalArgumentException("角色名称不能为空");
  }

  private void validateMenuRequest(String tenantId, String menuId, RbacMenuRequest request) {
    if (request == null) throw new IllegalArgumentException("菜单信息不能为空");
    if (!trim(request.getMenuCode()).matches("[A-Za-z0-9._-]{2,64}"))
      throw new IllegalArgumentException("菜单编码格式不正确");
    if (trim(request.getMenuName()).isEmpty()) throw new IllegalArgumentException("菜单名称不能为空");
    String type = trim(request.getMenuType());
    if (!MENU_TYPES.contains(type)) throw new IllegalArgumentException("菜单类型不支持: " + type);
    String externalUrl = emptyToNull(request.getExternalUrl());
    if ("external".equals(type) && (externalUrl == null || !externalUrl.matches("https?://.+")))
      throw new IllegalArgumentException("外链菜单必须配置 http 或 https 地址");
    String routePath = emptyToNull(request.getRoutePath());
    if (routePath != null && !routePath.startsWith("/"))
      throw new IllegalArgumentException("内部路由必须以 / 开头");
    String componentKey = emptyToNull(request.getComponentKey());
    if (componentKey != null && !componentKey.matches("[A-Za-z0-9._-]{1,128}"))
      throw new IllegalArgumentException("页面组件键格式不正确");
    String permission = emptyToNull(request.getPermissionCode());
    if (permission != null && mapper.countPermissionCode(permission) == 0)
      throw new IllegalArgumentException("权限码不存在: " + permission);
    String parentId = emptyToNull(request.getParentId());
    if (parentId != null) {
      if (parentId.equals(menuId)) throw new IllegalArgumentException("菜单不能把自己设为父节点");
      requiredMenuRow(tenantId, parentId);
      String cursor = parentId;
      for (int i = 0; i < 64 && cursor != null; i++) {
        if (cursor.equals(menuId)) throw new IllegalArgumentException("菜单父子关系不能形成循环");
        Map<String, Object> parent = mapper.findMenu(tenantId, cursor);
        cursor = parent == null ? null : emptyToNull(text(parent.get("parentId")));
      }
    }
  }

  private List<String> expandMenuAncestors(String tenantId, List<String> ids) {
    LinkedHashSet<String> expanded = new LinkedHashSet<String>(ids);
    for (String id : new ArrayList<String>(ids)) {
      String cursor = id;
      for (int i = 0; i < 64 && cursor != null; i++) {
        Map<String, Object> row = requiredMenuRow(tenantId, cursor);
        String parentId = emptyToNull(text(row.get("parentId")));
        if (parentId == null) break;
        expanded.add(parentId);
        cursor = parentId;
      }
    }
    return new ArrayList<String>(expanded);
  }

  private void validateMenus(String tenantId, List<String> ids) {
    if (!ids.isEmpty() && mapper.countMenusByIds(tenantId, ids) != ids.size())
      throw new IllegalArgumentException("包含不存在或跨租户的菜单");
  }

  private void validateRoles(String tenantId, List<String> ids) {
    if (!ids.isEmpty() && mapper.countRolesByIds(tenantId, ids) != ids.size())
      throw new IllegalArgumentException("包含不存在或跨租户的角色");
  }

  private Map<String, Object> menuMap(
      String tenantId, String menuId, RbacMenuRequest request, Instant now) {
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("menuId", menuId);
    row.put("tenantId", tenantId);
    row.put("parentId", emptyToNull(request.getParentId()));
    row.put("menuCode", request.getMenuCode().trim());
    row.put("menuName", request.getMenuName().trim());
    row.put("menuType", request.getMenuType().trim());
    row.put("routePath", emptyToNull(request.getRoutePath()));
    row.put("componentKey", emptyToNull(request.getComponentKey()));
    row.put("externalUrl", emptyToNull(request.getExternalUrl()));
    row.put("iconKey", emptyToNull(request.getIconKey()));
    row.put("permissionCode", emptyToNull(request.getPermissionCode()));
    row.put("displayOrder", request.getDisplayOrder() == null ? 0 : request.getDisplayOrder());
    row.put("visible", request.getVisible() == null || request.getVisible());
    row.put("enabled", request.getEnabled() == null || request.getEnabled());
    row.put("createdAt", now);
    row.put("updatedAt", now);
    return row;
  }

  private RbacRoleResponse requiredRole(String tenantId, String roleId) {
    return roleResponse(tenantId, requiredRoleRow(tenantId, roleId));
  }

  private Map<String, Object> requiredRoleRow(String tenantId, String roleId) {
    Map<String, Object> row = mapper.findRole(tenantId, roleId);
    if (row == null) throw new IllegalArgumentException("角色不存在或不属于当前租户");
    return row;
  }

  private Map<String, Object> requiredMenuRow(String tenantId, String menuId) {
    Map<String, Object> row = mapper.findMenu(tenantId, menuId);
    if (row == null) throw new IllegalArgumentException("菜单不存在或不属于当前租户");
    return row;
  }

  private RbacRoleResponse roleResponse(String tenantId, Map<String, Object> row) {
    RbacRoleResponse value = new RbacRoleResponse();
    value.setRoleId(text(row.get("roleId")));
    value.setRoleCode(text(row.get("roleCode")));
    value.setRoleName(text(row.get("roleName")));
    value.setDescription(text(row.get("description")));
    value.setEnabled(bool(row.get("enabled")));
    value.setCreatedAt(text(row.get("createdAt")));
    value.setUpdatedAt(text(row.get("updatedAt")));
    value.setMenuIds(new ArrayList<String>(mapper.findRoleMenuIds(tenantId, value.getRoleId())));
    return value;
  }

  private RbacMenuResponse menuResponse(Map<String, Object> row) {
    RbacMenuResponse value = new RbacMenuResponse();
    value.setMenuId(text(row.get("menuId")));
    value.setParentId(text(row.get("parentId")));
    value.setMenuCode(text(row.get("menuCode")));
    value.setMenuName(text(row.get("menuName")));
    value.setMenuType(text(row.get("menuType")));
    value.setRoutePath(text(row.get("routePath")));
    value.setComponentKey(text(row.get("componentKey")));
    value.setExternalUrl(text(row.get("externalUrl")));
    value.setIconKey(text(row.get("iconKey")));
    value.setPermissionCode(text(row.get("permissionCode")));
    value.setDisplayOrder(number(row.get("displayOrder")));
    value.setVisible(bool(row.get("visible")));
    value.setEnabled(bool(row.get("enabled")));
    value.setCreatedAt(text(row.get("createdAt")));
    value.setUpdatedAt(text(row.get("updatedAt")));
    return value;
  }

  private void invalidateUsers(List<String> users) {
    for (String userId : new LinkedHashSet<String>(users))
      loginService.invalidateUserSessions(userId);
  }

  private List<String> normalizeIds(List<String> ids) {
    LinkedHashSet<String> set = new LinkedHashSet<String>();
    if (ids != null) for (String id : ids) if (!trim(id).isEmpty()) set.add(id.trim());
    return new ArrayList<String>(set);
  }

  private String requireTenant(String value) {
    if (trim(value).isEmpty()) throw new IllegalArgumentException("当前账号缺少租户归属");
    return value.trim();
  }

  private String emptyToNull(String value) {
    String result = trim(value);
    return result.isEmpty() ? null : result;
  }

  private String trim(String value) {
    return value == null ? "" : value.trim();
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private boolean bool(Object value) {
    return Boolean.TRUE.equals(value);
  }

  private int number(Object value) {
    return value instanceof Number ? ((Number) value).intValue() : 0;
  }
}
