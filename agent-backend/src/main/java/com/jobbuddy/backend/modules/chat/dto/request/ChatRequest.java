package com.jobbuddy.backend.modules.chat.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class ChatRequest {
  private String sessionId;

  @NotBlank(message = "消息不能为空")
  private String message;
}
