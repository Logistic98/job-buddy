package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolArguments;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;

public interface RuntimeToolClient {
  RuntimeToolResult invoke(
      String toolName, RuntimeToolArguments arguments, String sessionId, String workspaceDir);
}
