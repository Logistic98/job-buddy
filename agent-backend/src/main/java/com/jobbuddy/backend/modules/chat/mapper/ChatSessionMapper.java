package com.jobbuddy.backend.modules.chat.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Param;

public interface ChatSessionMapper {
  Map<String, Object> findById(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId);

  int upsertState(@Param("state") Map<String, Object> state);

  int appendMessage(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId,
      @Param("role") String role,
      @Param("content") String content,
      @Param("metadataJson") String metadataJson,
      @Param("createdAt") Instant createdAt);

  int appendUserMessageOnce(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId,
      @Param("turnId") String turnId,
      @Param("content") String content,
      @Param("metadataJson") String metadataJson,
      @Param("createdAt") Instant createdAt);

  String findUserMessageContentByTurnId(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId,
      @Param("turnId") String turnId);

  Map<String, Object> findLatestAssistantJobMessage(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId);

  int updateMessageMetadata(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("id") Long id,
      @Param("metadataJson") String metadataJson);

  List<Map<String, Object>> listSessions(
      @Param("tenantId") String tenantId, @Param("userId") String userId);

  List<Map<String, Object>> listMessages(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId);

  int deleteMessages(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId);

  int deleteState(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("sessionId") String sessionId);
}
