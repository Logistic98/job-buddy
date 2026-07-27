package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolArguments;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewBatchRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewExamRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewExamSubmitRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewGenerateRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewQuestionRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewSmartExamRequest;
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

/**
 * 验证 InterviewServiceImpl 的核心行为、异常路径与边界条件。
 */
class InterviewServiceImplTest {
  private static final JsonCodec JSON = new JsonCodec();

  private final InterviewRepository repository = mock(InterviewRepository.class);
  private final InterviewCodeRunner codeRunner = mock(InterviewCodeRunner.class);
  private final AgentIntegrationService agentIntegrationService =
      mock(AgentIntegrationService.class);
  private final InterviewServiceImpl service =
      new InterviewServiceImpl(repository, codeRunner, JSON, agentIntegrationService);

  /**
   * 验证 InterviewServiceImpl 中题目的数量、长度与分页边界。
   */
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

  /**
   * 验证 InterviewServiceImpl 中题目的数量、长度与分页边界。
   */
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

  /**
   * 验证 InterviewServiceImpl 中题目的输入校验与拒绝边界。
   */
  @Test
  void saveQuestionShouldRejectMissingTitle() {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("content", "内容");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            new org.junit.jupiter.api.function.Executable() {
              /**
               * 验证执行。
               */
              public void execute() {
                service.saveQuestion(JSON.convert(payload, InterviewQuestionRequest.class), null);
              }
            });
    assertEquals("题目标题不能为空", error.getMessage());
  }

  /**
   * 验证 InterviewServiceImpl 中题目的持久化与状态变更规则。
   */
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

  /**
   * 验证 InterviewServiceImpl 中题目的输入校验与拒绝边界。
   */
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

  /**
   * 验证 InterviewServiceImpl 中题目的持久化与状态变更规则。
   */
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
            eq("interview_question_generate"),
            org.mockito.ArgumentMatchers.any(RuntimeToolArguments.class)))
        .thenReturn(runtimeToolResult(toolResult));

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

  /**
   * 验证 InterviewServiceImpl 中题目的题目生成与作答判定规则。
   */
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
            eq("interview_question_generate"),
            org.mockito.ArgumentMatchers.any(RuntimeToolArguments.class)))
        .thenReturn(runtimeToolResult(toolResult));

    Map<String, Object> result =
        JSON.toMap(
            service.generateQuestions(JSON.convert(payload, InterviewGenerateRequest.class)));

    assertEquals(Integer.valueOf(1), result.get("count"));
    List<Map<String, Object>> items = (List<Map<String, Object>>) result.get("items");
    assertEquals("单选", items.get(0).get("questionType"));
    assertEquals(null, items.get(0).get("codingMeta"));
    ArgumentCaptor<RuntimeToolArguments> captor =
        ArgumentCaptor.forClass(RuntimeToolArguments.class);
    verify(agentIntegrationService)
        .invokeRuntimeTool(eq("interview_question_generate"), captor.capture());
    Map<String, Object> capturedArguments = captor.getValue().toMap(JSON);
    assertEquals("生成一道考察 Java 集合线程安全性的单选题", capturedArguments.get("requirements"));
    assertEquals("单选", capturedArguments.get("question_type"));
    verify(repository, never()).saveQuestion(org.mockito.ArgumentMatchers.anyMap());
  }

  /**
   * 验证 InterviewServiceImpl 中题目的失败恢复、超时与降级边界。
   */
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
            eq("interview_question_generate"),
            org.mockito.ArgumentMatchers.any(RuntimeToolArguments.class)))
        .thenReturn(runtimeToolResult(toolResult));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.generateQuestions(JSON.convert(payload, InterviewGenerateRequest.class)));

    assertEquals("模型返回内容不是完整 JSON，请重新生成", error.getMessage());
    verify(repository, never()).saveQuestion(org.mockito.ArgumentMatchers.anyMap());
  }

  /**
   * 验证规则组卷支持最多一百道题，并在服务端保留完整结果。
   */
  @Test
  void createRandomExamShouldAllowOneHundredQuestionsForRules() {
    List<Map<String, Object>> pool = new ArrayList<Map<String, Object>>();
    for (int index = 1; index <= 120; index++) {
      Map<String, Object> item = question("q-" + index, "简答", "参考答案");
      item.put("title", "Agent 工程题 " + index);
      pool.add(item);
    }
    when(repository.findEnabled("qa", null, "中等", "简答")).thenReturn(pool);
    Map<String, Object> storedExam = new LinkedHashMap<String, Object>();
    storedExam.put("examId", "practice-rule-100");
    storedExam.put("title", "Agent 工程综合练习");
    storedExam.put("totalCount", Integer.valueOf(100));
    when(repository.findExam(eq("tenant-1"), eq("user-1"), anyString())).thenReturn(storedExam);

    Map<String, Object> rule = new LinkedHashMap<String, Object>();
    rule.put("bankType", "qa");
    rule.put("difficulty", "中等");
    rule.put("questionType", "简答");
    rule.put("count", Integer.valueOf(100));
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("title", "Agent 工程综合练习");
    payload.put("durationMinutes", Integer.valueOf(90));
    payload.put("showAnswer", Boolean.FALSE);
    payload.put("rules", Collections.singletonList(rule));

    service.createRandomExam(
        "tenant-1", "user-1", JSON.convert(payload, InterviewExamRequest.class));

    ArgumentCaptor<List<Map<String, Object>>> questionsCaptor =
        ArgumentCaptor.forClass((Class) List.class);
    verify(repository)
        .createExam(
            eq("tenant-1"),
            eq("user-1"),
            anyString(),
            eq("Agent 工程综合练习"),
            eq(90),
            org.mockito.ArgumentMatchers.any(),
            eq(true),
            questionsCaptor.capture());
    assertEquals(Integer.valueOf(100), Integer.valueOf(questionsCaptor.getValue().size()));
  }

  /**
   * 验证智能组卷只使用启用题目候选，并保存可回放的智能选题策略。
   */
  @Test
  void createSmartExamShouldValidateSelectionAndPersistSmartStrategy() {
    Map<String, Object> javaQuestion = smartCandidate("q-java", "Java 并发可见性", "Java 并发");
    Map<String, Object> redisQuestion = smartCandidate("q-redis", "Redis 持久化", "Redis");
    when(repository.findEnabled(null, null, null, null))
        .thenReturn(Arrays.asList(javaQuestion, redisQuestion));
    when(repository.findQuestion("q-java")).thenReturn(javaQuestion);
    when(repository.findQuestion("q-redis")).thenReturn(redisQuestion);
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", Boolean.TRUE);
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("title", "Java 与 Redis 专项练习");
    output.put("duration_minutes", Integer.valueOf(45));
    output.put("show_answer", Boolean.FALSE);
    output.put("question_ids", Arrays.asList("q-java", "q-redis"));
    output.put("selection_summary", "覆盖 Java 并发与 Redis 的两道中等难度题。");
    toolResult.put("output", output);
    when(agentIntegrationService.invokeRuntimeTool(
            eq("interview_paper_compose"),
            org.mockito.ArgumentMatchers.any(RuntimeToolArguments.class)))
        .thenReturn(runtimeToolResult(toolResult));
    Map<String, Object> storedExam = new LinkedHashMap<String, Object>();
    storedExam.put("examId", "practice-smart");
    storedExam.put("title", "Java 与 Redis 专项练习");
    storedExam.put("totalCount", Integer.valueOf(2));
    when(repository.findExam(eq("tenant-1"), eq("user-1"), anyString())).thenReturn(storedExam);

    InterviewSmartExamRequest request = new InterviewSmartExamRequest();
    request.setRequirements("选择 Java 并发和 Redis 中等难度题，45 分钟考试模式");
    Map<String, Object> result = JSON.toMap(service.createSmartExam("tenant-1", "user-1", request));

    assertEquals("Java 与 Redis 专项练习", result.get("title"));
    ArgumentCaptor<Object> strategyCaptor = ArgumentCaptor.forClass(Object.class);
    ArgumentCaptor<List<Map<String, Object>>> questionsCaptor =
        ArgumentCaptor.forClass((Class) List.class);
    verify(repository)
        .createExam(
            eq("tenant-1"),
            eq("user-1"),
            anyString(),
            eq("Java 与 Redis 专项练习"),
            eq(45),
            strategyCaptor.capture(),
            eq(true),
            questionsCaptor.capture());
    Map<String, Object> strategy = (Map<String, Object>) strategyCaptor.getValue();
    assertEquals("smart", strategy.get("mode"));
    assertEquals(request.getRequirements(), strategy.get("requirements"));
    assertEquals(Arrays.asList("q-java", "q-redis"), strategy.get("questionIds"));
    assertEquals(Integer.valueOf(2), Integer.valueOf(questionsCaptor.getValue().size()));

    ArgumentCaptor<RuntimeToolArguments> argumentsCaptor =
        ArgumentCaptor.forClass(RuntimeToolArguments.class);
    verify(agentIntegrationService)
        .invokeRuntimeTool(eq("interview_paper_compose"), argumentsCaptor.capture());
    Map<String, Object> arguments = argumentsCaptor.getValue().toMap(JSON);
    List<Map<String, Object>> candidates = (List<Map<String, Object>>) arguments.get("candidates");
    assertEquals(Integer.valueOf(2), Integer.valueOf(candidates.size()));
    assertTrue(!candidates.get(0).containsKey("answer"));
    assertTrue(!candidates.get(0).containsKey("codingMeta"));
  }

  /**
   * 验证模型返回候选集外题号时不会创建试卷。
   */
  @Test
  void createSmartExamShouldRejectQuestionOutsideCandidateCatalog() {
    Map<String, Object> javaQuestion = smartCandidate("q-java", "Java 并发可见性", "Java 并发");
    when(repository.findEnabled(null, null, null, null))
        .thenReturn(Collections.singletonList(javaQuestion));
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", Boolean.TRUE);
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("title", "非法试卷");
    output.put("duration_minutes", Integer.valueOf(30));
    output.put("show_answer", Boolean.FALSE);
    output.put("question_ids", Arrays.asList("q-java", "q-unknown"));
    output.put("selection_summary", "包含未知题号。");
    toolResult.put("output", output);
    when(agentIntegrationService.invokeRuntimeTool(
            eq("interview_paper_compose"),
            org.mockito.ArgumentMatchers.any(RuntimeToolArguments.class)))
        .thenReturn(runtimeToolResult(toolResult));
    InterviewSmartExamRequest request = new InterviewSmartExamRequest();
    request.setRequirements("选择 Java 并发题组成一套专项练习");

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.createSmartExam("tenant-1", "user-1", request));

    assertEquals("智能组卷结果包含不可用题目，请调整要求后重试", error.getMessage());
    verify(repository, never())
        .createExam(
            anyString(),
            anyString(),
            anyString(),
            anyString(),
            anyInt(),
            org.mockito.ArgumentMatchers.any(),
            anyBoolean(),
            org.mockito.ArgumentMatchers.anyList());
  }

  /**
   * 验证空题库和过短要求会在调用 Runtime 前失败。
   */
  @Test
  void createSmartExamShouldRejectInvalidRequirementAndEmptyBank() {
    InterviewSmartExamRequest shortRequest = new InterviewSmartExamRequest();
    shortRequest.setRequirements("Java");
    assertThrows(
        IllegalArgumentException.class,
        () -> service.createSmartExam("tenant-1", "user-1", shortRequest));

    InterviewSmartExamRequest request = new InterviewSmartExamRequest();
    request.setRequirements("选择 Java 并发题组成一套专项练习");
    when(repository.findEnabled(null, null, null, null))
        .thenReturn(Collections.<Map<String, Object>>emptyList());
    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.createSmartExam("tenant-1", "user-1", request));

    assertEquals("题库暂无可用题目，请先维护题库再智能组卷", error.getMessage());
    verify(agentIntegrationService, never())
        .invokeRuntimeTool(
            eq("interview_paper_compose"),
            org.mockito.ArgumentMatchers.any(RuntimeToolArguments.class));
  }

  /**
   * 验证旧版题库行内单题练习不会继续出现在练习记录中。
   */
  @Test
  void listExamsShouldHideLegacyTransientSinglePractice() {
    Map<String, Object> legacy = new LinkedHashMap<String, Object>();
    legacy.put("examId", "legacy-single");
    legacy.put("title", "旧版单题 单题练习");
    legacy.put("totalCount", Integer.valueOf(1));
    legacy.put("strategy", Collections.singletonMap("mode", "manual"));
    Map<String, Object> recorded = new LinkedHashMap<String, Object>();
    recorded.put("examId", "recorded-single");
    recorded.put("title", "手动选择练习");
    recorded.put("totalCount", Integer.valueOf(1));
    Map<String, Object> recordedStrategy = new LinkedHashMap<String, Object>();
    recordedStrategy.put("mode", "manual");
    recordedStrategy.put("recorded", Boolean.TRUE);
    recorded.put("strategy", recordedStrategy);
    when(repository.listExams("tenant-1", "user-1"))
        .thenReturn(new ArrayList<Map<String, Object>>(Arrays.asList(legacy, recorded)));

    List<?> result = service.listExams("tenant-1", "user-1");

    assertEquals(Integer.valueOf(1), Integer.valueOf(result.size()));
    assertEquals("recorded-single", JSON.toMap(result.get(0)).get("examId"));
  }

  /**
   * 验证 InterviewServiceImpl 中题目的输入校验与拒绝边界。
   */
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

  /**
   * 验证 InterviewServiceImpl 中题目的输入校验与拒绝边界。
   */
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

  /**
   * 验证 InterviewServiceImpl 中题目的输入校验与拒绝边界。
   */
  @Test
  void batchQuestionsShouldRejectEmptyIdsAndEmptyFields() {
    Map<String, Object> noIds = new LinkedHashMap<String, Object>();
    noIds.put("questionIds", Collections.emptyList());
    assertThrows(
        IllegalArgumentException.class,
        new org.junit.jupiter.api.function.Executable() {
          /**
           * 验证执行。
           */
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
          /**
           * 验证执行。
           */
          public void execute() {
            service.batchQuestions(JSON.convert(noFields, InterviewBatchRequest.class));
          }
        });
  }

  /**
   * 验证 InterviewServiceImpl 的输入校验与拒绝边界。
   */
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

  /**
   * 验证 InterviewServiceImpl 中题目的检索、筛选与排序规则。
   */
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

  /**
   * 验证 InterviewServiceImpl 中考试的输入校验与拒绝边界。
   */
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

  /**
   * 验证 InterviewServiceImpl 中考试的题目生成与作答判定规则。
   */
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

  /**
   * 验证 InterviewServiceImpl 中考试的输入校验与拒绝边界。
   */
  @Test
  void getExamShouldFailFastWhenExamMissing() {
    when(repository.findExam("tenant-1", "user-1", "missing")).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        new org.junit.jupiter.api.function.Executable() {
          /**
           * 验证执行。
           */
          public void execute() {
            service.getExam("tenant-1", "user-1", "missing");
          }
        });
  }

  /**
   * 验证运行时工具结果。
   *
   * @param value 待处理值
   * @return runtime 工具 Result
   */
  private RuntimeToolResult runtimeToolResult(Map<String, Object> value) {
    return RuntimeToolResult.fromJson(JSON.toTree(value));
  }

  /**
   * 验证题目。
   *
   * @param id 标识
   * @param questionType 题目类型
   * @param answer 答案
   * @return 测试题目
   */
  private Map<String, Object> question(String id, String questionType, String answer) {
    Map<String, Object> question = new LinkedHashMap<String, Object>();
    question.put("questionId", id);
    question.put("questionType", questionType);
    question.put("answer", answer);
    question.put("bankType", "编程题".equals(questionType) ? "leetcode" : "qa");
    return question;
  }

  /**
   * 构造智能组卷候选题。
   *
   * @param id 题目标识
   * @param title 标题
   * @param category 分类
   * @return 候选题
   */
  private Map<String, Object> smartCandidate(String id, String title, String category) {
    Map<String, Object> question = question(id, "简答", "不应发送给模型");
    question.put("title", title);
    question.put("category", category);
    question.put("difficulty", "中等");
    question.put("tags", Arrays.asList(category));
    question.put("content", title + "的完整题干");
    Map<String, Object> codingMeta = new LinkedHashMap<String, Object>();
    codingMeta.put("tests", Arrays.asList("不应发送给模型"));
    question.put("codingMeta", codingMeta);
    return question;
  }
}
