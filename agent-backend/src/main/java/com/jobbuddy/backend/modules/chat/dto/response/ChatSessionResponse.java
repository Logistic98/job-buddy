package com.jobbuddy.backend.modules.chat.dto.response;

import lombok.Data;

@Data
public class ChatSessionResponse {
  private String sessionId;
  private String resumeId;
  private Object updatedAt;
  private String title;
}
