package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewBatchRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewExamSubmitRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewQuestionRequest;
import com.jobbuddy.backend.modules.interview.repository.InterviewRepository;
import com.jobbuddy.backend.modules.interview.service.impl.InterviewCodeRunner;
import com.jobbuddy.backend.modules.interview.service.impl.InterviewServiceImpl;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class InterviewServiceImplTest {
  private static final JsonCodec JSON = new JsonCodec();

  private final InterviewRepository repository = mock(InterviewRepository.class);
  private final InterviewCodeRunner codeRunner = mock(InterviewCodeRunner.class);
  private final InterviewServiceImpl service = new InterviewServiceImpl(repository, codeRunner);

  @Test
  void pageQuestionsShouldClampPageAndSizeAndComputePages() {
    when(repository.countQuestions(null, null, null, null)).thenReturn(45);
    when(repository.listQuestions(null, null, null, null, 1, 100))
        .thenReturn(Collections.<Map<String, Object>>emptyList());

    Map<String, Object> result =
        JSON.toMap(
            service.pageQuestions(
                null, null, null, null, Integer.valueOf(0), Integer.valueOf(500)));

    assertEquals(Integer.valueOf(1), result.get("page"));
    assertEquals(Integer.valueOf(100), result.get("size"));
    assertEquals(Integer.valueOf(45), result.get("total"));
    assertEquals(Integer.valueOf(1), result.get("pages"));
  }

  @Test
  void pageQuestionsShouldUseDefaultsAndCeilTotalPages() {
    when(repository.countQuestions(null, null, null, null)).thenReturn(41);
    when(repository.listQuestions(null, null, null, null, 1, 20))
        .thenReturn(Collections.<Map<String, Object>>emptyList());

    Map<String, Object> result =
        JSON.toMap(service.pageQuestions(null, null, null, null, null, null));

    assertEquals(Integer.valueOf(20), result.get("size"));
    assertEquals(Integer.valueOf(3), result.get("pages"));
  }

  @Test
  void saveQuestionShouldRejectMissingTitle() {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("content", "内容");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            new org.junit.jupiter.api.function.Executable() {
              public void execute() {
                service.saveQuestion(JSON.convert(payload, InterviewQuestionRequest.class), null);
              }
            });
    assertEquals("题目标题不能为空", error.getMessage());
  }

  @Test
  void saveQuestionShouldNormalizeCodingBankTypeAndForceQuestionType() {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("title", "两数之和");
    payload.put("content", "实现 twoSum");
    payload.put("bankType", "编程题库");
    Map<String, Object> codingMeta = new LinkedHashMap<String, Object>();
    codingMeta.put("language", "python");
    codingMeta.put("functionName", "twoSum");
    codingMeta.put("parameterCount", Integer.valueOf(2));
    codingMeta.put("template", "def twoSum(nums, target):\n    pass");
    Map<String, Object> test = new LinkedHashMap<String, Object>();
    test.put("name", "示例");
    test.put("args", Arrays.<Object>asList(Arrays.asList(2, 7), Integer.valueOf(9)));
    test.put("expected", Arrays.asList(0, 1));
    codingMeta.put("tests", Arrays.asList(test));
    payload.put("codingMeta", codingMeta);
    when(repository.findQuestion(anyString())).thenReturn(new LinkedHashMap<String, Object>());

    service.saveQuestion(JSON.convert(payload, InterviewQuestionRequest.class), null);

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
    verify(repository).saveQuestion(captor.capture());
    Map<String, Object> saved = captor.getValue();
    assertEquals("leetcode", saved.get("bankType"));
    assertEquals("编程题", saved.get("questionType"));
    assertEquals("中等", saved.get("difficulty"));
    assertEquals("通用", saved.get("category"));
    assertTrue(String.valueOf(saved.get("questionId")).startsWith("iq_"));
  }

  @Test
  void saveQuestionShouldRejectCodingQuestionWithoutStructuredTests() {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("title", "缺少用例的算法题");
    payload.put("content", "实现 solution");
    payload.put("bankType", "leetcode");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.saveQuestion(JSON.convert(payload, InterviewQuestionRequest.class), null));

    assertEquals("算法题必须维护 codingMeta 字段", error.getMessage());
  }

  @Test
  void batchQuestionsShouldRejectEmptyIdsAndEmptyFields() {
    Map<String, Object> noIds = new LinkedHashMap<String, Object>();
    noIds.put("questionIds", Collections.emptyList());
    assertThrows(
        IllegalArgumentException.class,
        new org.junit.jupiter.api.function.Executable() {
          public void execute() {
            service.batchQuestions(JSON.convert(noIds, InterviewBatchRequest.class));
          }
        });

    Map<String, Object> noFields = new LinkedHashMap<String, Object>();
    noFields.put("questionIds", Arrays.asList("q1"));
    noFields.put("action", "update");
    assertThrows(
        IllegalArgumentException.class,
        new org.junit.jupiter.api.function.Executable() {
          public void execute() {
            service.batchQuestions(JSON.convert(noFields, InterviewBatchRequest.class));
          }
        });
  }

  @Test
  void batchDeleteShouldFilterBlankIdsAndReportCount() {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("questionIds", Arrays.asList("q1", "  ", null, "q2"));
    payload.put("action", "delete");

    Map<String, Object> result =
        JSON.toMap(service.batchQuestions(JSON.convert(payload, InterviewBatchRequest.class)));

    verify(repository).batchDeleteQuestions(eq(Arrays.asList("q1", "q2")));
    assertEquals(Integer.valueOf(2), result.get("count"));
    assertEquals("delete", result.get("action"));
  }

  @Test
  void submitExamShouldScoreChoiceAndCodingQuestions() {
    Map<String, Object> exam = new LinkedHashMap<String, Object>();
    List<Map<String, Object>> questions = new ArrayList<Map<String, Object>>();
    questions.add(question("q1", "单选", "A"));
    questions.add(question("q2", "编程题", null));
    exam.put("questions", questions);
    when(repository.findExamForUpdate("tenant-1", "user-1", "e1")).thenReturn(exam);
    when(repository.findExam("tenant-1", "user-1", "e1")).thenReturn(exam);

    Map<String, Object> answers = new LinkedHashMap<String, Object>();
    answers.put("q1", "A");
    answers.put("q2", "print(1)");
    Map<String, Object> codingResults = new LinkedHashMap<String, Object>();
    Map<String, Object> passed = new LinkedHashMap<String, Object>();
    passed.put("passed", Boolean.TRUE);
    codingResults.put("q2", passed);
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("answers", answers);
    payload.put("codingResults", codingResults);

    service.submitExam(
        "tenant-1", "user-1", "e1", JSON.convert(payload, InterviewExamSubmitRequest.class));

    verify(repository).saveExamAnswer(eq("e1"), eq("q1"), eq("A"), eq(true), anyDouble());
    verify(repository).saveExamAnswer(eq("e1"), eq("q2"), eq("print(1)"), eq(true), anyDouble());
    verify(repository).finishExam(eq("e1"), eq(2), eq(100.0));
  }

  @Test
  void submitExamShouldScoreZeroForWrongAndMissingCodingResult() {
    Map<String, Object> exam = new LinkedHashMap<String, Object>();
    List<Map<String, Object>> questions = new ArrayList<Map<String, Object>>();
    questions.add(question("q1", "单选", "A"));
    questions.add(question("q2", "编程题", null));
    exam.put("questions", questions);
    when(repository.findExamForUpdate("tenant-1", "user-1", "e1")).thenReturn(exam);
    when(repository.findExam("tenant-1", "user-1", "e1")).thenReturn(exam);

    Map<String, Object> answers = new LinkedHashMap<String, Object>();
    answers.put("q1", "B");
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("answers", answers);

    service.submitExam(
        "tenant-1", "user-1", "e1", JSON.convert(payload, InterviewExamSubmitRequest.class));

    verify(repository).saveExamAnswer(eq("e1"), eq("q1"), eq("B"), eq(false), anyDouble());
    verify(repository)
        .saveExamAnswer(eq("e1"), eq("q2"), eq((String) null), eq(false), anyDouble());
    verify(repository).finishExam(eq("e1"), eq(1), eq(0.0));
  }

  @Test
  void submitExamShouldPassShortAnswerWhenKeySegmentsCovered() {
    Map<String, Object> exam = new LinkedHashMap<String, Object>();
    List<Map<String, Object>> questions = new ArrayList<Map<String, Object>>();
    questions.add(question("q1", "简答", "线程安全；可见性；有序性"));
    exam.put("questions", questions);
    when(repository.findExamForUpdate("tenant-1", "user-1", "e1")).thenReturn(exam);
    when(repository.findExam("tenant-1", "user-1", "e1")).thenReturn(exam);

    Map<String, Object> answers = new LinkedHashMap<String, Object>();
    answers.put("q1", "volatile 保证可见性和有序性");
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("answers", answers);

    service.submitExam(
        "tenant-1", "user-1", "e1", JSON.convert(payload, InterviewExamSubmitRequest.class));

    verify(repository).saveExamAnswer(eq("e1"), eq("q1"), anyString(), eq(true), anyDouble());
    verify(repository).finishExam(eq("e1"), anyInt(), anyDouble());
  }

  @Test
  void getExamShouldFailFastWhenExamMissing() {
    when(repository.findExam("tenant-1", "user-1", "missing")).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        new org.junit.jupiter.api.function.Executable() {
          public void execute() {
            service.getExam("tenant-1", "user-1", "missing");
          }
        });
  }

  private Map<String, Object> question(String id, String questionType, String answer) {
    Map<String, Object> question = new LinkedHashMap<String, Object>();
    question.put("questionId", id);
    question.put("questionType", questionType);
    question.put("answer", answer);
    question.put("bankType", "编程题".equals(questionType) ? "leetcode" : "qa");
    return question;
  }
}
