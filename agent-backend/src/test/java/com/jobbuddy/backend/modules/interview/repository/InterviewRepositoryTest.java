package com.jobbuddy.backend.modules.interview.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.interview.mapper.InterviewMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import javax.sql.rowset.serial.SerialClob;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InterviewRepositoryTest {
  final InterviewMapper mapper = mock(InterviewMapper.class);
  final JsonCodec json = new JsonCodec();
  final InterviewRepository repository = new InterviewRepository(mapper, json);

  @Test
  void questionReadsNormalizeClobsTagsTimeAndStripStorageFields() throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("content", new SerialClob("question".toCharArray()));
    row.put("answer", "answer");
    row.put("userAnswer", new SerialClob("response".toCharArray()));
    row.put("tagsJson", "[{\"name\":\" Java \"},{\"value\":\"{label=AI}\"},{\"label\":\" \"}]");
    row.put("codingMetaJson", "{\"language\":\"python\"}");
    row.put("createdAt", Timestamp.from(Instant.EPOCH));
    when(mapper.findQuestion("tenant", "user", "q")).thenReturn(row);
    var result = repository.findQuestion("tenant", "user", "q");
    assertEquals("question", result.get("content"));
    assertEquals("response", result.get("userAnswer"));
    assertEquals(List.of(Map.of("label", "Java"), Map.of("label", "AI")), result.get("tags"));
    assertEquals(Map.of("language", "python"), result.get("codingMeta"));
    assertEquals(Instant.EPOCH, result.get("createdAt"));
    assertFalse(result.containsKey("tagsJson"));
    assertFalse(result.containsKey("codingMetaJson"));
    when(mapper.findQuestion("tenant", "user", "missing")).thenReturn(null);
    assertNull(repository.findQuestion("tenant", "user", "missing"));
  }

  @Test
  void listPaginationAndFiltersRemainOwnerScoped() {
    repository.listQuestions(
        "tenant", "user", " JAVA ", " technical ", " core ", " hard ", 3, 1000);
    verify(mapper).listQuestions("tenant", "user", "%java%", "technical", "core", "hard", 100, 200);
    repository.listQuestions("tenant", "user", " ", null, null, 0, 0);
    repository.listQuestions("tenant", "user", null, null);
    verify(mapper).listQuestions("tenant", "user", null, null, null, null, 100, 0);
    when(mapper.countQuestions("tenant", "user", "%java%", null, "core", null)).thenReturn(4);
    assertEquals(4, repository.countQuestions("tenant", "user", "Java", "core"));
    when(mapper.findEnabled("tenant", "user", null, "core", "hard", null))
        .thenReturn(List.of(new LinkedHashMap<>(Map.of("tagsJson", "[]"))));
    assertEquals(
        List.of(), repository.findEnabled("tenant", "user", " core ", " hard ").get(0).get("tags"));
    when(mapper.listBankTypes("tenant", "user")).thenReturn(List.of("technical"));
    assertEquals(
        List.of("technical"),
        repository.questionMeta("tenant", "user", " technical ").get("bankTypes"));
    verify(mapper).listCategories("tenant", "user", "technical");
  }

  @Test
  void savesInsertOrUpdateWithExplicitOwnerAndJsonFields() {
    Map<String, Object> question =
        new LinkedHashMap<>(Map.of("questionId", "q", "tags", List.of(Map.of("label", "Java"))));
    repository.saveQuestion("tenant", "user", question);
    verify(mapper).insertQuestion(question);
    assertEquals("tenant", question.get("tenantId"));
    assertEquals("user", question.get("userId"));
    assertEquals(true, question.get("enabled"));
    assertEquals(
        List.of(Map.of("label", "Java")), json.toMapList(question.get("tagsJson").toString()));
    assertInstanceOf(Timestamp.class, question.get("updatedAt"));
    when(mapper.countQuestion("tenant", "user", "q")).thenReturn(1);
    question.put("enabled", false);
    repository.saveQuestion("tenant", "user", question);
    verify(mapper).updateQuestion(question);
    assertEquals(false, question.get("enabled"));
  }

  @Test
  void batchChangesCountRowsRatherThanFieldsAndShareTimestamp() {
    when(mapper.updateQuestionCategory(eq("tenant"), eq("user"), eq("q"), any(), any()))
        .thenReturn(1);
    when(mapper.updateQuestionDifficulty(eq("tenant"), eq("user"), eq("q"), any(), any()))
        .thenReturn(1);
    when(mapper.updateQuestionTags(eq("tenant"), eq("user"), eq("q"), any(), any())).thenReturn(1);
    assertEquals(
        1,
        repository.batchUpdateQuestions(
            "tenant",
            "user",
            List.of("q", "missing"),
            Map.of("category", "core", "difficulty", "hard", "tags", List.of())));
    when(mapper.softDeleteQuestion(eq("tenant"), eq("user"), eq("q"), any())).thenReturn(1);
    assertEquals(1, repository.batchDeleteQuestions("tenant", "user", List.of("q", "missing")));
    ArgumentCaptor<Timestamp> time = ArgumentCaptor.forClass(Timestamp.class);
    verify(mapper).softDeleteQuestion(eq("tenant"), eq("user"), eq("q"), time.capture());
    verify(mapper).softDeleteQuestion("tenant", "user", "missing", time.getValue());
    repository.deleteQuestion("tenant", "user", "single");
    verify(mapper).softDeleteQuestion(eq("tenant"), eq("user"), eq("single"), any());
  }

  @Test
  void createsExamWithMinimumExpiryAndStableQuestionOrder() {
    repository.createExam(
        "tenant",
        "user",
        "exam",
        "Practice",
        0,
        Map.of("mode", "random"),
        true,
        List.of(Map.of("questionId", "a"), Map.of("questionId", "b")));
    ArgumentCaptor<Timestamp> started = ArgumentCaptor.forClass(Timestamp.class);
    ArgumentCaptor<Timestamp> expires = ArgumentCaptor.forClass(Timestamp.class);
    verify(mapper)
        .insertExam(
            eq("tenant"),
            eq("user"),
            eq("exam"),
            eq("Practice"),
            eq("running"),
            eq(2),
            eq(0),
            isNull(),
            eq(0),
            eq("{\"mode\":\"random\"}"),
            eq(true),
            started.capture(),
            expires.capture());
    assertEquals(
        60,
        java.time.Duration.between(started.getValue().toInstant(), expires.getValue().toInstant())
            .getSeconds());
    var order = inOrder(mapper);
    order.verify(mapper).insertExamQuestion("exam", "a", 1);
    order.verify(mapper).insertExamQuestion("exam", "b", 2);
  }

  @Test
  void examHydrationIncludesQuestionsAndSubmittedExamHasNoTimeRemaining() {
    Map<String, Object> exam =
        new LinkedHashMap<>(
            Map.of(
                "status",
                "running",
                "expiresAt",
                Timestamp.from(Instant.now().plusSeconds(120)),
                "strategyJson",
                "{\"count\":2}"));
    when(mapper.findExam("tenant", "user", "exam")).thenReturn(exam);
    when(mapper.examQuestions("exam"))
        .thenReturn(List.of(new LinkedHashMap<>(Map.of("content", "Question"))));
    var result = repository.findExam("tenant", "user", "exam");
    assertTrue((Long) result.get("remainingSeconds") > 0);
    assertTrue((Long) result.get("remainingSeconds") <= 120);
    assertEquals(Map.of("count", 2), result.get("strategy"));
    assertEquals(1, ((List<?>) result.get("questions")).size());
    assertFalse(result.containsKey("strategyJson"));
    exam.put("status", "submitted");
    when(mapper.findExamForUpdate("tenant", "user", "exam")).thenReturn(exam);
    assertEquals(
        0L, repository.findExamForUpdate("tenant", "user", "exam").get("remainingSeconds"));
    when(mapper.listExams("tenant", "user")).thenReturn(List.of(exam));
    assertEquals(0L, repository.listExams("tenant", "user").get(0).get("remainingSeconds"));
    when(mapper.findExam("tenant", "user", "missing")).thenReturn(null);
    assertNull(repository.findExam("tenant", "user", "missing"));
    verify(mapper, never()).examQuestions("missing");
    repository.saveExamAnswer("exam", "q", "answer", true, 100);
    verify(mapper).saveExamAnswer("exam", "q", "answer", true, 100);
    repository.finishExam("exam", 1, 100);
    verify(mapper).finishExam(eq("exam"), eq(1), eq(100d), any());
    assertFalse(repository.deleteExam("tenant", "user", "exam"));
    when(mapper.deleteExam("tenant", "user", "exam")).thenReturn(1);
    assertTrue(repository.deleteExam("tenant", "user", "exam"));
  }
}
