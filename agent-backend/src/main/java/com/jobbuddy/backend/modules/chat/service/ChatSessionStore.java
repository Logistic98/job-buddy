package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.dto.response.ChatMessageResponse;
import com.jobbuddy.backend.modules.chat.dto.response.ChatSessionResponse;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import java.util.List;
import java.util.Map;

public interface ChatSessionStore {
  void bindOwner(String sessionId, String tenantId, String userId);

  ChatSessionState getOrCreate(String sessionId);

  ChatSessionState get(String sessionId);

  void save(ChatSessionState state);

  void appendMessage(String sessionId, String role, String content);

  void appendMessage(String sessionId, String role, String content, Map<String, Object> metadata);

  boolean appendUserMessageOnce(String sessionId, String turnId, String content);

  boolean replaceLatestAssistantJobMessage(
      String sessionId, List<Map<String, Object>> jobs, List<Map<String, Object>> toolEvents);

  void upsertToolEvent(String sessionId, Map<String, Object> event);

  void updateResumeMatch(String sessionId, Map<String, Object> match);

  List<ChatSessionResponse> listSessions(String tenantId, String userId);

  List<ChatMessageResponse> listMessages(String tenantId, String userId, String sessionId);

  void clear(String tenantId, String userId, String sessionId);
}
