package com.jobbuddy.backend.modules.chat.cache;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

@Component
public class ChatSessionCache {

  private static final Logger LOG = LoggerFactory.getLogger(ChatSessionCache.class);
  private static final Duration SESSION_CACHE_TTL = Duration.ofHours(12);

  private final StringRedisTemplate redisTemplate;
  private final JsonCodec jsonCodec;
  private final AtomicLong unavailableUntilMillis = new AtomicLong(0L);
  private volatile Duration failureCooldown = Duration.ofSeconds(30);

  public ChatSessionCache(StringRedisTemplate redisTemplate, JsonCodec jsonCodec) {
    this.redisTemplate = redisTemplate;
    this.jsonCodec = jsonCodec;
  }

  @Value("${job-buddy.chat.redis-failure-cooldown:30s}")
  void setFailureCooldown(Duration failureCooldown) {
    if (failureCooldown != null && !failureCooldown.isNegative())
      this.failureCooldown = failureCooldown;
  }

  public ChatSessionState get(String sessionId) {
    if (isTemporarilyUnavailable()) return null;
    String json;
    try {
      json = redisTemplate.opsForValue().get(cacheKey(sessionId));
      markAvailable();
    } catch (RuntimeException e) {
      markUnavailable("读取", sessionId, e);
      return null;
    }
    if (json == null || json.isEmpty()) return null;
    Map<String, Object> map = jsonCodec.toMap(json);
    if (map.isEmpty()) return null;
    ChatSessionState state = new ChatSessionState();
    state.tenantId = stringValue(map.get("tenantId"));
    state.userId = stringValue(map.get("userId"));
    state.sessionId = stringValue(map.get("sessionId"));
    Object resumeId = map.get("resumeId");
    state.resumeId = resumeId == null ? null : String.valueOf(resumeId);
    state.lastSlots =
        map.get("lastSlots") instanceof Map ? (Map<String, Object>) map.get("lastSlots") : null;
    state.jobs =
        map.get("jobs") instanceof List
            ? (List<Map<String, Object>>) map.get("jobs")
            : new ArrayList<Map<String, Object>>();
    state.toolEvents =
        map.get("toolEvents") instanceof List
            ? (List<Map<String, Object>>) map.get("toolEvents")
            : new ArrayList<Map<String, Object>>();
    state.resumeMatch =
        map.get("resumeMatch") instanceof Map ? (Map<String, Object>) map.get("resumeMatch") : null;
    if (state.tenantId == null || state.userId == null || state.sessionId == null) {
      LOG.warn("忽略缺少属主字段的 Redis 会话缓存 - sessionId: {}", sessionId);
      evict(sessionId);
      return null;
    }
    return state;
  }

  public void put(ChatSessionState state) {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("tenantId", state.tenantId);
    payload.put("userId", state.userId);
    payload.put("sessionId", state.sessionId);
    payload.put("resumeId", state.resumeId);
    payload.put("lastSlots", state.lastSlots);
    payload.put("jobs", state.jobs);
    payload.put("toolEvents", state.toolEvents);
    payload.put("resumeMatch", state.resumeMatch);
    if (isTemporarilyUnavailable()) return;
    try {
      redisTemplate
          .opsForValue()
          .set(cacheKey(state.sessionId), jsonCodec.toJson(payload), SESSION_CACHE_TTL);
      markAvailable();
    } catch (RuntimeException e) {
      markUnavailable("写入", state.sessionId, e);
    }
  }

  public void evict(String sessionId) {
    if (isTemporarilyUnavailable()) return;
    try {
      redisTemplate.delete(cacheKey(sessionId));
      markAvailable();
    } catch (RuntimeException e) {
      markUnavailable("删除", sessionId, e);
    }
  }

  private String stringValue(Object value) {
    if (value == null) return null;
    String text = String.valueOf(value).trim();
    return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
  }

  private boolean isTemporarilyUnavailable() {
    return unavailableUntilMillis.get() > System.currentTimeMillis();
  }

  private void markAvailable() {
    unavailableUntilMillis.set(0L);
  }

  private void markUnavailable(String operation, String sessionId, RuntimeException error) {
    long now = System.currentTimeMillis();
    long cooldownMillis = Math.max(0L, failureCooldown.toMillis());
    long previous = unavailableUntilMillis.getAndSet(now + cooldownMillis);
    if (previous <= now) {
      LOG.warn(
          "{} Redis 会话缓存失败,将在 {} ms 内直接回退 PostgreSQL - sessionId: {}, error: {}",
          operation,
          cooldownMillis,
          sessionId,
          conciseMessage(error));
      LOG.debug("Redis 会话缓存异常详情 - operation: {}, sessionId: {}", operation, sessionId, error);
    }
  }

  private String conciseMessage(Throwable error) {
    if (error == null) return "unknown";
    Throwable cause = error;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    String message = cause.getMessage();
    if (message == null || message.trim().isEmpty()) message = cause.getClass().getSimpleName();
    message = message.trim().replace('\n', ' ').replace('\r', ' ');
    return message.length() <= 180 ? message : message.substring(0, 180) + "...";
  }

  private String cacheKey(String sessionId) {
    return "job-buddy:chat-session:" + sessionId;
  }
}
