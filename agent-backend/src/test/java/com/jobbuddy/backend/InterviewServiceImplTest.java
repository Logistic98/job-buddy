package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewBatchRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewExamSubmitRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewGenerateRequest;
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
  private final AgentIntegrationService agentIntegrationService =
      mock(AgentIntegrationService.class);
  private final InterviewServiceImpl service =
      new InterviewServiceImpl(repository, codeRunner, JSON, agentIntegrationService);

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
  void saveQuestionShouldRejectCodingQuestionWithEmptyTests() {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("title", "无测试用例算法题");
    payload.put("content", "描述解题目标");
    payload.put("bankType", "leetcode");
    Map<String, Object> codingMeta = new LinkedHashMap<String, Object>();
    codingMeta.put("language", "python");
    codingMeta.put("functionName", "solution");
    codingMeta.put("parameterCount", Integer.valueOf(1));
    codingMeta.put("template", "def solution(value):\n    return value");
    codingMeta.put("tests", Collections.emptyList());
    payload.put("codingMeta", codingMeta);
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.saveQuestion(JSON.convert(payload, InterviewQuestionRequest.class), null));

    assertEquals("codingMeta.tests 至少需要 1 条测试用例", error.getMessage());
  }

  @Test
  void generateAlgorithmQuestionsShouldReturnReviewCandidatesWithoutPersistence() {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("topic", "动态规划");
    payload.put("category", "动态规划");
    payload.put("difficulty", "困难");
    payload.put("questionType", "编程题");
    payload.put("bankType", "leetcode");
    payload.put("language", "java");
    payload.put("requirements", "覆盖状态定义和边界条件");
    payload.put("sourceUrl", "https://leetcode.com/problems/coin-change/");
    payload.put("count", Integer.valueOf(1));
    Map<String, Object> candidate =
        JSON.toMap(
            "{\"title\":\"零钱兑换变体\",\"bankType\":\"leetcode\",\"category\":\"动态规划\","
                + "\"difficulty\":\"困难\",\"questionType\":\"编程题\","
                + "\"content\":\"给定硬币集合和目标金额，返回最少硬币数。\","
                + "\"answer\":\"使用一维动态规划。\",\"tags\":[\"动态规划\"],"
                + "\"codingMeta\":{\"language\":\"java\",\"functionName\":\"coinChange\",\"parameterCount\":2,\"signature\":\"coinChange(int[],"
                + " int)\",\"template\":\"class Solution { int coinChange(int[] coins, int amount)"
                + " { return 0; } }\","
                + "\"tests\":[{\"name\":\"样例\",\"args\":[[1,2,5],11],\"expected\":3,\"sample\":true},"
                + "{\"name\":\"无解\",\"args\":[[2],3],\"expected\":-1,\"sample\":false},"
                + "{\"name\":\"零金额\",\"args\":[[1],0],\"expected\":0,\"sample\":false}]}}");
    Map<String, Object> generated = new LinkedHashMap<String, Object>();
    generated.put("items", Arrays.asList(candidate));
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", Boolean.TRUE);
    toolResult.put("data", generated);
    when(agentIntegrationService.invokeRuntimeTool(
            eq("interview_question_generate"), org.mockito.ArgumentMatchers.anyMap()))
        .thenReturn(toolResult);

    Map<String, Object> result =
        JSON.toMap(
            service.generateQuestions(JSON.convert(payload, InterviewGenerateRequest.class)));

    assertEquals(Integer.valueOf(1), result.get("count"));
    List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
    assertEquals("零钱兑换变体", items.get(0).get("title"));
    assertEquals(null, items.get(0).get("questionId"));
    Map<String, Object> codingMeta = (Map<String, Object>) items.get(0).get("codingMeta");
    assertEquals("java", codingMeta.get("language"));
    assertEquals(Integer.valueOf(3), Integer.valueOf(((List) codingMeta.get("tests")).size()));
    verify(repository, never()).saveQuestion(org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void generateQaQuestionsShouldAcceptRequirementsAsTheOnlySource() {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("category", "Java 基础");
    payload.put("difficulty", "中等");
    payload.put("questionType", "单选");
    payload.put("bankType", "qa");
    payload.put("requirements", "生成一道考察 Java 集合线程安全性的单选题");
    payload.put("count", Integer.valueOf(1));
    Map<String, Object> candidate =
        JSON.toMap(
            "{\"title\":\"HashMap 线程安全判断\",\"bankType\":\"qa\","
                + "\"category\":\"Java 基础\",\"difficulty\":\"中等\",\"questionType\":\"单选\","
                + "\"content\":\"以下关于 HashMap 的描述，正确的是哪一项？\\n\\n"
                + "A. 默认线程安全\\nB. 默认线程不安全\","
                + "\"answer\":\"B\",\"tags\":[\"Java\",\"集合\"]}");
    Map<String, Object> generated = new LinkedHashMap<String, Object>();
    generated.put("items", Arrays.asList(candidate));
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", Boolean.TRUE);
    toolResult.put("data", generated);
    when(agentIntegrationService.invokeRuntimeTool(
            eq("interview_question_generate"), org.mockito.ArgumentMatchers.anyMap()))
        .thenReturn(toolResult);

    Map<String, Object> result =
        JSON.toMap(
            service.generateQuestions(JSON.convert(payload, InterviewGenerateRequest.class)));

    assertEquals(Integer.valueOf(1), result.get("count"));
    List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
    assertEquals("单选", items.get(0).get("questionType"));
    assertEquals(null, items.get(0).get("codingMeta"));
    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);
    verify(agentIntegrationService)
        .invokeRuntimeTool(eq("interview_question_generate"), captor.capture());
    assertEquals("生成一道考察 Java 集合线程安全性的单选题", captor.getValue().get("requirements"));
    assertEquals("单选", captor.getValue().get("question_type"));
    verify(repository, never()).saveQuestion(org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void generateAlgorithmQuestionsShouldSurfaceRuntimeFailure() {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("topic", "动态规划");
    payload.put("category", "动态规划");
    payload.put("difficulty", "中等");
    payload.put("questionType", "编程题");
    payload.put("bankType", "leetcode");
    payload.put("language", "python");
    payload.put("count", Integer.valueOf(1));
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", Boolean.FALSE);
    toolResult.put("error", "模型返回内容不是完整 JSON，请重新生成");
    when(agentIntegrationService.invokeRuntimeTool(
            eq("interview_question_generate"), org.mockito.ArgumentMatchers.anyMap()))
        .thenReturn(toolResult);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.generateQuestions(JSON.convert(payload, InterviewGenerateRequest.class)));

    assertEquals("模型返回内容不是完整 JSON，请重新生成", error.getMessage());
    verify(repository, never()).saveQuestion(org.mockito.ArgumentMatchers.anyMap());
  }

  @Test
  void saveQuestionShouldRejectNonArrayCodingTests() {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("title", "测试用例类型错误");
    payload.put("content", "描述解题目标");
    payload.put("bankType", "leetcode");
    Map<String, Object> codingMeta = new LinkedHashMap<String, Object>();
    codingMeta.put("language", "python");
    codingMeta.put("functionName", "solution");
    codingMeta.put("parameterCount", Integer.valueOf(1));
    codingMeta.put("template", "def solution(value):\n    return value");
    codingMeta.put("tests", "invalid");
    payload.put("codingMeta", codingMeta);

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                service.saveQuestion(JSON.convert(payload, InterviewQuestionRequest.class), null));

    assertEquals("codingMeta.tests 必须是数组", error.getMessage());
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
