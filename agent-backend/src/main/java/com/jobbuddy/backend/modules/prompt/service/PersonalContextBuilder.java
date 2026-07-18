package com.jobbuddy.backend.modules.prompt.service;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.prompt.model.PersonalContext;

public interface PersonalContextBuilder {
  PersonalContext build(
      String tenantId, String userId, String message, IntentResult intent, ChatSessionState state);
}
