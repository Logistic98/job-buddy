package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.cache.ChatSessionCache;
import com.jobbuddy.backend.modules.chat.dto.response.ChatMessageResponse;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.mapper.ChatSessionMapper;
import com.jobbuddy.backend.modules.chat.repository.ChatSessionRepository;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import com.jobbuddy.backend.modules.chat.service.impl.ChatSessionStoreImpl;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * 验证 ChatSessionStore 的核心行为、异常路径与边界条件。
 */
class ChatSessionStoreTest {

  /**
   * 验证首次创建的会话携带仅限当前请求使用的新建标记。
   */
  @Test
  void getOrCreateShouldMarkNewlyCreatedSession() {
    ChatSessionRepository repository = mock(ChatSessionRepository.class);
    ChatSessionCache cache = mock(ChatSessionCache.class);
    ChatSessionStore store = new ChatSessionStoreImpl(repository, cache);
    store.bindOwner("session-a", "tenant-a", "user-a");

    ChatSessionState state = store.getOrCreate("session-a");

    assertTrue(state.newlyCreated);
  }

  /**
   * 验证缓存中已有的会话不会沿用瞬时新建标记。
   */
  @Test
  void getOrCreateShouldClearNewlyCreatedMarkerForExistingSession() {
    ChatSessionRepository repository = mock(ChatSessionRepository.class);
    ChatSessionCache cache = mock(ChatSessionCache.class);
    ChatSessionState cached = ChatSessionRepository.newSession("tenant-a", "user-a", "session-a");
    cached.newlyCreated = true;
    when(cache.get("session-a")).thenReturn(cached);
    ChatSessionStore store = new ChatSessionStoreImpl(repository, cache);
    store.bindOwner("session-a", "tenant-a", "user-a");

    ChatSessionState state = store.getOrCreate("session-a");

    assertFalse(state.newlyCreated);
  }

  /**
   * 验证 ChatSessionStore 中会话的身份认证与会话边界。
   */
  @Test
  void unboundSessionMustFailClosed() {
    ChatSessionRepository repository = mock(ChatSessionRepository.class);
    ChatSessionCache cache = mock(ChatSessionCache.class);
    ChatSessionStore store = new ChatSessionStoreImpl(repository, cache);

    assertThrows(IllegalStateException.class, () -> store.get("session-a"));
    verify(repository, never()).findById(any(), any(), any());
  }

  /**
   * 验证 ChatSessionStore 中用户的权限与租户隔离边界。
   */
  @Test
  void inMemoryOwnerCannotBeReboundByAnotherUser() {
    ChatSessionRepository repository = mock(ChatSessionRepository.class);
    ChatSessionCache cache = mock(ChatSessionCache.class);
    ChatSessionStore store = new ChatSessionStoreImpl(repository, cache);
    store.bindOwner("session-a", "tenant-a", "user-a");

    assertThrows(
        IllegalArgumentException.class, () -> store.bindOwner("session-a", "tenant-a", "user-b"));
  }

  /**
   * 验证 ChatSessionStore 的权限与租户隔离边界。
   */
  @Test
  void saveRequiresStateOwnerToMatchBinding() {
    ChatSessionRepository repository = mock(ChatSessionRepository.class);
    ChatSessionCache cache = mock(ChatSessionCache.class);
    ChatSessionStore store = new ChatSessionStoreImpl(repository, cache);
    store.bindOwner("session-a", "tenant-a", "user-a");
    ChatSessionState state = ChatSessionRepository.newSession("tenant-a", "user-b", "session-a");

    assertThrows(IllegalArgumentException.class, () -> store.save(state));
    verify(repository, never()).save(any(ChatSessionState.class));
  }

  /**
   * 换一批触发登录墙后，当前轮工具事件应挂到新用户消息后的 synthetic assistant，不能污染旧岗位消息。
   */
  @Test
  void listMessagesShouldAppendSyntheticAssistantWhenLatestUserIsAfterLatestAssistant() {
    ChatSessionRepository repository = mock(ChatSessionRepository.class);
    ChatSessionCache cache = mock(ChatSessionCache.class);
    ChatSessionState state = ChatSessionRepository.newSession("tenant-a", "user-a", "session-a");
    Map<String, Object> authTool = new java.util.LinkedHashMap<String, Object>();
    authTool.put("id", "job_search");
    authTool.put("status", "error");
    authTool.put("summary", "需要登录 Boss 直聘");
    state.toolEvents = List.of(authTool);
    when(cache.get("session-a")).thenReturn(state);
    Map<String, Object> oldUser = new java.util.LinkedHashMap<String, Object>();
    oldUser.put("role", "user");
    oldUser.put("content", "筛选岗位");
    Map<String, Object> oldAssistant = new java.util.LinkedHashMap<String, Object>();
    oldAssistant.put("role", "assistant");
    oldAssistant.put("content", "");
    oldAssistant.put(
        "jobCards", List.of(Collections.<String, Object>singletonMap("securityId", "old-job")));
    oldAssistant.put(
        "toolEvents", List.of(Collections.<String, Object>singletonMap("id", "old_tool")));
    Map<String, Object> flipUser = new java.util.LinkedHashMap<String, Object>();
    flipUser.put("role", "user");
    flipUser.put("content", "换一批");
    when(repository.listMessages("tenant-a", "user-a", "session-a"))
        .thenReturn(List.of(oldUser, oldAssistant, flipUser));
    ChatSessionStore store = new ChatSessionStoreImpl(repository, cache);

    List<ChatMessageResponse> messages = store.listMessages("tenant-a", "user-a", "session-a");

    assertEquals(4, messages.size());
    ChatMessageResponse unchangedOldAssistant = messages.get(1);
    assertEquals("assistant", unchangedOldAssistant.getRole());
    assertEquals("old-job", unchangedOldAssistant.getJobCards().get(0).path("securityId").asText());
    assertEquals("old_tool", unchangedOldAssistant.getToolEvents().get(0).path("id").asText());
    ChatMessageResponse syntheticAssistant = messages.get(3);
    assertEquals("assistant", syntheticAssistant.getRole());
    assertEquals("", syntheticAssistant.getContent());
    assertTrue(
        syntheticAssistant.getJobCards() == null || syntheticAssistant.getJobCards().isNull());
    assertEquals("job_search", syntheticAssistant.getToolEvents().get(0).path("id").asText());
  }

