package com.jobbuddy.backend.modules.chat.cache;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.common.util.JsonCodec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class ChatSessionCache {

    private static final Logger LOG = LoggerFactory.getLogger(ChatSessionCache.class);
    private static final Duration SESSION_CACHE_TTL = Duration.ofHours(12);

    private final StringRedisTemplate redisTemplate;
    private final JsonCodec jsonCodec;

    public ChatSessionCache(StringRedisTemplate redisTemplate, JsonCodec jsonCodec) {
        this.redisTemplate = redisTemplate;
        this.jsonCodec = jsonCodec;
    }

    public ChatSessionState get(String sessionId) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(cacheKey(sessionId));
        } catch (RuntimeException e) {
            LOG.warn("读取 Redis 会话缓存失败,将回退到 PostgreSQL - sessionId: {}", sessionId, e);
            return null;
        }
        if (json == null || json.isEmpty()) return null;
        Map<String, Object> map = jsonCodec.toMap(json);
        if (map.isEmpty()) return null;
        ChatSessionState state = new ChatSessionState();
        state.sessionId = String.valueOf(map.get("sessionId"));
        Object resumeId = map.get("resumeId");
        state.resumeId = resumeId == null ? null : String.valueOf(resumeId);
        state.lastSlots = map.get("lastSlots") instanceof Map ? (Map<String, Object>) map.get("lastSlots") : null;
        state.jobs = map.get("jobs") instanceof List ? (List<Map<String, Object>>) map.get("jobs") : new ArrayList<Map<String, Object>>();
        return state;
    }

    public void put(ChatSessionState state) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("sessionId", state.sessionId);
        payload.put("resumeId", state.resumeId);
        payload.put("lastSlots", state.lastSlots);
        payload.put("jobs", state.jobs);
        try {
            redisTemplate.opsForValue().set(cacheKey(state.sessionId), jsonCodec.toJson(payload), SESSION_CACHE_TTL);
        } catch (RuntimeException e) {
            LOG.warn("写入 Redis 会话缓存失败,PostgreSQL 持久化结果不受影响 - sessionId: {}", state.sessionId, e);
        }
    }

    public void evict(String sessionId) {
        try {
            redisTemplate.delete(cacheKey(sessionId));
        } catch (RuntimeException e) {
            LOG.warn("删除 Redis 会话缓存失败,继续清理 PostgreSQL - sessionId: {}", sessionId, e);
        }
    }

    private String cacheKey(String sessionId) {
        return "job-buddy:chat-session:" + sessionId;
    }
}
