package com.jobbuddy.backend.modules.chat.repository;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.mapper.ChatSessionMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 将聊天持久化行映射为会话状态，并把 JSON 元数据转换限制在存储边界。
 */
@Repository
public class ChatSessionRepository {
  private final ChatSessionMapper mapper;
  private final JsonCodec jsonCodec;

  /**
   * 创建对话会话存储访问实例。
   *
   * @param mapper 数据映射
   * @param jsonCodec JSON 编解码器
   */
  public ChatSessionRepository(ChatSessionMapper mapper, JsonCodec jsonCodec) {
    this.mapper = mapper;
    this.jsonCodec = jsonCodec;
  }

  /**
   * 按标识查询记录。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @return 按标识查询到的记录
   */
  public ChatSessionState findById(String tenantId, String userId, String sessionId) {
    Map<String, Object> row = mapper.findById(tenantId, userId, sessionId);
    if (row == null) return null;
    ChatSessionState state = new ChatSessionState();
    state.tenantId = string(row.get("tenantId"));
    state.userId = string(row.get("userId"));
    state.sessionId = string(row.get("sessionId"));
    state.resumeId = string(row.get("resumeId"));
    state.lastSlots = jsonCodec.toMap(string(row.get("lastSlotsJson")));
    state.jobs = jsonCodec.toMapList(string(row.get("jobsJson")));
    state.toolEvents = jsonCodec.toMapList(string(row.get("toolEventsJson")));
    state.resumeMatch = jsonCodec.toMap(string(row.get("resumeMatchJson")));
    return state;
  }

  /**
   * 保存对话会话存储访问。
   *
   * @param state 状态
   */
  public void save(ChatSessionState state) {
    Map<String, Object> row = new HashMap<String, Object>();
    row.put("tenantId", state.tenantId);
    row.put("userId", state.userId);
    row.put("sessionId", state.sessionId);
    row.put("resumeId", state.resumeId);
    row.put("lastSlotsJson", jsonCodec.toJson(state.lastSlots));
    row.put("jobsJson", jsonCodec.toJson(state.jobs));
    row.put("toolEventsJson", jsonCodec.toJson(state.toolEvents));
    row.put("resumeMatchJson", jsonCodec.toJson(state.resumeMatch));
    row.put("updatedAt", Instant.now());
    if (mapper.upsertState(row) == 0) {
      throw new IllegalArgumentException("会话已属于其他用户");
    }
  }

  /**
   * 追加会话消息。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @param role 角色
   * @param content 内容
   */
  public void appendMessage(
      String tenantId, String userId, String sessionId, String role, String content) {
    appendMessage(tenantId, userId, sessionId, role, content, null);
  }

  /**
   * 追加会话消息。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @param role 角色
   * @param content 内容
   * @param metadata 元数据
   */
  public void appendMessage(
      String tenantId,
      String userId,
      String sessionId,
      String role,
      String content,
      Map<String, Object> metadata) {
    mapper.appendMessage(
        tenantId, userId, sessionId, role, content, jsonCodec.toJson(metadata), Instant.now());
  }

  /**
   * 用稳定 turnId 原子写入用户消息。相同 turnId 的同一请求视为幂等重放；若载荷不同则拒绝，避免错误复用动作身份。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @param turnId 对话轮次标识
   * @param content 内容
   * @return 消息是否首次写入
   */
  public boolean appendUserMessageOnce(
      String tenantId, String userId, String sessionId, String turnId, String content) {
    return appendUserMessageOnce(tenantId, userId, sessionId, turnId, content, null);
  }

  /**
   * 用稳定 turnId 原子写入携带元数据的用户消息。重放时内容和元数据都必须一致。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @param turnId 轮次标识
   * @param content 内容
   * @param metadata 消息元数据
   * @return 消息是否首次写入
   */
  public boolean appendUserMessageOnce(
      String tenantId,
      String userId,
      String sessionId,
      String turnId,
      String content,
      Map<String, Object> metadata) {
    String normalizedTurnId = turnId == null ? "" : turnId.trim();
    if (normalizedTurnId.isEmpty()) {
      appendMessage(tenantId, userId, sessionId, "user", content, metadata);
      return true;
    }
    String metadataJson = jsonCodec.toJson(metadata);
    int inserted =
        mapper.appendUserMessageOnce(
            tenantId, userId, sessionId, normalizedTurnId, content, metadataJson, Instant.now());
    if (inserted > 0) return true;
    Map<String, Object> existing =
        mapper.findUserMessageByTurnId(tenantId, userId, sessionId, normalizedTurnId);
    String existingContent = existing == null ? null : string(existing.get("content"));
    String existingMetadata = existing == null ? null : string(existing.get("metadataJson"));
    if (!java.util.Objects.equals(existingContent, content)
        || !java.util.Objects.equals(
            jsonCodec.toMap(existingMetadata), jsonCodec.toMap(metadataJson))) {
      throw new IllegalArgumentException("同一 turnId 不能用于不同的用户消息");
    }
    return false;
  }

