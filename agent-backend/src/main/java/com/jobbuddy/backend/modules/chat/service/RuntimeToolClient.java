package com.jobbuddy.backend.modules.chat.service;

import java.util.Map;

public interface RuntimeToolClient {
  Map<String, Object> invoke(
      String toolName, Map<String, Object> arguments, String sessionId, String workspaceDir);
}
