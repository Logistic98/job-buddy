package com.jobbuddy.backend.modules.chat.repository;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.mapper.ChatSessionMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Repository
public class ChatSessionRepository {
    private final ChatSessionMapper mapper;
    private final JsonCodec jsonCodec;

    public ChatSessionRepository(ChatSessionMapper mapper, JsonCodec jsonCodec) {
        this.mapper = mapper;
        this.jsonCodec = jsonCodec;
    }

    public ChatSessionState findById(String sessionId) {
        Map<String, Object> row = mapper.findById(sessionId);
        if (row == null) return null;
        ChatSessionState state = new ChatSessionState();
        state.sessionId = string(row.get("sessionId"));
        state.resumeId = string(row.get("resumeId"));
        state.lastSlots = jsonCodec.toMap(string(row.get("lastSlotsJson")));
        state.jobs = jsonCodec.toMapList(string(row.get("jobsJson")));
        state.toolEvents = jsonCodec.toMapList(string(row.get("toolEventsJson")));
        state.resumeMatch = jsonCodec.toMap(string(row.get("resumeMatchJson")));
        return state;
    }

    public void save(ChatSessionState state) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("sessionId", state.sessionId);
        row.put("resumeId", state.resumeId);
        row.put("lastSlotsJson", jsonCodec.toJson(state.lastSlots));
        row.put("jobsJson", jsonCodec.toJson(state.jobs));
        row.put("toolEventsJson", jsonCodec.toJson(state.toolEvents));
        row.put("resumeMatchJson", jsonCodec.toJson(state.resumeMatch));
        row.put("updatedAt", Instant.now());
        if (mapper.countById(state.sessionId) > 0) mapper.updateState(row); else mapper.insertState(row);
    }

    public void appendMessage(String sessionId, String role, String content) { appendMessage(sessionId, role, content, null); }

    public void appendMessage(String sessionId, String role, String content, Map<String, Object> metadata) {
        mapper.appendMessage(sessionId, role, content, jsonCodec.toJson(metadata), Instant.now());
    }

    public boolean replaceLatestAssistantJobMessage(String sessionId, List<Map<String, Object>> jobs, List<Map<String, Object>> toolEvents) {
        Map<String, Object> row = mapper.findLatestAssistantJobMessage(sessionId);
        if (row == null || row.get("id") == null) return false;
        Map<String, Object> metadata = jsonCodec.toMap(string(row.get("metadataJson")));
        metadata = metadata == null || metadata.isEmpty()
                ? new LinkedHashMap<String, Object>()
                : new LinkedHashMap<String, Object>(metadata);
        metadata.put("jobCards", jobs == null ? new ArrayList<Map<String, Object>>() : jobs);
        if (toolEvents != null && !toolEvents.isEmpty()) metadata.put("toolEvents", toolEvents);
        Long id = longValue(row.get("id"));
        return id != null && mapper.updateMessageMetadata(id, jsonCodec.toJson(metadata)) > 0;
    }

    public List<Map<String, Object>> listSessions() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : mapper.listSessions()) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("sessionId", row.get("sessionId"));
            item.put("resumeId", row.get("resumeId"));
            item.put("updatedAt", toInstantObject(row.get("updatedAt")));
            item.put("title", row.get("firstMessage") == null ? "新会话" : row.get("firstMessage"));
            result.add(item);
        }
        return result;
    }

    public List<Map<String, Object>> listMessages(String sessionId) {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : mapper.listMessages(sessionId)) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("role", row.get("role"));
            item.put("content", row.get("content"));
            Map<String, Object> metadata = jsonCodec.toMap(string(row.get("metadataJson")));
            if (metadata != null && !metadata.isEmpty()) {
                item.put("metadata", metadata);
                if (metadata.containsKey("jobCards")) item.put("jobCards", metadata.get("jobCards"));
                if (metadata.containsKey("resumeMatch")) item.put("resumeMatch", metadata.get("resumeMatch"));
                if (metadata.containsKey("toolEvents")) item.put("toolEvents", metadata.get("toolEvents"));
                if (metadata.containsKey("reasoning")) item.put("reasoning", metadata.get("reasoning"));
            }
            item.put("createdAt", toInstantObject(row.get("createdAt")));
            result.add(item);
        }
        return result;
    }

    public void deleteById(String sessionId) {
        mapper.deleteMessages(sessionId);
        mapper.deleteState(sessionId);
    }

    public static ChatSessionState newSession(String sessionId) {
        ChatSessionState state = new ChatSessionState();
        state.sessionId = sessionId;
        state.jobs = new ArrayList<Map<String, Object>>();
        state.toolEvents = new ArrayList<Map<String, Object>>();
        return state;
    }

    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private Long longValue(Object value) {
        if (value instanceof Number) return ((Number) value).longValue();
        try { return value == null ? null : Long.parseLong(String.valueOf(value)); } catch (Exception e) { return null; }
    }
    private Object toInstantObject(Object value) {
        if (value instanceof Instant) return value;
        if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toInstant();
        if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant();
        return value;
    }
}
