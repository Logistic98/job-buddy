package com.jobbuddy.backend.modules.auth.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface UserAuthMapper {
  Map<String, Object> findUserByUsername(@Param("username") String username);

  Map<String, Object> findUserByToken(@Param("token") String token);

  List<String> findRoleCodesByUserId(@Param("userId") String userId);

  List<String> findPermissionsByUserId(@Param("userId") String userId);

  List<Map<String, Object>> findMenusByUserId(@Param("userId") String userId);

  List<Map<String, Object>> listUsers(@Param("tenantId") String tenantId);

  List<Map<String, Object>> listUserRoleAssignments(@Param("tenantId") String tenantId);

  List<Map<String, Object>> listUserEffectivePermissionAssignments(
      @Param("tenantId") String tenantId);

  Map<String, Object> findUserById(
      @Param("tenantId") String tenantId, @Param("userId") String userId);

  List<Map<String, Object>> listPermissionDefinitions();

  int insertUser(
      @Param("userId") String userId,
      @Param("tenantId") String tenantId,
      @Param("username") String username,
      @Param("passwordHash") String passwordHash,
      @Param("displayName") String displayName,
      @Param("role") String role,
      @Param("enabled") boolean enabled,
      @Param("now") Instant now);

  int updateUser(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("username") String username,
      @Param("displayName") String displayName,
      @Param("role") String role,
      @Param("enabled") boolean enabled,
      @Param("now") Instant now);

  int updatePasswordHash(
      @Param("userId") String userId,
      @Param("passwordHash") String passwordHash,
      @Param("now") Instant now);

  int saveSession(
      @Param("token") String token,
      @Param("userId") String userId,
      @Param("expiresAt") Instant expiresAt,
      @Param("now") Instant now);

  int deleteSession(@Param("token") String token);

  int deleteSessionsByUserId(@Param("userId") String userId);

  int deleteExpiredSessions(@Param("now") Instant now);
}
