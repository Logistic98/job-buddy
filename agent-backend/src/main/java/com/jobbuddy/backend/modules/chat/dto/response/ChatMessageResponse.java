package com.jobbuddy.backend.modules.chat.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ChatMessageResponse {
    private String role;
    private String content;
    private Map<String, Object> metadata;
    private List<Map<String, Object>> jobCards;
    private Map<String, Object> resumeMatch;
    private List<Map<String, Object>> toolEvents;
    private Object reasoning;
    private Object createdAt;

    @SuppressWarnings("unchecked")
    public static ChatMessageResponse from(Map<String, Object> row) {
        ChatMessageResponse response = new ChatMessageResponse();
        response.setRole(stringOrNull(row.get("role")));
        response.setContent(stringOrNull(row.get("content")));
        Object metadata = row.get("metadata");
        if (metadata instanceof Map) response.setMetadata((Map<String, Object>) metadata);
        Object jobCards = row.get("jobCards");
        if (jobCards instanceof List) response.setJobCards((List<Map<String, Object>>) jobCards);
        Object resumeMatch = row.get("resumeMatch");
        if (resumeMatch instanceof Map) response.setResumeMatch((Map<String, Object>) resumeMatch);
        Object toolEvents = row.get("toolEvents");
        if (toolEvents instanceof List) response.setToolEvents((List<Map<String, Object>>) toolEvents);
        response.setReasoning(row.get("reasoning"));
        response.setCreatedAt(row.get("createdAt"));
        return response;
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value);
    }
}
