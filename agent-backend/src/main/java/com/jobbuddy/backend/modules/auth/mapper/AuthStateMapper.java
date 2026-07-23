package com.jobbuddy.backend.modules.auth.mapper;

import java.time.Instant;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface AuthStateMapper {
  Map<String, Object> findByProvider(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("provider") String provider);

  int countByProvider(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("provider") String provider);

  int insertState(@Param("state") Map<String, Object> state);

  int updateState(@Param("state") Map<String, Object> state);

  int upsertQrSession(@Param("state") Map<String, Object> state);

  int updateQrSessionToken(
      @Param("qrSessionId") String qrSessionId,
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("toolSessionToken") String toolSessionToken,
      @Param("toolSessionVersion") int toolSessionVersion,
      @Param("updatedAt") Instant updatedAt);

  Map<String, Object> findQrSession(@Param("qrSessionId") String qrSessionId);

  Map<String, Object> findActiveQrSession(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("now") Instant now);

  Map<String, Object> findQrSessionByChat(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("chatSessionId") String chatSessionId,
      @Param("now") Instant now);

  int deleteQrSession(
      @Param("qrSessionId") String qrSessionId,
      @Param("tenantId") String tenantId,
      @Param("userId") String userId);

  int deleteExpiredQrSessions(@Param("now") Instant now);
}
