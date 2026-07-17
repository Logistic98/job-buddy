package com.jobbuddy.backend.modules.auth.repository;

import com.jobbuddy.backend.modules.auth.mapper.UserAuthMapper;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class UserAuthRepository {
  private final UserAuthMapper mapper;

  public UserAuthRepository(UserAuthMapper mapper) {
    this.mapper = mapper;
  }

  public Map<String, Object> findUserByUsername(String username) {
    return normalizeTimeStrings(mapper.findUserByUsername(username), "createdAt", "updatedAt");
  }

  public Map<String, Object> findUserByToken(String token) {
    return normalizeTimeStrings(
        mapper.findUserByToken(token), "createdAt", "updatedAt", "expiresAt");
  }

  public List<String> findRoles(String userId) {
    List<String> roles = mapper.findRoleCodesByUserId(userId);
    return roles == null ? Collections.<String>emptyList() : roles;
  }

  public List<String> findPermissions(String userId) {
    List<String> permissions = mapper.findPermissionsByUserId(userId);
    return permissions == null ? Collections.<String>emptyList() : permissions;
  }

  public List<Map<String, Object>> findMenus(String userId) {
    List<Map<String, Object>> menus = mapper.findMenusByUserId(userId);
    return menus == null ? Collections.<Map<String, Object>>emptyList() : menus;
  }

  public List<Map<String, Object>> listUsers(String tenantId) {
    return mapper.listUsers(tenantId);
  }

  public Map<String, Object> findUserById(String tenantId, String userId) {
    return mapper.findUserById(tenantId, userId);
  }

  public List<Map<String, Object>> listGrantablePermissions() {
    return mapper.listGrantablePermissions();
  }

  public List<Map<String, Object>> listPermissionDefinitions() {
    return mapper.listPermissionDefinitions();
  }

  public int countEnabledAdmins(String tenantId) {
    return mapper.countEnabledAdmins(tenantId);
  }

  public void insertUser(
      String userId,
      String tenantId,
      String username,
      String passwordHash,
      String displayName,
      String role,
      boolean enabled) {
    mapper.insertUser(
        userId, tenantId, username, passwordHash, displayName, role, enabled, Instant.now());
  }

  public void updateUser(
      String tenantId, String userId, String displayName, String role, boolean enabled) {
    mapper.updateUser(tenantId, userId, displayName, role, enabled, Instant.now());
  }

  public void replacePermissions(String tenantId, String userId, List<String> permissions) {
    mapper.deleteUserPermissions(tenantId, userId);
    Instant now = Instant.now();
    for (String permission : permissions)
      mapper.insertUserPermission(tenantId, userId, permission, now);
  }

  public void updatePasswordHash(String userId, String passwordHash) {
    mapper.updatePasswordHash(userId, passwordHash, Instant.now());
  }

  public void saveSession(String token, String userId, Instant expiresAt) {
    mapper.saveSession(token, userId, expiresAt, Instant.now());
  }

  public void touchSession(String token) {
    mapper.touchSession(token, Instant.now());
  }

  public void deleteSession(String token) {
    mapper.deleteSession(token);
  }

  public void deleteSessionsByUserId(String userId) {
    mapper.deleteSessionsByUserId(userId);
  }

  public void deleteExpiredSessions() {
    mapper.deleteExpiredSessions(Instant.now());
  }

  private Map<String, Object> normalizeTimeStrings(Map<String, Object> row, String... keys) {
    if (row == null) return null;
    Map<String, Object> result = new LinkedHashMap<String, Object>(row);
    for (String key : keys)
      if (result.get(key) != null) result.put(key, toInstant(result.get(key)).toString());
    return result;
  }

  private Instant toInstant(Object value) {
    if (value instanceof Instant) return (Instant) value;
    if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toInstant();
    if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant();
    return Instant.parse(String.valueOf(value));
  }
}
