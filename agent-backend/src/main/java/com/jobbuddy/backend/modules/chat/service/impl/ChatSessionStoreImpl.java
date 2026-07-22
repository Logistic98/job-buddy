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

@Service
public class ChatSessionStoreImpl implements ChatSessionStore {

  private final ChatSessionRepository chatSessionRepository;
  private final ChatSessionCache chatSessionCache;
  private final ConcurrentMap<String, Owner> owners = new ConcurrentHashMap<String, Owner>();
  private final JsonCodec jsonCodec = new JsonCodec();

  public ChatSessionStoreImpl(
      ChatSessionRepository chatSessionRepository, ChatSessionCache chatSessionCache) {
    this.chatSessionRepository = chatSessionRepository;
    this.chatSessionCache = chatSessionCache;
  }

  @Override
  public void bindOwner(String sessionId, String tenantId, String userId) {
    String normalizedSessionId = requireValue(sessionId, "sessionId");
    Owner next = new Owner(tenantId, userId);
    Owner previous = owners.putIfAbsent(normalizedSessionId, next);
    if (previous != null && !previous.equals(next)) {
      throw new IllegalArgumentException("无权访问该会话");
    }
  }

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

  @Override
  public void save(ChatSessionState state) {
    if (state == null || state.sessionId == null) return;
    Owner owner = owner(state.sessionId);
    if (!owner.matches(state)) throw new IllegalArgumentException("会话属主不匹配");
    state.toolEvents = filterMemoryNoiseEvents(state.toolEvents);
    chatSessionRepository.save(state);
    chatSessionCache.put(state);
  }

  @Override
  public void appendMessage(String sessionId, String role, String content) {
    Owner owner = owner(sessionId);
    chatSessionRepository.appendMessage(owner.tenantId, owner.userId, sessionId, role, content);
  }

  @Override
  public void appendMessage(
      String sessionId, String role, String content, Map<String, Object> metadata) {
    Owner owner = owner(sessionId);
    chatSessionRepository.appendMessage(
        owner.tenantId, owner.userId, sessionId, role, content, metadata);
  }

  @Override
  public boolean replaceLatestAssistantJobMessage(
      String sessionId, List<Map<String, Object>> jobs, List<Map<String, Object>> toolEvents) {
    Owner owner = owner(sessionId);
    return chatSessionRepository.replaceLatestAssistantJobMessage(
        owner.tenantId, owner.userId, sessionId, jobs, filterMemoryNoiseEvents(toolEvents));
  }

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

  @Override
  public void updateResumeMatch(String sessionId, Map<String, Object> match) {
    ChatSessionState state = getOrCreate(sessionId);
    state.resumeMatch = match;
    save(state);
  }

  @Override
  public List<ChatSessionResponse> listSessions(String tenantId, String userId) {
    return jsonCodec.convertList(
        chatSessionRepository.listSessions(tenantId, userId), ChatSessionResponse.class);
  }

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

  @Override
  public void clear(String tenantId, String userId, String sessionId) {
    bindOwner(sessionId, tenantId, userId);
    chatSessionCache.evict(sessionId);
    chatSessionRepository.deleteById(tenantId, userId, sessionId);
    owners.remove(sessionId, new Owner(tenantId, userId));
  }

  private Owner owner(String sessionId) {
    String normalizedSessionId = requireValue(sessionId, "sessionId");
    Owner owner = owners.get(normalizedSessionId);
    if (owner == null) throw new IllegalStateException("会话尚未绑定认证用户");
    return owner;
  }

  private static String requireValue(String value, String field) {
    if (value == null || value.trim().isEmpty()) {
      throw new IllegalArgumentException(field + " 不能为空");
    }
    return value.trim();
  }

  private static final class Owner {
    private final String tenantId;
    private final String userId;

    private Owner(String tenantId, String userId) {
      this.tenantId = requireValue(tenantId, "tenantId");
      this.userId = requireValue(userId, "userId");
    }

    private boolean matches(ChatSessionState state) {
      return state != null && tenantId.equals(state.tenantId) && userId.equals(state.userId);
    }

    @Override
    public boolean equals(Object other) {
      if (!(other instanceof Owner)) return false;
      Owner value = (Owner) other;
      return tenantId.equals(value.tenantId) && userId.equals(value.userId);
    }

    @Override
    public int hashCode() {
      return 31 * tenantId.hashCode() + userId.hashCode();
    }
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
    for (String key : new String[] {"id", "name"}) {
      Object value = event.get(key);
      if (value != null)
        builder.append(' ').append(String.valueOf(value).toLowerCase(java.util.Locale.ROOT));
    }
    String text = builder.toString();
    return text.contains("memory") || text.contains("记忆");
  }
}
