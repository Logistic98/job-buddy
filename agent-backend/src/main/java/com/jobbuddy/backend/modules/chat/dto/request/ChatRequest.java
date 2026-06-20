package com.jobbuddy.backend.modules.chat.dto.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;

@Data
public class ChatRequest {
    private String sessionId;

    @NotBlank(message = "消息不能为空")
    private String message;
}
