package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.jobbuddy.backend.modules.chat.cache.ChatSessionCache;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
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
}
