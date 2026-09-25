package com.jobbuddy.backend.modules.chat.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.mapper.ChatSessionMapper;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;

class ChatSessionRepositoryTest {
  private final ChatSessionMapper mapper = mock(ChatSessionMapper.class);
  private final JsonCodec json = new JsonCodec();
  private final ChatSessionRepository repository = new ChatSessionRepository(mapper, json);

  @Test
  void stateRoundTripPreservesOwnerAndStructuredFields() {
    var state = ChatSessionRepository.newSession("tenant", "owner", "session");
    state.resumeId = "resume";
    state.lastSlots = Map.of("city", "上海");
    state.jobs = List.of(Map.of("id", "job"));
    when(mapper.upsertState(any())).thenReturn(1);
    repository.save(state);
    ArgumentCaptor<Map<String, Object>> row = ArgumentCaptor.forClass(Map.class);
    verify(mapper).upsertState(row.capture());
    when(mapper.findById("tenant", "owner", "session")).thenReturn(row.getValue());
    var loaded = repository.findById("tenant", "owner", "session");
    assertEquals("tenant", loaded.tenantId);
    assertEquals("owner", loaded.userId);
    assertEquals("resume", loaded.resumeId);
    assertEquals(state.lastSlots, loaded.lastSlots);
    assertEquals(state.jobs, loaded.jobs);
    when(mapper.upsertState(any())).thenReturn(0);
    assertThrows(IllegalArgumentException.class, () -> repository.save(state));
    when(mapper.findById("tenant", "other", "session")).thenReturn(null);
    assertNull(repository.findById("tenant", "other", "session"));
  }

  @Test
  void matchingTurnReplayIsIdempotentButPayloadChangesAreRejected() {
    when(mapper.findUserMessageByTurnId("tenant", "owner", "session", "turn"))
        .thenReturn(Map.of("content", "query", "metadataJson", "{\"a\":1}"));
    assertFalse(
        repository.appendUserMessageOnce(
            "tenant", "owner", "session", " turn ", "query", Map.of("a", 1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.appendUserMessageOnce(
                "tenant", "owner", "session", "turn", "changed", Map.of("a", 1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            repository.appendUserMessageOnce(
                "tenant", "owner", "session", "turn", "query", Map.of("a", 2)));
    assertTrue(repository.appendUserMessageOnce("tenant", "owner", "session", " ", "query"));
    verify(mapper)
        .appendMessage(
            eq("tenant"), eq("owner"), eq("session"), eq("user"), eq("query"), anyString(), any());
  }

  @ParameterizedTest
  @ValueSource(strings = {"7", "invalid"})
  void replacingJobCardsPreservesOtherMetadataAndValidatesRowId(String id) {
    when(mapper.findLatestAssistantJobMessage("tenant", "owner", "session"))
        .thenReturn(Map.of("id", id, "metadataJson", "{\"reasoning\":\"keep\"}"));
    when(mapper.updateMessageMetadata(anyString(), anyString(), anyLong(), anyString()))
        .thenReturn(1);
    boolean updated =
        repository.replaceLatestAssistantJobMessage(
            "tenant",
            "owner",
            "session",
            List.of(Map.of("id", "new")),
            List.of(Map.of("status", "success")));
    assertEquals(id.equals("7"), updated);
    if (updated) {
      ArgumentCaptor<String> encoded = ArgumentCaptor.forClass(String.class);
      verify(mapper).updateMessageMetadata(eq("tenant"), eq("owner"), eq(7L), encoded.capture());
      var metadata = json.toMap(encoded.getValue());
      assertEquals("keep", metadata.get("reasoning"));
      assertEquals(List.of(Map.of("id", "new")), metadata.get("jobCards"));
      assertEquals(List.of(Map.of("status", "success")), metadata.get("toolEvents"));
    } else verify(mapper, never()).updateMessageMetadata(any(), any(), anyLong(), any());
  }

  @Test
  void messageProjectionRestoresAttachmentsAndExecutionMetadata() {
    Map<String, Object> metadata =
        Map.of(
            "attachments",
            List.of("file"),
            "jobCards",
            List.of("job"),
            "resumeMatch",
            Map.of("score", 80),
            "toolEvents",
            List.of("tool"),
            "reasoning",
            "evidence");
    Instant timestamp = Instant.parse("2026-01-01T00:00:00Z");
    when(mapper.listMessages("tenant", "owner", "session"))
        .thenReturn(
            List.of(
                Map.of(
                    "id",
                    1,
                    "role",
                    "assistant",
                    "content",
                    "answer",
                    "metadataJson",
                    json.toJson(metadata),
                    "createdAt",
                    java.sql.Timestamp.from(timestamp))));
    var message = repository.listMessages("tenant", "owner", "session").get(0);
    assertEquals(metadata, message.get("metadata"));
    metadata.forEach((key, value) -> assertEquals(value, message.get(key)));
    assertEquals(timestamp, message.get("createdAt"));
  }

  @Test
  void sessionListingNormalizesDatesAndDefaultsTitle() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    when(mapper.listSessions("tenant", "owner"))
        .thenReturn(
            List.of(
                Map.of("sessionId", "one", "updatedAt", java.util.Date.from(now)),
                Map.of("sessionId", "two", "updatedAt", now, "firstMessage", "hello")));
    var sessions = repository.listSessions("tenant", "owner");
    assertEquals("新会话", sessions.get(0).get("title"));
    assertEquals(now, sessions.get(0).get("updatedAt"));
    assertEquals("hello", sessions.get(1).get("title"));
    repository.deleteById("tenant", "owner", "one");
    var order = inOrder(mapper);
    order.verify(mapper).deleteMessages("tenant", "owner", "one");
    order.verify(mapper).deleteState("tenant", "owner", "one");
  }
}
