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

class ChatSessionStoreTest {

  @Test
  void unboundSessionMustFailClosed() {
    ChatSessionRepository repository = mock(ChatSessionRepository.class);
    ChatSessionCache cache = mock(ChatSessionCache.class);
    ChatSessionStore store = new ChatSessionStoreImpl(repository, cache);

    assertThrows(IllegalStateException.class, () -> store.get("session-a"));
    verify(repository, never()).findById(any(), any(), any());
  }

  @Test
  void inMemoryOwnerCannotBeReboundByAnotherUser() {
    ChatSessionRepository repository = mock(ChatSessionRepository.class);
    ChatSessionCache cache = mock(ChatSessionCache.class);
    ChatSessionStore store = new ChatSessionStoreImpl(repository, cache);
    store.bindOwner("session-a", "tenant-a", "user-a");

    assertThrows(
        IllegalArgumentException.class, () -> store.bindOwner("session-a", "tenant-a", "user-b"));
  }

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

  @Test
  void repositoryMustTreatSameTurnAndPayloadAsIdempotent() {
    ChatSessionMapper mapper = mock(ChatSessionMapper.class);
    when(mapper.appendUserMessageOnce(
            eq("tenant-a"), eq("user-a"), eq("session-a"), eq("turn-a"), eq("筛选岗位"), any(), any()))
        .thenReturn(1, 0);
    when(mapper.findUserMessageContentByTurnId("tenant-a", "user-a", "session-a", "turn-a"))
        .thenReturn("筛选岗位");
    ChatSessionRepository repository = new ChatSessionRepository(mapper, new JsonCodec());

    assertTrue(
        repository.appendUserMessageOnce("tenant-a", "user-a", "session-a", "turn-a", "筛选岗位"));
    assertFalse(
        repository.appendUserMessageOnce("tenant-a", "user-a", "session-a", "turn-a", "筛选岗位"));
  }

  @Test
  void repositoryMustRejectSameTurnWithDifferentPayload() {
    ChatSessionMapper mapper = mock(ChatSessionMapper.class);
    when(mapper.appendUserMessageOnce(any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(0);
    when(mapper.findUserMessageContentByTurnId("tenant-a", "user-a", "session-a", "turn-a"))
        .thenReturn("原消息");
    ChatSessionRepository repository = new ChatSessionRepository(mapper, new JsonCodec());

    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.appendUserMessageOnce("tenant-a", "user-a", "session-a", "turn-a", "另一条消息"));
  }
}
