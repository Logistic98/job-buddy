package com.jobbuddy.backend;

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
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.mapper.ChatSessionMapper;
import com.jobbuddy.backend.modules.chat.repository.ChatSessionRepository;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import com.jobbuddy.backend.modules.chat.service.impl.ChatSessionStoreImpl;
import org.junit.jupiter.api.Test;

/**
 * 验证 ChatSessionStore 的核心行为、异常路径与边界条件。
 */
class ChatSessionStoreTest {

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
