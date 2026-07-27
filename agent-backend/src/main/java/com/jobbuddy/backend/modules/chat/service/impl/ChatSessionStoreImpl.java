package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.cache.ChatSessionCache;
import com.jobbuddy.backend.modules.chat.dto.response.ChatMessageResponse;
import com.jobbuddy.backend.modules.chat.dto.response.ChatSessionResponse;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.repository.ChatSessionRepository;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import org.springframework.stereotype.Service;

/**
 * 组合进程内会话缓存与 PostgreSQL 聊天仓储。
 *
 * <p>跨重启状态以仓储为准；缓存项按属主绑定并使用防御性副本。
 */
@Service
public class ChatSessionStoreImpl implements ChatSessionStore {

  private final ChatSessionRepository chatSessionRepository;
  private final ChatSessionCache chatSessionCache;
  private final ConcurrentMap<String, Owner> owners = new ConcurrentHashMap<String, Owner>();
  private final JsonCodec jsonCodec = new JsonCodec();

  /**
   * 创建对话会话存储实例。
   *
   * @param chatSessionRepository 对话会话存储访问
   * @param chatSessionCache 对话会话缓存
   */
  public ChatSessionStoreImpl(
      ChatSessionRepository chatSessionRepository, ChatSessionCache chatSessionCache) {
    this.chatSessionRepository = chatSessionRepository;
    this.chatSessionCache = chatSessionCache;
  }

  /**
   * 绑定属主。
   *
   * @param sessionId 会话标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   */
  @Override
  public void bindOwner(String sessionId, String tenantId, String userId) {
    String normalizedSessionId = requireValue(sessionId, "sessionId");
    Owner next = new Owner(tenantId, userId);
    Owner previous = owners.putIfAbsent(normalizedSessionId, next);
    if (previous != null && !previous.equals(next)) {
      throw new IllegalArgumentException("无权访问该会话");
    }
  }

  /**
   * 获取或创建会话。
   *
   * @param sessionId 会话标识
   * @return 或创建会话
   */
  @Override
  public ChatSessionState getOrCreate(String sessionId) {
    ChatSessionState existing = get(sessionId);
    if (existing != null) return existing;
    Owner owner = owner(sessionId);
    ChatSessionState created =
        ChatSessionRepository.newSession(owner.tenantId, owner.userId, sessionId);
    save(created);
    return created;
  }

  /**
   * 按标识读取数据。
   *
   * @param sessionId 会话标识
   * @return 查询结果
   */
  @Override
  public ChatSessionState get(String sessionId) {
    Owner owner = owner(sessionId);
    ChatSessionState cached = chatSessionCache.get(sessionId);
    if (cached != null && owner.matches(cached)) return cached;
    ChatSessionState loaded =
        chatSessionRepository.findById(owner.tenantId, owner.userId, sessionId);
    if (loaded != null) chatSessionCache.put(loaded);
    return loaded;
  }

  /**
   * 保存会话数据。
   *
   * @param state 状态
   */
  @Override
  public void save(ChatSessionState state) {
    if (state == null || state.sessionId == null) return;
    Owner owner = owner(state.sessionId);
    if (!owner.matches(state)) throw new IllegalArgumentException("会话属主不匹配");
    state.toolEvents = filterMemoryNoiseEvents(state.toolEvents);
    chatSessionRepository.save(state);
    chatSessionCache.put(state);
  }

  /**
   * 追加消息。
   *
   * @param sessionId 会话标识
   * @param role 角色
   * @param content 内容
   */
  @Override
  public void appendMessage(String sessionId, String role, String content) {
    Owner owner = owner(sessionId);
    chatSessionRepository.appendMessage(owner.tenantId, owner.userId, sessionId, role, content);
  }

  /**
   * 追加消息。
   *
   * @param sessionId 会话标识
   * @param role 角色
   * @param content 内容
   * @param metadata 扩展元数据
   */
  @Override
  public void appendMessage(
      String sessionId, String role, String content, Map<String, Object> metadata) {
    Owner owner = owner(sessionId);
    chatSessionRepository.appendMessage(
        owner.tenantId, owner.userId, sessionId, role, content, metadata);
  }

  /**
   * 仅追加一次用户消息。
   *
   * @param sessionId 会话标识
   * @param turnId 对话轮次标识
   * @param content 内容
   * @return 消息是否首次写入
   */
  @Override
  public boolean appendUserMessageOnce(String sessionId, String turnId, String content) {
    return appendUserMessageOnce(sessionId, turnId, content, null);
  }

  @Override
  public boolean appendUserMessageOnce(
      String sessionId, String turnId, String content, Map<String, Object> metadata) {
    Owner owner = owner(sessionId);
    return chatSessionRepository.appendUserMessageOnce(
        owner.tenantId, owner.userId, sessionId, turnId, content, metadata);
  }

  /**
   * 替换最新助手岗位消息。
   *
   * @param sessionId 会话标识
   * @param jobs 岗位列表
   * @param toolEvents 工具事件列表
   * @return 更新后的消息列表
   */
  @Override
  public boolean replaceLatestAssistantJobMessage(
      String sessionId, List<Map<String, Object>> jobs, List<Map<String, Object>> toolEvents) {
    Owner owner = owner(sessionId);
    return chatSessionRepository.replaceLatestAssistantJobMessage(
        owner.tenantId, owner.userId, sessionId, jobs, filterMemoryNoiseEvents(toolEvents));
  }

