package com.jobbuddy.backend.modules.chat.dto.request;

import lombok.Data;

import java.util.Map;

@Data
public class ChatStreamRequest {
    private String message;
    private String sessionId;
    private String resumeId;
    private Boolean resumeAfterAuth;
    private Map<String, Object> selectedJob;
}
