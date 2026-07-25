package com.jobbuddy.backend.modules.auth.repository;

import com.jobbuddy.backend.modules.auth.mapper.UserAuthMapper;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 统一认证 Mapper 行结构、会话时间字段与角色投影。
 */
@Repository
public class UserAuthRepository {
  private final UserAuthMapper mapper;

  /**
   * 创建用户认证存储访问实例。
   *
   * @param mapper 数据映射
   */
  public UserAuthRepository(UserAuthMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 按用户名查询用户。
   *
   * @param username 用户名
   * @return 用户通过用户名
   */
  public Map<String, Object> findUserByUsername(String username) {
    return normalizeTimeStrings(mapper.findUserByUsername(username), "createdAt", "updatedAt");
  }

  /**
   * 按会话令牌查询用户。
   *
   * @param token 令牌
   * @return 用户通过令牌
   */
  public Map<String, Object> findUserByToken(String token) {
    return normalizeTimeStrings(
        mapper.findUserByToken(token), "createdAt", "updatedAt", "expiresAt");
  }

  /**
   * 查找角色列表。
   *
   * @param userId 用户标识
   * @return 角色列表
   */
  public List<String> findRoles(String userId) {
    List<String> roles = mapper.findRoleCodesByUserId(userId);
    return roles == null ? Collections.<String>emptyList() : roles;
  }

  /**
   * 查找权限列表。
   *
   * @param userId 用户标识
   * @return 权限列表
   */
  public List<String> findPermissions(String userId) {
    List<String> permissions = mapper.findPermissionsByUserId(userId);
    return permissions == null ? Collections.<String>emptyList() : permissions;
  }

  /**
   * 查找菜单列表。
   *
   * @param userId 用户标识
   * @return 菜单列表
   */
  public List<Map<String, Object>> findMenus(String userId) {
    List<Map<String, Object>> menus = mapper.findMenusByUserId(userId);
    return menus == null ? Collections.<Map<String, Object>>emptyList() : menus;
  }

  /**
   * 查询用户列表。
   *
   * @param tenantId 租户标识
   * @return 用户列表
   */
  public List<Map<String, Object>> listUsers(String tenantId) {
    return mapper.listUsers(tenantId);
  }

  /**
   * 查询用户角色分配关系。
   *
   * @param tenantId 租户标识
   * @return 用户角色分配关系
   */
  public List<Map<String, Object>> listUserRoleAssignments(String tenantId) {
    List<Map<String, Object>> assignments = mapper.listUserRoleAssignments(tenantId);
    return assignments == null ? Collections.<Map<String, Object>>emptyList() : assignments;
  }

  /**
   * 查询用户有效权限分配关系。
   *
   * @param tenantId 租户标识
   * @return 用户有效权限分配列表
   */
  public List<Map<String, Object>> listUserEffectivePermissionAssignments(String tenantId) {
    List<Map<String, Object>> assignments = mapper.listUserEffectivePermissionAssignments(tenantId);
    return assignments == null ? Collections.<Map<String, Object>>emptyList() : assignments;
  }

  /**
   * 按标识查询用户。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 用户通过标识
   */
  public Map<String, Object> findUserById(String tenantId, String userId) {
    return mapper.findUserById(tenantId, userId);
  }

  /**
   * 查询权限定义。
   *
   * @return 权限定义
   */
  public List<Map<String, Object>> listPermissionDefinitions() {
    return mapper.listPermissionDefinitions();
  }

  /**
   * 新增用户并发数。
   *
   * @param userId 用户标识
   * @param tenantId 租户标识
   * @param username 用户名
   * @param passwordHash 密码哈希值
   * @param displayName 展示名称
   * @param role 角色
   * @param enabled 启用状态
   */
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

  /**
   * 更新用户。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param username 用户名
   * @param displayName 展示名称
   * @param role 角色
   * @param enabled 启用状态
   */
  public void updateUser(
      String tenantId,
      String userId,
      String username,
      String displayName,
      String role,
      boolean enabled) {
    mapper.updateUser(tenantId, userId, username, displayName, role, enabled, Instant.now());
  }

  /**
   * 更新密码哈希值。
   *
   * @param userId 用户标识
   * @param passwordHash 密码哈希值
   */
  public void updatePasswordHash(String userId, String passwordHash) {
    mapper.updatePasswordHash(userId, passwordHash, Instant.now());
  }

  /**
   * 保存会话。
   *
   * @param token 令牌
   * @param userId 用户标识
   * @param expiresAt 过期时间
   */
  public void saveSession(String token, String userId, Instant expiresAt) {
    mapper.saveSession(token, userId, expiresAt, Instant.now());
  }

  /**
   * 删除会话。
   *
   * @param token 令牌
   */
  public void deleteSession(String token) {
    mapper.deleteSession(token);
  }

  /**
   * 删除指定用户的全部会话。
   *
   * @param userId 用户标识
   */
  public void deleteSessionsByUserId(String userId) {
    mapper.deleteSessionsByUserId(userId);
  }

  /**
   * 删除过期会话列表。
   */
  public void deleteExpiredSessions() {
    mapper.deleteExpiredSessions(Instant.now());
  }

  /**
   * 规范化时间字符串。
   *
   * @param row 数据记录
   * @param keys 键列表
   * @return 规范化后的时间文本
   */
  private Map<String, Object> normalizeTimeStrings(Map<String, Object> row, String... keys) {
    if (row == null) return null;
    Map<String, Object> result = new LinkedHashMap<String, Object>(row);
    for (String key : keys)
      if (result.get(key) != null) result.put(key, toInstant(result.get(key)).toString());
    return result;
  }

  /**
   * 将数据库值转换为时间点。
   *
   * @param value 待处理值
   * @return 时间点
   */
  private Instant toInstant(Object value) {
    if (value instanceof Instant) return (Instant) value;
    if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toInstant();
    if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant();
    return Instant.parse(String.valueOf(value));
  }
}
