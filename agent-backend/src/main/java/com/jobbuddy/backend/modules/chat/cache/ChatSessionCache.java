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

/**
 * 为活跃会话提供尽力而为的 Redis 缓存。
 *
 * <p>缓存失败可观测，但恢复与属主判断仍以 PostgreSQL 为准。
 */
@Component
public class ChatSessionCache {

  private static final Logger LOG = LoggerFactory.getLogger(ChatSessionCache.class);
  private static final Duration SESSION_CACHE_TTL = Duration.ofHours(12);

  private final StringRedisTemplate redisTemplate;
  private final JsonCodec jsonCodec;
  private final AtomicLong unavailableUntilMillis = new AtomicLong(0L);
  private volatile Duration failureCooldown = Duration.ofSeconds(30);

  /**
   * 创建对话会话缓存实例。
   *
   * @param redisTemplate Redis 模板
   * @param jsonCodec JSON 编解码器
   */
  public ChatSessionCache(StringRedisTemplate redisTemplate, JsonCodec jsonCodec) {
    this.redisTemplate = redisTemplate;
    this.jsonCodec = jsonCodec;
  }

  /**
   * 设置失败冷却期。
   *
   * @param failureCooldown 失败冷却时间
   */
  @Value("${job-buddy.chat.redis-failure-cooldown:30s}")
  void setFailureCooldown(Duration failureCooldown) {
    if (failureCooldown != null && !failureCooldown.isNegative())
      this.failureCooldown = failureCooldown;
  }

  /**
   * 按会话标识读取缓存状态。
   *
   * @param sessionId 会话标识
   * @return 查询结果
   */
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

  /**
   * 写入缓存数据。
   *
   * @param state 状态
   */
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

  /**
   * 移除会话缓存。
   *
   * @param sessionId 会话标识
   */
  public void evict(String sessionId) {
    if (isTemporarilyUnavailable()) return;
    try {
      redisTemplate.delete(cacheKey(sessionId));
      markAvailable();
    } catch (RuntimeException e) {
      markUnavailable("删除", sessionId, e);
    }
  }

  /**
   * 读取字符串值。
   *
   * @param value 待处理值
   * @return 字符串值
   */
  private String stringValue(Object value) {
    if (value == null) return null;
    String text = String.valueOf(value).trim();
    return text.isEmpty() || "null".equalsIgnoreCase(text) ? null : text;
  }

  /**
   * 判断是否暂时不可用。
   *
   * @return 缓存是否暂时不可用
   */
  private boolean isTemporarilyUnavailable() {
    return unavailableUntilMillis.get() > System.currentTimeMillis();
  }

  /**
   * 标记缓存服务可用。
   */
  private void markAvailable() {
    unavailableUntilMillis.set(0L);
  }

  /**
   * 标记缓存服务不可用。
   *
   * @param operation 操作名称
   * @param sessionId 会话标识
   * @param error 错误
   */
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

  /**
   * 压缩错误消息。
   *
   * @param error 错误
   * @return 精简错误消息
   */
  private String conciseMessage(Throwable error) {
    if (error == null) return "unknown";
    Throwable cause = error;
    while (cause.getCause() != null && cause.getCause() != cause) cause = cause.getCause();
    String message = cause.getMessage();
    if (message == null || message.trim().isEmpty()) message = cause.getClass().getSimpleName();
    message = message.trim().replace('\n', ' ').replace('\r', ' ');
    return message.length() <= 180 ? message : message.substring(0, 180) + "...";
  }

  /**
   * 构造会话缓存键。
   *
   * @param sessionId 会话标识
   * @return 会话缓存键
   */
  private String cacheKey(String sessionId) {
    return "job-buddy:chat-session:" + sessionId;
  }
}
