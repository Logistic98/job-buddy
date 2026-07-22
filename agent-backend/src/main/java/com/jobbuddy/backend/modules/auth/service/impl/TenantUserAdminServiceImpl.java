package com.jobbuddy.backend.modules.auth.service.impl;

import com.jobbuddy.backend.modules.auth.dto.request.ManagedUserCreateRequest;
import com.jobbuddy.backend.modules.auth.dto.request.ManagedUserUpdateRequest;
import com.jobbuddy.backend.modules.auth.dto.response.ManagedUserResponse;
import com.jobbuddy.backend.modules.auth.repository.UserAuthRepository;
import com.jobbuddy.backend.modules.auth.service.DynamicRbacService;
import com.jobbuddy.backend.modules.auth.service.TenantUserAdminService;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
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
  private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

  public TenantUserAdminServiceImpl(
      UserAuthRepository repository,
      UserLoginService loginService,
      DynamicRbacService rbacService) {
    this.repository = repository;
    this.loginService = loginService;
    this.rbacService = rbacService;
  }

  @Override
  public List<ManagedUserResponse> listUsers(String tenantId) {
    List<ManagedUserResponse> result = new ArrayList<ManagedUserResponse>();
    for (Map<String, Object> row : repository.listUsers(tenantId))
      result.add(toResponse(tenantId, row));
    return result;
  }

  @Transactional
  @Override
  public ManagedUserResponse create(String tenantId, ManagedUserCreateRequest request) {
    if (request == null) throw new IllegalArgumentException("用户信息不能为空");
    String username = required(request.getUsername(), "用户名不能为空");
    if (!username.matches("[A-Za-z0-9._-]{3,64}"))
      throw new IllegalArgumentException("用户名仅支持 3-64 位字母、数字、点、下划线和短横线");
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
      rbacService.replaceUserRoles(tenantId, userId, request.getRoleIds());
    } catch (DataIntegrityViolationException exception) {
      throw new IllegalArgumentException("用户名已存在，请使用全局唯一用户名");
    }
    return getRequired(tenantId, userId);
  }

  @Transactional
  @Override
  public ManagedUserResponse update(
      String tenantId, String userId, ManagedUserUpdateRequest request) {
    Map<String, Object> existing = requiredUser(tenantId, userId);
    if (request == null) throw new IllegalArgumentException("用户信息不能为空");
    boolean enabled =
        request.getEnabled() == null
            ? Boolean.TRUE.equals(existing.get("enabled"))
            : request.getEnabled();
    repository.updateUser(
        tenantId,
        userId,
        request.getDisplayName() == null
            ? text(existing.get("displayName"))
            : request.getDisplayName().trim(),
        text(existing.get("role")).isEmpty() ? "user" : text(existing.get("role")),
        enabled);
    if (request.getRoleIds() != null)
      rbacService.replaceUserRoles(tenantId, userId, request.getRoleIds());
    rbacService.protectManagementAccess(tenantId);
    loginService.invalidateUserSessions(userId);
    return getRequired(tenantId, userId);
  }

  @Transactional
  @Override
  public ManagedUserResponse replaceRoles(String tenantId, String userId, List<String> roleIds) {
    requiredUser(tenantId, userId);
    rbacService.replaceUserRoles(tenantId, userId, roleIds);
    return getRequired(tenantId, userId);
  }

  @Transactional
  @Override
  public void resetPassword(String tenantId, String userId, String password) {
    requiredUser(tenantId, userId);
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
    ManagedUserResponse response = new ManagedUserResponse();
    String userId = text(row.get("userId"));
    response.setUserId(userId);
    response.setUsername(text(row.get("username")));
    response.setDisplayName(text(row.get("displayName")));
    response.setEnabled(Boolean.TRUE.equals(row.get("enabled")));
    response.setCreatedAt(text(row.get("createdAt")));
    response.setUpdatedAt(text(row.get("updatedAt")));
    response.setRoleIds(new ArrayList<String>(rbacService.userRoleIds(tenantId, userId)));
    response.setRoleNames(new ArrayList<String>(rbacService.userRoleNames(tenantId, userId)));
    response.setPermissions(new ArrayList<String>(repository.findPermissions(userId)));
    return response;
  }

  private void validatePassword(String password) {
    if (password == null || password.length() < 8 || password.length() > 16)
      throw new IllegalArgumentException("密码长度必须为 8-16 位");
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
