package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.cache.ChatSessionCache;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.repository.ChatSessionRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

@Service
public class ChatSessionStore {

    private final ChatSessionRepository chatSessionRepository;
    private final ChatSessionCache chatSessionCache;

    public ChatSessionStore(ChatSessionRepository chatSessionRepository, ChatSessionCache chatSessionCache) {
        this.chatSessionRepository = chatSessionRepository;
        this.chatSessionCache = chatSessionCache;
    }

    public ChatSessionState getOrCreate(String sessionId) {
        ChatSessionState existing = get(sessionId);
        if (existing != null) return existing;
        ChatSessionState created = ChatSessionRepository.newSession(sessionId);
        save(created);
        return created;
    }

    public ChatSessionState get(String sessionId) {
        ChatSessionState cached = chatSessionCache.get(sessionId);
        if (cached != null) return cached;
        ChatSessionState loaded = chatSessionRepository.findById(sessionId);
        if (loaded != null) chatSessionCache.put(loaded);
        return loaded;
    }

    public void save(ChatSessionState state) {
        if (state == null || state.sessionId == null) return;
        state.toolEvents = filterMemoryNoiseEvents(state.toolEvents);
        chatSessionRepository.save(state);
        chatSessionCache.put(state);
    }

    public void appendMessage(String sessionId, String role, String content) {
        chatSessionRepository.appendMessage(sessionId, role, content);
    }

    public void appendMessage(String sessionId, String role, String content, Map<String, Object> metadata) {
        chatSessionRepository.appendMessage(sessionId, role, content, metadata);
    }

    public boolean replaceLatestAssistantJobMessage(String sessionId, List<Map<String, Object>> jobs, List<Map<String, Object>> toolEvents) {
        return chatSessionRepository.replaceLatestAssistantJobMessage(sessionId, jobs, filterMemoryNoiseEvents(toolEvents));
    }

    public void upsertToolEvent(String sessionId, Map<String, Object> event) {
        if (event == null || event.get("id") == null) return;
        ChatSessionState state = getOrCreate(sessionId);
        if (state.toolEvents == null) state.toolEvents = new java.util.ArrayList<Map<String, Object>>();
        String id = String.valueOf(event.get("id"));
        boolean replaced = false;
        for (int i = 0; i < state.toolEvents.size(); i++) {
            Map<String, Object> existing = state.toolEvents.get(i);
            if (id.equals(String.valueOf(existing.get("id")))) {
                Map<String, Object> merged = new java.util.LinkedHashMap<String, Object>(existing);
                merged.putAll(event);
                state.toolEvents.set(i, merged);
                replaced = true;
                break;
            }
        }
        if (!replaced && !isMemoryNoiseEvent(event)) state.toolEvents.add(event);
        state.toolEvents = filterMemoryNoiseEvents(state.toolEvents);
        save(state);
    }

    public void updateResumeMatch(String sessionId, Map<String, Object> match) {
        ChatSessionState state = getOrCreate(sessionId);
        state.resumeMatch = match;
        save(state);
    }

    public List<Map<String, Object>> listSessions() {
        return chatSessionRepository.listSessions();
    }

    public List<Map<String, Object>> listMessages(String sessionId) {
        List<Map<String, Object>> rows = chatSessionRepository.listMessages(sessionId);
        boolean hasJobCards = false;
        for (Map<String, Object> row : rows) {
            Object cards = row.get("jobCards");
            if (cards instanceof List && !((List<?>) cards).isEmpty()) {
                hasJobCards = true;
                break;
            }
        }
        ChatSessionState state = get(sessionId);
        if (state != null) {
            Map<String, Object> target = null;
            for (int i = rows.size() - 1; i >= 0; i--) {
                Map<String, Object> row = rows.get(i);
                if ("assistant".equals(row.get("role"))) {
                    target = row;
                    break;
                }
            }
            if (target == null && ((state.jobs != null && !state.jobs.isEmpty()) || (state.toolEvents != null && !state.toolEvents.isEmpty()))) {
                target = new java.util.LinkedHashMap<String, Object>();
                target.put("role", "assistant");
                target.put("content", "");
                rows.add(target);
            }
            if (target != null) {
                if (!hasJobCards && state.jobs != null && !state.jobs.isEmpty()) target.put("jobCards", state.jobs);
                List<Map<String, Object>> visibleToolEvents = filterMemoryNoiseEvents(state.toolEvents);
                if (visibleToolEvents != null && !visibleToolEvents.isEmpty()) target.put("toolEvents", visibleToolEvents);
                if (state.resumeMatch != null && !state.resumeMatch.isEmpty()) target.put("resumeMatch", state.resumeMatch);
            }
        }
        return rows;
    }

    public void clear(String sessionId) {
        chatSessionCache.evict(sessionId);
        chatSessionRepository.deleteById(sessionId);
    }

    private List<Map<String, Object>> filterMemoryNoiseEvents(List<Map<String, Object>> events) {
        List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        if (events == null) return rows;
        for (Map<String, Object> event : events) {
            if (!isMemoryNoiseEvent(event)) rows.add(event);
        }
        return rows;
    }

    private boolean isMemoryNoiseEvent(Map<String, Object> event) {
        if (event == null) return false;
        // 仅按稳定标识字段（id/name）判定记忆读取类噪声步骤；不要匹配 title/summary 等展示文案，
        // 否则用户问题或步骤摘要里出现“记忆/memory”字样时整条推理步骤会被误删。
        StringBuilder builder = new StringBuilder();
        for (String key : new String[]{"id", "name"}) {
            Object value = event.get(key);
            if (value != null) builder.append(' ').append(String.valueOf(value).toLowerCase(java.util.Locale.ROOT));
        }
        String text = builder.toString();
        return text.contains("memory") || text.contains("记忆");
    }
}
