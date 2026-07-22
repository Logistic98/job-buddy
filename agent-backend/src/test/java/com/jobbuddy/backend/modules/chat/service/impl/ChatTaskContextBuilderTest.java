package com.jobbuddy.backend.modules.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.modules.chat.dto.response.ChatMessageResponse;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ChatTaskContextBuilderTest {

  @Test
  void shouldCarryRecentDialogueAndAppendCurrentMessageExactlyOnce() {
    ChatSessionStore store = mock(ChatSessionStore.class);
    ChatSessionState state = state();
    List<ChatMessageResponse> stored = new ArrayList<ChatMessageResponse>();
    stored.add(message("user", "分析此岗位与当前简历的匹配度"));
    stored.add(message("assistant", "已完成当前岗位与简历的匹配分析。"));
    // 模拟异步持久化队列已经先一步写入当前用户消息。
    stored.add(message("user", "现在这个6年的简历呢"));
    when(store.listMessages("tenant-a", "user-a", "session-a")).thenReturn(stored);

    List<Map<String, Object>> messages =
        new ChatTaskContextBuilder(store).build(state, "现在这个6年的简历呢");

    assertEquals(3, messages.size());
    assertEquals("分析此岗位与当前简历的匹配度", messages.get(0).get("content"));
    assertEquals("assistant", messages.get(1).get("role"));
    assertEquals("现在这个6年的简历呢", messages.get(2).get("content"));
    assertEquals(
        1, messages.stream().filter(item -> "现在这个6年的简历呢".equals(item.get("content"))).count());
  }

  @Test
  void shouldLimitHistoryAndIgnoreUnsupportedOrBlankMessages() {
    ChatSessionStore store = mock(ChatSessionStore.class);
    ChatSessionState state = state();
    List<ChatMessageResponse> stored = new ArrayList<ChatMessageResponse>();
    stored.add(message("system", "不应进入 Runtime"));
    stored.add(message("assistant", " "));
    for (int index = 0; index < 10; index++) {
      stored.add(message(index % 2 == 0 ? "user" : "assistant", "历史-" + index));
    }
    when(store.listMessages("tenant-a", "user-a", "session-a")).thenReturn(stored);

    List<Map<String, Object>> messages = new ChatTaskContextBuilder(store).build(state, "继续分析");

    assertEquals(8, messages.size());
    assertEquals("历史-3", messages.get(0).get("content"));
    assertEquals("继续分析", messages.get(7).get("content"));
    assertTrue(messages.stream().noneMatch(item -> "system".equals(item.get("role"))));
  }

  @Test
  void shouldDegradeToCurrentMessageWhenHistoryCannotBeRead() {
    ChatSessionStore store = mock(ChatSessionStore.class);
    ChatSessionState state = state();
    when(store.listMessages("tenant-a", "user-a", "session-a"))
        .thenThrow(new IllegalStateException("db unavailable"));

    List<Map<String, Object>> messages = new ChatTaskContextBuilder(store).build(state, "现在这份简历呢");

    assertEquals(1, messages.size());
    assertEquals("user", messages.get(0).get("role"));
    assertEquals("现在这份简历呢", messages.get(0).get("content"));
  }

  private ChatSessionState state() {
    ChatSessionState state = new ChatSessionState();
    state.tenantId = "tenant-a";
    state.userId = "user-a";
    state.sessionId = "session-a";
    return state;
  }

  private ChatMessageResponse message(String role, String content) {
    ChatMessageResponse response = new ChatMessageResponse();
    response.setRole(role);
    response.setContent(content);
    return response;
  }
}