  /**
   * 新用户消息后已经存在新助手消息时，当前轮工具事件应合并到新助手消息，旧岗位消息保持不变。
   */
  @Test
  void listMessagesShouldMergeCurrentOutputIntoLatestAssistantWhenItFollowsLatestUser() {
    ChatSessionRepository repository = mock(ChatSessionRepository.class);
    ChatSessionCache cache = mock(ChatSessionCache.class);
    ChatSessionState state = ChatSessionRepository.newSession("tenant-a", "user-a", "session-a");
    Map<String, Object> currentTool = new java.util.LinkedHashMap<String, Object>();
    currentTool.put("id", "recommendation_quality_gate");
    currentTool.put("status", "success");
    state.toolEvents = List.of(currentTool);
    when(cache.get("session-a")).thenReturn(state);
    Map<String, Object> oldUser = new java.util.LinkedHashMap<String, Object>();
    oldUser.put("role", "user");
    oldUser.put("content", "筛选岗位");
    Map<String, Object> oldAssistant = new java.util.LinkedHashMap<String, Object>();
    oldAssistant.put("role", "assistant");
    oldAssistant.put("content", "");
    oldAssistant.put(
        "jobCards", List.of(Collections.<String, Object>singletonMap("securityId", "old-job")));
    oldAssistant.put(
        "toolEvents", List.of(Collections.<String, Object>singletonMap("id", "old_tool")));
    Map<String, Object> flipUser = new java.util.LinkedHashMap<String, Object>();
    flipUser.put("role", "user");
    flipUser.put("content", "换一批");
    Map<String, Object> newAssistant = new java.util.LinkedHashMap<String, Object>();
    newAssistant.put("role", "assistant");
    newAssistant.put("content", "");
    newAssistant.put(
        "jobCards", List.of(Collections.<String, Object>singletonMap("securityId", "new-job")));
    when(repository.listMessages("tenant-a", "user-a", "session-a"))
        .thenReturn(List.of(oldUser, oldAssistant, flipUser, newAssistant));
    ChatSessionStore store = new ChatSessionStoreImpl(repository, cache);

    List<ChatMessageResponse> messages = store.listMessages("tenant-a", "user-a", "session-a");

    assertEquals(4, messages.size());
    assertEquals("old-job", messages.get(1).getJobCards().get(0).path("securityId").asText());
    assertEquals("old_tool", messages.get(1).getToolEvents().get(0).path("id").asText());
    assertEquals("new-job", messages.get(3).getJobCards().get(0).path("securityId").asText());
    assertEquals(
        "recommendation_quality_gate", messages.get(3).getToolEvents().get(0).path("id").asText());
  }

  /**
   * 验证 ChatSessionStore 的去重与幂等边界。
   */
  @Test
  void repositoryMustTreatSameTurnAndPayloadAsIdempotent() {
    ChatSessionMapper mapper = mock(ChatSessionMapper.class);
    when(mapper.appendUserMessageOnce(
            eq("tenant-a"), eq("user-a"), eq("session-a"), eq("turn-a"), eq("筛选岗位"), any(), any()))
        .thenReturn(1, 0);
    when(mapper.findUserMessageByTurnId("tenant-a", "user-a", "session-a", "turn-a"))
        .thenReturn(
            java.util.Map.of(
                "content", "筛选岗位",
                "metadataJson", "null"));
    ChatSessionRepository repository = new ChatSessionRepository(mapper, new JsonCodec());

    assertTrue(
        repository.appendUserMessageOnce("tenant-a", "user-a", "session-a", "turn-a", "筛选岗位"));
    assertFalse(
        repository.appendUserMessageOnce("tenant-a", "user-a", "session-a", "turn-a", "筛选岗位"));
  }

  /**
   * 验证 ChatSessionStore 的输入校验与拒绝边界。
   */
  @Test
  void repositoryMustRejectSameTurnWithDifferentPayload() {
    ChatSessionMapper mapper = mock(ChatSessionMapper.class);
    when(mapper.appendUserMessageOnce(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(0);
    when(mapper.findUserMessageByTurnId("tenant-a", "user-a", "session-a", "turn-a"))
        .thenReturn(
            java.util.Map.of(
                "content", "原消息",
                "metadataJson", "null"));
    ChatSessionRepository repository = new ChatSessionRepository(mapper, new JsonCodec());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.appendUserMessageOnce("tenant-a", "user-a", "session-a", "turn-a", "另一条消息"));
  }
}
