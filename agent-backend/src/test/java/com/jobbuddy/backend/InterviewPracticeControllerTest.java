package com.jobbuddy.backend;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

  @Test
  void shouldExtractInterviewReferenceDocument() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "file",
            "reference.txt",
            "text/plain",
            "上海 Java 大模型应用开发岗，月薪40-50k".getBytes(StandardCharsets.UTF_8));

    mockMvc
        .perform(multipart("/api/interview/documents/extract").file(file))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(200))
        .andExpect(jsonPath("$.data.fileName").value("reference.txt"))
        .andExpect(jsonPath("$.data.text").value("上海 Java 大模型应用开发岗，月薪40-50k"))
        .andExpect(jsonPath("$.data.truncated").value(false));
  }

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
    when(agentIntegrationService.invokeRuntimeTool(eq("interview_question_generate"), anyMap()))
        .thenReturn(toolResult);

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

  private JsonNode parseData(String content) throws Exception {
    return objectMapper.readTree(content).get("data");
  }
}
