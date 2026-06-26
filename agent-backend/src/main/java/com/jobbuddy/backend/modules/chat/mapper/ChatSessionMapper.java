package com.jobbuddy.backend.modules.chat.mapper;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for chat session state and message history persistence.
 */
public interface ChatSessionMapper {

    Map<String, Object> findById(@Param("sessionId") String sessionId);

    int countById(@Param("sessionId") String sessionId);

    int insertState(@Param("state") Map<String, Object> state);

    int updateState(@Param("state") Map<String, Object> state);

    int appendMessage(
            @Param("sessionId") String sessionId,
            @Param("role") String role,
            @Param("content") String content,
            @Param("metadataJson") String metadataJson,
            @Param("createdAt") Instant createdAt);

    Map<String, Object> findLatestAssistantJobMessage(@Param("sessionId") String sessionId);

    int updateMessageMetadata(
            @Param("id") Long id,
            @Param("metadataJson") String metadataJson);

    List<Map<String, Object>> listSessions();

    List<Map<String, Object>> listMessages(@Param("sessionId") String sessionId);

    int deleteMessages(@Param("sessionId") String sessionId);

    int deleteState(@Param("sessionId") String sessionId);
}
