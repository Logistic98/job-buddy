package com.jobbuddy.backend.modules.chat.dto.response;

import lombok.Data;

/**
 * 承载对话会话响应数据。
 */
@Data
public class ChatSessionResponse {
  private String sessionId;
  private String resumeId;
  private Object updatedAt;
  private String title;
}