  /**
   * 替换最近助手岗位消息。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @param jobs 岗位列表
   * @param toolEvents 工具事件列表
   * @return 是否替换成功
   */
  public boolean replaceLatestAssistantJobMessage(
      String tenantId,
      String userId,
      String sessionId,
      List<Map<String, Object>> jobs,
      List<Map<String, Object>> toolEvents) {
    Map<String, Object> row = mapper.findLatestAssistantJobMessage(tenantId, userId, sessionId);
    if (row == null || row.get("id") == null) return false;
    Map<String, Object> metadata = jsonCodec.toMap(string(row.get("metadataJson")));
    metadata =
        metadata == null || metadata.isEmpty()
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(metadata);
    metadata.put("jobCards", jobs == null ? new ArrayList<Map<String, Object>>() : jobs);
    if (toolEvents != null && !toolEvents.isEmpty()) metadata.put("toolEvents", toolEvents);
    Long id = longValue(row.get("id"));
    return id != null
        && mapper.updateMessageMetadata(tenantId, userId, id, jsonCodec.toJson(metadata)) > 0;
  }

  /**
   * 查询会话列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 会话列表
   */
  public List<Map<String, Object>> listSessions(String tenantId, String userId) {
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> row : mapper.listSessions(tenantId, userId)) {
      Map<String, Object> item = new LinkedHashMap<String, Object>();
      item.put("sessionId", row.get("sessionId"));
      item.put("resumeId", row.get("resumeId"));
      item.put("updatedAt", toInstantObject(row.get("updatedAt")));
      item.put("title", row.get("firstMessage") == null ? "新会话" : row.get("firstMessage"));
      result.add(item);
    }
    return result;
  }

  /**
   * 查询消息列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @return 消息列表
   */
  public List<Map<String, Object>> listMessages(String tenantId, String userId, String sessionId) {
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> row : mapper.listMessages(tenantId, userId, sessionId)) {
      Map<String, Object> item = new LinkedHashMap<String, Object>();
      item.put("id", row.get("id"));
      item.put("turnId", row.get("turnId"));
      item.put("role", row.get("role"));
      item.put("content", row.get("content"));
      Map<String, Object> metadata = jsonCodec.toMap(string(row.get("metadataJson")));
      if (metadata != null && !metadata.isEmpty()) {
        item.put("metadata", metadata);
        if (metadata.containsKey("attachments"))
          item.put("attachments", metadata.get("attachments"));
        if (metadata.containsKey("jobCards")) item.put("jobCards", metadata.get("jobCards"));
        if (metadata.containsKey("resumeMatch"))
          item.put("resumeMatch", metadata.get("resumeMatch"));
        if (metadata.containsKey("toolEvents")) item.put("toolEvents", metadata.get("toolEvents"));
        if (metadata.containsKey("reasoning")) item.put("reasoning", metadata.get("reasoning"));
      }
      item.put("createdAt", toInstantObject(row.get("createdAt")));
      result.add(item);
    }
    return result;
  }

  /**
   * 按标识删除记录。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   */
  public void deleteById(String tenantId, String userId, String sessionId) {
    mapper.deleteMessages(tenantId, userId, sessionId);
    mapper.deleteState(tenantId, userId, sessionId);
  }

  /**
   * 创建会话。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @return 会话
   */
  public static ChatSessionState newSession(String tenantId, String userId, String sessionId) {
    ChatSessionState state = new ChatSessionState();
    state.tenantId = tenantId;
    state.userId = userId;
    state.sessionId = sessionId;
    state.jobs = new ArrayList<Map<String, Object>>();
    state.toolEvents = new ArrayList<Map<String, Object>>();
    return state;
  }

  /**
   * 将输入转换为字符串。
   *
   * @param value 待处理值
   * @return 字符串值
   */
  private String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  /**
   * 读取长整型值。
   *
   * @param value 待处理值
   * @return 长整型值
   */
  private Long longValue(Object value) {
    if (value instanceof Number) return ((Number) value).longValue();
    try {
      return value == null ? null : Long.parseLong(String.valueOf(value));
    } catch (Exception e) {
      return null;
    }
  }

  /**
   * 将输入转换为时间点对象。
   *
   * @param value 待处理值
   * @return 时间点对象
   */
  private Object toInstantObject(Object value) {
    if (value instanceof Instant) return value;
    if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toInstant();
    if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant();
    return value;
  }
}