  /**
   * 新增或更新工具事件。
   *
   * @param sessionId 会话标识
   * @param event 事件名称
   */
  @Override
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

  /**
   * 更新简历匹配结果。
   *
   * @param sessionId 会话标识
   * @param match 匹配结果
   */
  @Override
  public void updateResumeMatch(String sessionId, Map<String, Object> match) {
    ChatSessionState state = getOrCreate(sessionId);
    state.resumeMatch = match;
    save(state);
  }

  /**
   * 查询会话列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 会话列表
   */
  @Override
  public List<ChatSessionResponse> listSessions(String tenantId, String userId) {
    return jsonCodec.convertList(
        chatSessionRepository.listSessions(tenantId, userId), ChatSessionResponse.class);
  }

  /**
   * 查询消息列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   * @return 消息列表
   */
  @Override
  public List<ChatMessageResponse> listMessages(String tenantId, String userId, String sessionId) {
    bindOwner(sessionId, tenantId, userId);
    List<Map<String, Object>> rows =
        chatSessionRepository.listMessages(tenantId, userId, sessionId);
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
      if (target == null
          && ((state.jobs != null && !state.jobs.isEmpty())
              || (state.toolEvents != null && !state.toolEvents.isEmpty()))) {
        target = new java.util.LinkedHashMap<String, Object>();
        target.put("role", "assistant");
        target.put("content", "");
        rows.add(target);
      }
      if (target != null) {
        if (!hasJobCards && state.jobs != null && !state.jobs.isEmpty())
          target.put("jobCards", state.jobs);
        List<Map<String, Object>> visibleToolEvents = filterMemoryNoiseEvents(state.toolEvents);
        if (visibleToolEvents != null && !visibleToolEvents.isEmpty())
          target.put("toolEvents", visibleToolEvents);
        if (state.resumeMatch != null && !state.resumeMatch.isEmpty())
          target.put("resumeMatch", state.resumeMatch);
      }
    }
    return jsonCodec.convertList(rows, ChatMessageResponse.class);
  }

  /**
   * 清理会话数据。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param sessionId 会话标识
   */
  @Override
  public void clear(String tenantId, String userId, String sessionId) {
    bindOwner(sessionId, tenantId, userId);
    chatSessionCache.evict(sessionId);
    chatSessionRepository.deleteById(tenantId, userId, sessionId);
    owners.remove(sessionId, new Owner(tenantId, userId));
  }

  /**
   * 获取属主。
   *
   * @param sessionId 会话标识
   * @return 属主
   */
  private Owner owner(String sessionId) {
    String normalizedSessionId = requireValue(sessionId, "sessionId");
    Owner owner = owners.get(normalizedSessionId);
    if (owner == null) throw new IllegalStateException("会话尚未绑定认证用户");
    return owner;
  }

  /**
   * 校验并获取值。
   *
   * @param value 输入值
   * @param field 字段名称
   * @return 校验后的并获取值
   */
  private static String requireValue(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value.trim();
  }

  /**
   * 定义属主。
   */
  private static final class Owner {
    private final String tenantId;
    private final String userId;

    /**
     * 创建属主实例。
     *
     * @param tenantId 租户标识
     * @param userId 用户标识
     */
    private Owner(String tenantId, String userId) {
      this.tenantId = requireValue(tenantId, "tenantId");
      this.userId = requireValue(userId, "userId");
    }

    /**
     * 判断岗位是否命中黑名单规则。
     *
     * @param state 状态
     * @return 岗位是否命中黑名单规则是否成立
     */
    private boolean matches(ChatSessionState state) {
      return state != null && tenantId.equals(state.tenantId) && userId.equals(state.userId);
    }

    /**
     * 比较字段值是否相等。
     *
     * @param other 另一比较对象
     * @return 两个对象是否相等
     */
    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Owner)) return false;
      Owner value = (Owner) other;
      return tenantId.equals(value.tenantId) && userId.equals(value.userId);
    }

    /**
     * 计算会话属主哈希值。
     *
     * @return 对象哈希值
     */
    @Override
    public int hashCode() {
      return 31 * tenantId.hashCode() + userId.hashCode();
    }
  }

  /**
   * 过滤记忆噪声事件。
   *
   * @param events 事件列表
   * @return 过滤后的记忆事件
   */
  private List<Map<String, Object>> filterMemoryNoiseEvents(List<Map<String, Object>> events) {
    List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
    if (events == null) return rows;
    for (Map<String, Object> event : events) {
      if (!isMemoryNoiseEvent(event)) rows.add(event);
    }
    return rows;
  }

  /**
   * 判断是否为记忆噪声事件。
   *
   * @param event 事件名称
   * @return 是否为记忆噪声事件
   */
  private boolean isMemoryNoiseEvent(Map<String, Object> event) {
    if (event == null) return false;
    // 仅按稳定标识字段（id/name）判定记忆读取类噪声步骤；不要匹配 title/summary 等展示文案，
    // 否则用户问题或步骤摘要里出现“记忆/memory”字样时整条推理步骤会被误删。
    StringBuilder builder = new StringBuilder();
    for (String key : new String[] {"id", "name"}) {
      Object value = event.get(key);
      if (value != null)
        builder.append(' ').append(String.valueOf(value).toLowerCase(java.util.Locale.ROOT));
    }
    String text = builder.toString();
    return text.contains("memory") || text.contains("记忆");
  }
}
