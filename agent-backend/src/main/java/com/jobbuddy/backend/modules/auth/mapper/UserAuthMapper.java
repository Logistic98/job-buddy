package com.jobbuddy.backend.modules.auth.mapper;

import java.time.Instant;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for local user accounts and login sessions.
 */
public interface UserAuthMapper {

    Map<String, Object> findUserByUsername(@Param("username") String username);

    int updatePasswordHash(
            @Param("userId") String userId,
            @Param("passwordHash") String passwordHash,
            @Param("now") Instant now);

    Map<String, Object> findUserByToken(@Param("token") String token);

    int saveSession(
            @Param("token") String token,
            @Param("userId") String userId,
            @Param("expiresAt") Instant expiresAt,
            @Param("now") Instant now);

    int touchSession(
            @Param("token") String token,
            @Param("now") Instant now);

    int deleteSession(@Param("token") String token);

    int deleteExpiredSessions(@Param("now") Instant now);
}
