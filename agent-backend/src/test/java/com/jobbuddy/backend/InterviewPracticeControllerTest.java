package com.jobbuddy.backend;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolArguments;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * 验证 InterviewPracticeController 的主要成功路径。
 */
@SpringBootTest(
    classes = AgentBackendApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:agent_backend_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.flyway.enabled=false",
      "job-buddy.auth.enabled=false",
      "job-buddy.service-monitor.initial-delay-ms=3600000"
    })
@AutoConfigureMockMvc
class InterviewPracticeControllerTest {
  @Autowired private MockMvc mockMvc;

  @Autowired private ObjectMapper objectMapper;

  @MockBean private AgentIntegrationService agentIntegrationService;

  /**
   * 验证 InterviewPracticeController 的主要成功路径。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldExtractInterviewReferenceDocument() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "reference.txt",
            "text/plain",
            "杭州 Java 云原生后端开发岗，月薪20-30k".getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(multipart("/api/interview/documents/extract").file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.fileName").value("reference.txt"))
        .andExpect(jsonPath("$.data.text").value("杭州 Java 云原生后端开发岗，月薪20-30k"))
        .andExpect(jsonPath("$.data.truncated").value(false));
  }

  /**
   * 验证 InterviewPracticeController 的检索、筛选与排序规则。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldGenerateReviewCandidatesWithoutSavingThem() throws Exception {
    Map<String, Object> toolResult =
        objectMapper.readValue(
            "{\"success\":true,\"data\":{\"items\":[{\"title\":\"动态规划候选题\","
                + "\"bankType\":\"leetcode\",\"category\":\"动态规划\",\"difficulty\":\"中等\","
                + "\"questionType\":\"编程题\",\"content\":\"给定数组，返回满足约束的最优值。\","
                + "\"answer\":\"使用动态规划并说明复杂度。\",\"tags\":[\"动态规划\"],"
                + "\"codingMeta\":{\"language\":\"java\",\"functionName\":\"solve\","
                + "\"parameterCount\":1,\"signature\":\"solve(int[])\","
                + "\"template\":\"class Solution { int solve(int[] values) { return 0; } }\","
                + "\"tests\":[{\"name\":\"样例\",\"args\":[[1,2]],\"expected\":3,\"sample\":true},"
                + "{\"name\":\"空数组\",\"args\":[[]],\"expected\":0,\"sample\":false},"
                + "{\"name\":\"单元素\",\"args\":[[5]],\"expected\":5,\"sample\":false}]}}]}}",
            Map.class);
    when(agentIntegrationService.invokeRuntimeTool(
            eq("interview_question_generate"), any(RuntimeToolArguments.class)))
        .thenReturn(RuntimeToolResult.fromJson(objectMapper.valueToTree(toolResult)));

    mockMvc
        .perform(
            post("/api/interview/questions/generate")
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"topic\":\"动态规划\",\"category\":\"动态规划\",\"difficulty\":\"中等\","
                        + "\"questionType\":\"编程题\",\"bankType\":\"leetcode\",\"language\":\"java\","
                        + "\"requirements\":\"覆盖状态定义和边界条件\",\"count\":1}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.count").value(1))
        .andExpect(jsonPath("$.data.items[0].bankType").value("leetcode"))
        .andExpect(jsonPath("$.data.items[0].questionType").value("编程题"))
        .andExpect(jsonPath("$.data.items[0].questionId").doesNotExist())
        .andExpect(jsonPath("$.data.items[0].codingMeta.language").value("java"))
        .andExpect(jsonPath("$.data.items[0].codingMeta.functionName").isNotEmpty())
        .andExpect(jsonPath("$.data.items[0].codingMeta.template").isNotEmpty())
        .andExpect(jsonPath("$.data.items[0].codingMeta.tests.length()", greaterThanOrEqualTo(3)));
  }

  /**
   * 验证 InterviewPracticeController 中题目的题目生成与作答判定规则。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldCreatePracticeFromManualQuestionIds() throws Exception {
    JsonNode first =
        createQuestion(
            "{\"bankType\":\"leetcode\",\"title\":\"手动算法题\",\"category\":\"数组\",\"difficulty\":\"简单\",\"questionType\":\"编程题\",\"content\":\"实现数组求和。\",\"answer\":\"通过测试用例\",\"tags\":[\"算法\"],\"codingMeta\":{\"language\":\"javascript\",\"functionName\":\"sum\",\"parameterCount\":1,\"template\":\"function"
                + " sum(nums) { return 0"
                + " }\",\"tests\":[{\"name\":\"示例\",\"args\":[[1,2]],\"expected\":3,\"sample\":true}]}}");
    JsonNode second =
        createQuestion(
            "{\"bankType\":\"qa\",\"title\":\"手动理论题\",\"category\":\"Java\",\"difficulty\":\"中等\",\"questionType\":\"单选\",\"content\":\"正确的是？\\n"
                + "\\n"
                + "A. JVM 支持 GC\\n"
                + "B. JVM 不支持 GC\",\"answer\":\"A\",\"tags\":[\"Java\"]}");

    mockMvc
        .perform(get("/api/interview/questions/meta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.bankTypeOptions[0].label").value("算法题库"))
        .andExpect(jsonPath("$.data.bankTypeOptions[1].label").value("问答题库"));

    String body =
        "{\"title\":\"手动选题练习\",\"durationMinutes\":20,\"showAnswer\":true,\"questionIds\":[\""
            + second.get("questionId").asText()
            + "\",\""
            + first.get("questionId").asText()
            + "\",\""
            + second.get("questionId").asText()
            + "\"]}";
    mockMvc
        .perform(
            post("/api/interview/practices/random")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.totalCount").value(2))
        .andExpect(jsonPath("$.data.durationMinutes").value(20))
        .andExpect(jsonPath("$.data.strategy.mode").value("manual"))
        .andExpect(jsonPath("$.data.strategy.showAnswer").value(true))
        .andExpect(
            jsonPath("$.data.questions[0].questionId").value(second.get("questionId").asText()))
        .andExpect(
            jsonPath("$.data.questions[1].questionId").value(first.get("questionId").asText()));
  }

  /**
   * 验证单题临时练习不会进入记录列表，正式练习可由所属用户删除。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldHideTransientSinglePracticeAndDeleteRecordedPractice() throws Exception {
    JsonNode question =
        createQuestion(
            "{\"bankType\":\"qa\",\"title\":\"临时单题\",\"category\":\"Java\","
                + "\"difficulty\":\"简单\",\"questionType\":\"简答\",\"content\":\"说明 final。\","
                + "\"answer\":\"不可变引用语义\",\"tags\":[\"Java\"]}");
    String questionId = question.get("questionId").asText();
    String transientBody =
        "{\"title\":\"临时单题练习\",\"durationMinutes\":30,\"showAnswer\":true,"
            + "\"recorded\":false,\"questionIds\":[\""
            + questionId
            + "\"]}";

    MvcResult transientResult =
        mockMvc
            .perform(
                post("/api/interview/practices/random")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(transientBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.recorded").value(false))
            .andReturn();
    String transientExamId =
        objectMapper
            .readTree(transientResult.getResponse().getContentAsString())
            .at("/data/examId")
            .asText();

    MvcResult hiddenListResult =
        mockMvc.perform(get("/api/interview/practices")).andExpect(status().isOk()).andReturn();
    JsonNode hiddenList =
        objectMapper.readTree(hiddenListResult.getResponse().getContentAsString()).path("data");
    assertFalse(containsExam(hiddenList, transientExamId));

    String recordedBody =
        "{\"title\":\"保留练习记录\",\"durationMinutes\":30,\"showAnswer\":false,"
            + "\"questionIds\":[\""
            + questionId
            + "\"]}";
    MvcResult recordedResult =
        mockMvc
            .perform(
                post("/api/interview/practices/random")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(recordedBody))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.recorded").value(true))
            .andReturn();
    String recordedExamId =
        objectMapper
            .readTree(recordedResult.getResponse().getContentAsString())
            .at("/data/examId")
            .asText();

    MvcResult visibleListResult =
        mockMvc.perform(get("/api/interview/practices")).andExpect(status().isOk()).andReturn();
    assertTrue(
        containsExam(
            objectMapper
                .readTree(visibleListResult.getResponse().getContentAsString())
                .path("data"),
            recordedExamId));

    mockMvc
        .perform(delete("/api/interview/practices/{examId}", recordedExamId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.data.deleted").value(true));
    MvcResult deletedListResult =
        mockMvc.perform(get("/api/interview/practices")).andExpect(status().isOk()).andReturn();
    assertFalse(
        containsExam(
            objectMapper
                .readTree(deletedListResult.getResponse().getContentAsString())
                .path("data"),
            recordedExamId));
  }

  /**
   * 验证自然语言智能组卷会从现有题库选择题目并直接创建练习。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldCreateSmartPracticeFromExistingQuestionCatalog() throws Exception {
    JsonNode javaQuestion =
        createQuestion(
            "{\"bankType\":\"qa\",\"title\":\"Java 并发可见性\",\"category\":\"Java 并发\","
                + "\"difficulty\":\"中等\",\"questionType\":\"简答\",\"content\":\"说明 volatile 的语义。\","
                + "\"answer\":\"可见性与有序性\",\"tags\":[\"Java\",\"并发\"]}");
    JsonNode redisQuestion =
        createQuestion(
            "{\"bankType\":\"qa\",\"title\":\"Redis 持久化\",\"category\":\"Redis\","
                + "\"difficulty\":\"中等\",\"questionType\":\"单选\",\"content\":\"RDB 与 AOF 哪项描述正确？\\n\\n"
                + "A. RDB 是快照\\nB. RDB 是追加日志\",\"answer\":\"A\",\"tags\":[\"Redis\"]}");
    String javaId = javaQuestion.get("questionId").asText();
    String redisId = redisQuestion.get("questionId").asText();
    Map<String, Object> toolResult =
        objectMapper.readValue(
            "{\"success\":true,\"output\":{\"title\":\"Java 与 Redis 专项练习\","
                + "\"duration_minutes\":45,\"show_answer\":false,\"question_ids\":[\""
                + javaId
                + "\",\""
                + redisId
                + "\"],\"selection_summary\":\"覆盖 Java 并发与 Redis。\"}}",
            Map.class);
    when(agentIntegrationService.invokeRuntimeTool(
            eq("interview_paper_compose"), any(RuntimeToolArguments.class)))
        .thenReturn(RuntimeToolResult.fromJson(objectMapper.valueToTree(toolResult)));

    mockMvc
        .perform(
            post("/api/interview/practices/smart")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"requirements\":\"选择 Java 并发和 Redis 中等难度题，45 分钟考试模式\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.title").value("Java 与 Redis 专项练习"))
        .andExpect(jsonPath("$.data.totalCount").value(2))
        .andExpect(jsonPath("$.data.durationMinutes").value(45))
        .andExpect(jsonPath("$.data.strategy.mode").value("smart"))
        .andExpect(
            jsonPath("$.data.strategy.requirements").value("选择 Java 并发和 Redis 中等难度题，45 分钟考试模式"))
        .andExpect(jsonPath("$.data.strategy.showAnswer").value(false))
        .andExpect(jsonPath("$.data.questions[0].questionId").value(javaId))
        .andExpect(jsonPath("$.data.questions[1].questionId").value(redisId));
  }

  /**
   * 验证 InterviewPracticeController 中编程题的题目生成与作答判定规则。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void shouldCreateMixedTimedPracticeAndSubmitCodingResult() throws Exception {
    createQuestion(
        "{\"bankType\":\"leetcode\",\"title\":\"Two Sum"
            + " 验证题\",\"category\":\"数组与哈希表\",\"difficulty\":\"简单\",\"questionType\":\"编程题\",\"content\":\"实现"
            + " twoSum。\",\"answer\":\"通过测试用例\",\"tags\":[\"LeetCode\",\"数组\"],\"codingMeta\":{\"language\":\"javascript\",\"functionName\":\"twoSum\",\"parameterCount\":2,\"signature\":\"function"
            + " twoSum(nums, target): number[]\",\"template\":\"function twoSum(nums, target) {"
            + " return [0, 1]"
            + " }\",\"tests\":[{\"name\":\"示例\",\"args\":[[2,7],9],\"expected\":[0,1],\"sample\":true}]}}"
            + " ");
    createQuestion(
        "{\"bankType\":\"qa\",\"title\":\"HashMap 选择题\",\"category\":\"Java"
            + " 基础\",\"difficulty\":\"中等\",\"questionType\":\"单选\",\"content\":\"正确的是？\\n"
            + "\\n"
            + "A. 扩容通常为 2 倍\\n"
            + "B. 永不扩容\",\"answer\":\"A\",\"tags\":[\"Java\"]}");
    createQuestion(
        "{\"bankType\":\"qa\",\"title\":\"Redis"
            + " 持久化简答\",\"category\":\"Redis\",\"difficulty\":\"中等\",\"questionType\":\"简答\",\"content\":\"请简述"
            + " RDB 与 AOF 的原理与差异。\",\"answer\":\"RDB 生成内存快照恢复快；AOF"
            + " 追加写命令日志数据更安全\",\"tags\":[\"Redis\"]}");

    mockMvc
        .perform(get("/api/interview/questions/meta"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.fields").doesNotExist())
        .andExpect(jsonPath("$.data.bankTypeOptions.length()", greaterThanOrEqualTo(2)));

    mockMvc
        .perform(get("/api/interview/questions").param("bankType", "leetcode").param("size", "10"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.fields").doesNotExist())
        .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
        .andExpect(jsonPath("$.data.items[0].bankType").value("leetcode"));

    String practiceBody =
        "{\"title\":\"混合模拟练习\",\"durationMinutes\":15,\"showAnswer\":true,\"rules\":["
            + "{\"bankType\":\"leetcode\",\"questionType\":\"编程题\",\"count\":1},"
            + "{\"bankType\":\"qa\",\"questionType\":\"单选\",\"count\":1},"
            + "{\"bankType\":\"qa\",\"questionType\":\"简答\",\"count\":1}]}";
    JsonNode practice =
        parseData(
            mockMvc
                .perform(
                    post("/api/interview/practices/random")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(practiceBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.durationMinutes").value(15))
                .andExpect(jsonPath("$.data.strategy.showAnswer").value(true))
                .andExpect(jsonPath("$.data.remainingSeconds", greaterThan(0)))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8));

    String practiceId = practice.get("examId").asText();
    StringBuilder answers = new StringBuilder("{");
    StringBuilder codingResults = new StringBuilder("{");
    Iterator<JsonNode> questions = practice.get("questions").elements();
    boolean firstAnswer = true;
    boolean firstCoding = true;
    while (questions.hasNext()) {
      JsonNode question = questions.next();
      String questionId = question.get("questionId").asText();
      String bankType = question.get("bankType").asText();
      if (!firstAnswer) answers.append(',');
      answers.append('"').append(questionId).append("\":");
      if ("leetcode".equals(bankType)) {
        answers.append("\"function twoSum(nums, target) { return [0, 1] }\"");
        if (!firstCoding) codingResults.append(',');
        codingResults.append('"').append(questionId).append("\":true");
        firstCoding = false;
      } else if ("简答".equals(question.get("questionType").asText())) {
        answers.append("\"RDB 生成内存快照恢复快，AOF 追加写命令日志数据更安全\"");
      } else {
        answers.append("\"A\"");
      }
      firstAnswer = false;
    }
    answers.append('}');
    codingResults.append('}');

    mockMvc
        .perform(
            post("/api/interview/practices/" + practiceId + "/submit")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"answers\":" + answers + ",\"codingResults\":" + codingResults + "}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.status").value("submitted"))
        .andExpect(jsonPath("$.data.score").value(100.0));
  }

  /**
   * 验证创建题目。
   *
   * @param body 请求体
   * @return 题目
   * @throws Exception 处理失败时抛出
   */
  private JsonNode createQuestion(String body) throws Exception {
    String content =
        mockMvc
            .perform(
                post("/api/interview/questions")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(body))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value(200))
            .andReturn()
            .getResponse()
            .getContentAsString(StandardCharsets.UTF_8);
    return parseData(content);
  }

  /**
   * 判断练习列表是否包含指定记录。
   *
   * @param exams 练习列表
   * @param examId 练习标识
   * @return 是否包含
   */
  private boolean containsExam(JsonNode exams, String examId) {
    if (exams == null || !exams.isArray()) return false;
    for (JsonNode exam : exams) {
      if (examId.equals(exam.path("examId").asText())) return true;
    }
    return false;
  }

  /**
   * 解析 JSON 测试数据。
   *
   * @param content 内容
   * @return 数据
   * @throws Exception 处理失败时抛出
   */
  private JsonNode parseData(String content) throws Exception {
    return objectMapper.readTree(content).get("data");
  }
}
