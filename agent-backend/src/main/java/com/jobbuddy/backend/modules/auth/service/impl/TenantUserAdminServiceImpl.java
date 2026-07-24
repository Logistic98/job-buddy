package com.jobbuddy.backend.modules.auth.service.impl;

import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.request.ManagedUserCreateRequest;
import com.jobbuddy.backend.modules.auth.dto.request.ManagedUserUpdateRequest;
import com.jobbuddy.backend.modules.auth.dto.response.ManagedUserResponse;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import com.jobbuddy.backend.modules.auth.service.DynamicRbacService;
import com.jobbuddy.backend.modules.auth.service.TenantUserAdminService;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TenantUserAdminServiceImpl implements TenantUserAdminService {
  private final UserAuthRepository repository;
  private final UserLoginService loginService;
  private final DynamicRbacService rbacService;
  private final RbacDelegationPolicy delegationPolicy;
  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  public TenantUserAdminServiceImpl(
      UserAuthRepository repository,
      UserLoginService loginService,
      DynamicRbacService rbacService,
      RbacDelegationPolicy delegationPolicy) {
    this.repository = repository;
    this.loginService = loginService;
    this.rbacService = rbacService;
    this.delegationPolicy = delegationPolicy;
  }

  @Transactional(readOnly = true)
  @Override
  public List<ManagedUserResponse> listUsers(String tenantId) {
    Map<String, ManagedUserResponse> users = new LinkedHashMap<String, ManagedUserResponse>();
    for (Map<String, Object> row : repository.listUsers(tenantId)) {
      ManagedUserResponse response = baseResponse(row);
      users.put(response.getUserId(), response);
    }
    for (Map<String, Object> assignment : repository.listUserRoleAssignments(tenantId)) {
      ManagedUserResponse response = users.get(text(assignment.get("userId")));
      if (response != null) {
        response.getRoleIds().add(text(assignment.get("roleId")));
        response.getRoleNames().add(text(assignment.get("roleName")));
      }
    }
    // 权限来自用户角色与角色菜单的实时并集，不维护第二套用户直授权数据。
    for (Map<String, Object> assignment :
        repository.listUserEffectivePermissionAssignments(tenantId)) {
      ManagedUserResponse response = users.get(text(assignment.get("userId")));
      if (response != null) {
        response.getPermissions().add(text(assignment.get("permissionCode")));
      }
    }
    return new ArrayList<ManagedUserResponse>(users.values());
  }

  @Transactional
  @Override
  public ManagedUserResponse create(
      String tenantId, AuthenticatedUser actor, ManagedUserCreateRequest request) {
    if (request == null) throw new IllegalArgumentException("用户信息不能为空");
    String username = required(request.getUsername(), "用户名不能为空");
    validateUsername(username);
    validatePassword(request.getPassword());
    String userId = UUID.randomUUID().toString();
    try {
      repository.insertUser(
          userId,
          tenantId,
          username,
          passwordEncoder.encode(request.getPassword()),
          trimToDefault(request.getDisplayName(), username),
          "user",
          true);
      rbacService.replaceUserRoles(tenantId, actor, userId, request.getRoleIds());
    } catch (DataIntegrityViolationException exception) {
      throw new IllegalArgumentException("用户名已存在，请使用全局唯一用户名");
    }
    return getRequired(tenantId, userId);
  }

  @Transactional
  @Override
  public ManagedUserResponse update(
      String tenantId, AuthenticatedUser actor, String userId, ManagedUserUpdateRequest request) {
    Map<String, Object> existing = requiredUser(tenantId, userId);
    if (request == null) throw new IllegalArgumentException("用户信息不能为空");
    String username =
        request.getUsername() == null
            ? text(existing.get("username"))
            : required(request.getUsername(), "用户名不能为空");
    validateUsername(username);
    Map<String, Object> usernameOwner = repository.findUserByUsername(username);
    if (usernameOwner != null
        && !usernameOwner.isEmpty()
        && !userId.equals(text(usernameOwner.get("userId")))) {
      throw new IllegalArgumentException("用户名已存在，请使用全局唯一用户名");
    }
    boolean enabled =
        request.getEnabled() == null
            ? Boolean.TRUE.equals(existing.get("enabled"))
            : request.getEnabled();
    try {
      repository.updateUser(
          tenantId,
          userId,
          username,
          request.getDisplayName() == null
              ? text(existing.get("displayName"))
              : request.getDisplayName().trim(),
          text(existing.get("role")).isEmpty() ? "user" : text(existing.get("role")),
          enabled);
    } catch (DataIntegrityViolationException exception) {
      throw new IllegalArgumentException("用户名已存在，请使用全局唯一用户名");
    }
    if (request.getRoleIds() != null) {
      Set<String> currentRoleIds =
          new LinkedHashSet<String>(rbacService.userRoleIds(tenantId, userId));
      Set<String> requestedRoleIds =
          new LinkedHashSet<String>(
              request.getRoleIds() == null
                  ? java.util.Collections.<String>emptyList()
                  : request.getRoleIds());
      if (!currentRoleIds.equals(requestedRoleIds)) {
        rbacService.replaceUserRoles(tenantId, actor, userId, request.getRoleIds());
      }
    }
    rbacService.protectManagementAccess(tenantId);
    loginService.invalidateUserSessions(userId);
    return getRequired(tenantId, userId);
  }

  @Transactional
  @Override
  public ManagedUserResponse replaceRoles(
      String tenantId, AuthenticatedUser actor, String userId, List<String> roleIds) {
    requiredUser(tenantId, userId);
    rbacService.replaceUserRoles(tenantId, actor, userId, roleIds);
    return getRequired(tenantId, userId);
  }

  @Transactional
  @Override
  public void resetPassword(
      String tenantId, AuthenticatedUser actor, String userId, String password) {
    requiredUser(tenantId, userId);
    delegationPolicy.validatePasswordReset(tenantId, actor, userId);
    validatePassword(password);
    repository.updatePasswordHash(userId, passwordEncoder.encode(password));
    loginService.invalidateUserSessions(userId);
  }

  private ManagedUserResponse getRequired(String tenantId, String userId) {
    return toResponse(tenantId, requiredUser(tenantId, userId));
  }

  private Map<String, Object> requiredUser(String tenantId, String userId) {
    Map<String, Object> row = repository.findUserById(tenantId, userId);
    if (row == null || row.isEmpty()) throw new IllegalArgumentException("用户不存在或不属于当前租户");
    return row;
  }

  private ManagedUserResponse toResponse(String tenantId, Map<String, Object> row) {
    ManagedUserResponse response = baseResponse(row);
    String userId = response.getUserId();
    response.setRoleIds(new ArrayList<String>(rbacService.userRoleIds(tenantId, userId)));
    response.setRoleNames(new ArrayList<String>(rbacService.userRoleNames(tenantId, userId)));
    response.setPermissions(new ArrayList<String>(repository.findPermissions(userId)));
    return response;
  }

  private ManagedUserResponse baseResponse(Map<String, Object> row) {
    ManagedUserResponse response = new ManagedUserResponse();
    response.setUserId(text(row.get("userId")));
    response.setUsername(text(row.get("username")));
    response.setDisplayName(text(row.get("displayName")));
    response.setEnabled(Boolean.TRUE.equals(row.get("enabled")));
    response.setCreatedAt(text(row.get("createdAt")));
    response.setUpdatedAt(text(row.get("updatedAt")));
    return response;
  }

  private void validatePassword(String password) {
    if (password == null || password.length() < 8 || password.length() > 16)
      throw new IllegalArgumentException("密码长度必须为 8-16 位");
  }

  private void validateUsername(String username) {
    if (!username.matches("[A-Za-z][A-Za-z0-9_-]{2,31}")) {
      throw new IllegalArgumentException("用户名须为 3-32 位、以字母开头，且仅可包含字母、数字、下划线和短横线");
    }
  }

  private String required(String value, String message) {
    if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(message);
    return value.trim();
  }

  private String trimToDefault(String value, String fallback) {
    return value == null || value.trim().isEmpty() ? fallback : value.trim();
  }

  private String text(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
