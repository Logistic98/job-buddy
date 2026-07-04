package com.jobbuddy.backend.modules.chat.dto.response;

import lombok.Data;

import java.util.Map;

@Data
public class ChatSessionResponse {
    private String sessionId;
    private String resumeId;
    private Object updatedAt;
    private String title;

    public static ChatSessionResponse from(Map<String, Object> row) {
        ChatSessionResponse response = new ChatSessionResponse();
        response.setSessionId(stringOrNull(row.get("sessionId")));
        response.setResumeId(stringOrNull(row.get("resumeId")));
        response.setUpdatedAt(row.get("updatedAt"));
        response.setTitle(stringOrNull(row.get("title")));
        return response;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
