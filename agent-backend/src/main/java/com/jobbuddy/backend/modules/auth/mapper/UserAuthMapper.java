package com.jobbuddy.backend.modules.auth.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

/**
 * 映射用户认证数据记录。
 */
public interface UserAuthMapper {
  /**
   * 按用户名查询用户。
   *
   * @param username 用户名
   * @return 用户通过用户名
   */
  Map<String, Object> findUserByUsername(@Param("username") String username);

  /**
   * 按会话令牌查询用户。
   *
   * @param token 令牌
   * @return 用户通过令牌
   */
  Map<String, Object> findUserByToken(@Param("token") String token);

  /**
   * 查询用户的角色编码列表。
   *
   * @param userId 用户标识
   * @return 用户角色编码列表
   */
  List<String> findRoleCodesByUserId(@Param("userId") String userId);

  /**
   * 查询用户的有效权限列表。
   *
   * @param userId 用户标识
   * @return 权限列表通过用户标识
   */
  List<String> findPermissionsByUserId(@Param("userId") String userId);

  /**
   * 查询用户的可访问菜单列表。
   *
   * @param userId 用户标识
   * @return 菜单列表通过用户标识
   */
  List<Map<String, Object>> findMenusByUserId(@Param("userId") String userId);

  /**
   * 查询用户列表。
   *
   * @param tenantId 租户标识
   * @return 用户列表
   */
  List<Map<String, Object>> listUsers(@Param("tenantId") String tenantId);

  /**
   * 查询用户角色分配关系。
   *
   * @param tenantId 租户标识
   * @return 用户角色分配关系
   */
  List<Map<String, Object>> listUserRoleAssignments(@Param("tenantId") String tenantId);

  /**
   * 查询用户有效权限分配关系。
   *
   * @param tenantId 租户标识
   * @return 用户有效权限分配列表
   */
  List<Map<String, Object>> listUserEffectivePermissionAssignments(
      @Param("tenantId") String tenantId);

  /**
   * 按标识查询用户。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 用户通过标识
   */
  Map<String, Object> findUserById(
      @Param("tenantId") String tenantId, @Param("userId") String userId);

  /**
   * 查询权限定义。
   *
   * @return 权限定义
   */
  List<Map<String, Object>> listPermissionDefinitions();

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
   * @param now 当前时间
   * @return 用户并发数
   */
  int insertUser(
      @Param("userId") String userId,
      @Param("tenantId") String tenantId,
      @Param("username") String username,
      @Param("passwordHash") String passwordHash,
      @Param("displayName") String displayName,
      @Param("role") String role,
      @Param("enabled") boolean enabled,
      @Param("now") Instant now);

  /**
   * 更新用户。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param username 用户名
   * @param displayName 展示名称
   * @param role 角色
   * @param enabled 启用状态
   * @param now 当前时间
   * @return 用户并发数
   */
  int updateUser(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("username") String username,
      @Param("displayName") String displayName,
      @Param("role") String role,
      @Param("enabled") boolean enabled,
      @Param("now") Instant now);

  /**
   * 更新密码哈希值。
   *
   * @param userId 用户标识
   * @param passwordHash 密码哈希值
   * @param now 当前时间
   * @return 受影响的记录数
   */
  int updatePasswordHash(
      @Param("userId") String userId,
      @Param("passwordHash") String passwordHash,
      @Param("now") Instant now);

  /**
   * 保存会话。
   *
   * @param token 令牌
   * @param userId 用户标识
   * @param expiresAt 过期时间
   * @param now 当前时间
   * @return 会话
   */
  int saveSession(
      @Param("token") String token,
      @Param("userId") String userId,
      @Param("expiresAt") Instant expiresAt,
      @Param("now") Instant now);

  /**
   * 删除会话。
   *
   * @param token 令牌
   * @return 会话
   */
  int deleteSession(@Param("token") String token);

  /**
   * 删除指定用户的全部会话。
   *
   * @param userId 用户标识
   * @return Sessions 按用户并发数标识
   */
  int deleteSessionsByUserId(@Param("userId") String userId);

  /**
   * 删除过期会话列表。
   *
   * @param now 当前时间
   * @return 删除的会话数
   */
  int deleteExpiredSessions(@Param("now") Instant now);
}
