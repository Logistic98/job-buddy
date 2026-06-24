package com.jobbuddy.backend;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;

import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = AgentBackendApplication.class, properties = {
        "spring.datasource.url=jdbc:h2:mem:agent_backend_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.flyway.enabled=false"
})
@AutoConfigureMockMvc
class InterviewPracticeControllerTest {
    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void shouldCreatePracticeFromManualQuestionIds() throws Exception {
        JsonNode first = createQuestion("{\"bankType\":\"leetcode\",\"title\":\"手动算法题\",\"category\":\"数组\",\"difficulty\":\"简单\",\"questionType\":\"编程题\",\"content\":\"实现数组求和。\",\"answer\":\"通过测试用例\",\"tags\":[\"算法\"],\"codingMeta\":{\"language\":\"javascript\",\"functionName\":\"sum\",\"template\":\"function sum(nums) { return 0 }\",\"tests\":[{\"name\":\"示例\",\"args\":[[1,2]],\"expected\":3,\"sample\":true}]}}");
        JsonNode second = createQuestion("{\"bankType\":\"baguwen\",\"title\":\"手动理论题\",\"category\":\"Java\",\"difficulty\":\"中等\",\"questionType\":\"单选\",\"content\":\"正确的是？\\n\\nA. JVM 支持 GC\\nB. JVM 不支持 GC\",\"answer\":\"A\",\"tags\":[\"Java\"]}");

        mockMvc.perform(get("/api/interview/questions/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.bankTypeOptions[0].label").value("算法题库"))
                .andExpect(jsonPath("$.data.bankTypeOptions[1].label").value("问答题库"));

        String body = "{\"title\":\"手动选题练习\",\"durationMinutes\":20,\"showAnswer\":true,\"questionIds\":[\""
                + second.get("questionId").asText() + "\",\"" + first.get("questionId").asText() + "\",\"" + second.get("questionId").asText() + "\"]}";
        mockMvc.perform(post("/api/interview/practices/random")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(2))
                .andExpect(jsonPath("$.data.durationMinutes").value(20))
                .andExpect(jsonPath("$.data.strategy.mode").value("manual"))
                .andExpect(jsonPath("$.data.strategy.showAnswer").value(true))
                .andExpect(jsonPath("$.data.questions[0].questionId").value(second.get("questionId").asText()))
                .andExpect(jsonPath("$.data.questions[1].questionId").value(first.get("questionId").asText()));
    }

    @Test
    void shouldCreateMixedTimedPracticeAndSubmitCodingResult() throws Exception {
        createQuestion("{\"bankType\":\"leetcode\",\"title\":\"Two Sum 验证题\",\"category\":\"数组与哈希表\",\"difficulty\":\"简单\",\"questionType\":\"编程题\",\"content\":\"实现 twoSum。\",\"answer\":\"通过测试用例\",\"tags\":[\"LeetCode\",\"数组\"],\"codingMeta\":{\"language\":\"javascript\",\"functionName\":\"twoSum\",\"signature\":\"function twoSum(nums, target): number[]\",\"template\":\"function twoSum(nums, target) { return [0, 1] }\",\"tests\":[{\"name\":\"示例\",\"args\":[[2,7],9],\"expected\":[0,1],\"sample\":true}]}} ");
        createQuestion("{\"bankType\":\"baguwen\",\"title\":\"HashMap 选择题\",\"category\":\"Java 基础\",\"difficulty\":\"中等\",\"questionType\":\"单选\",\"content\":\"正确的是？\\n\\nA. 扩容通常为 2 倍\\nB. 永不扩容\",\"answer\":\"A\",\"tags\":[\"Java\"]}");
        createQuestion("{\"bankType\":\"baguwen\",\"title\":\"Redis 持久化简答\",\"category\":\"Redis\",\"difficulty\":\"中等\",\"questionType\":\"简答\",\"content\":\"请简述 RDB 与 AOF 的原理与差异。\",\"answer\":\"RDB 生成内存快照恢复快；AOF 追加写命令日志数据更安全\",\"tags\":[\"Redis\"]}");

        mockMvc.perform(get("/api/interview/questions/meta"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fields").doesNotExist())
                .andExpect(jsonPath("$.data.bankTypeOptions.length()", greaterThanOrEqualTo(2)));

        mockMvc.perform(get("/api/interview/questions").param("bankType", "leetcode").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.fields").doesNotExist())
                .andExpect(jsonPath("$.data.total", greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.data.items[0].bankType").value("leetcode"));

        String practiceBody = "{\"title\":\"混合模拟练习\",\"durationMinutes\":15,\"showAnswer\":true,\"rules\":["
                + "{\"bankType\":\"leetcode\",\"questionType\":\"编程题\",\"count\":1},"
                + "{\"bankType\":\"baguwen\",\"questionType\":\"单选\",\"count\":1},"
                + "{\"bankType\":\"baguwen\",\"questionType\":\"简答\",\"count\":1}]}";
        JsonNode practice = parseData(mockMvc.perform(post("/api/interview/practices/random")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(practiceBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.totalCount").value(3))
                .andExpect(jsonPath("$.data.durationMinutes").value(15))
                .andExpect(jsonPath("$.data.strategy.showAnswer").value(true))
                .andExpect(jsonPath("$.data.remainingSeconds", greaterThan(0)))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));

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

        mockMvc.perform(post("/api/interview/practices/" + practiceId + "/submit")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"answers\":" + answers + ",\"codingResults\":" + codingResults + "}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.status").value("submitted"))
                .andExpect(jsonPath("$.data.score").value(100.0));
    }

    private JsonNode createQuestion(String body) throws Exception {
        String content = mockMvc.perform(post("/api/interview/questions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8);
        return parseData(content);
    }

    private JsonNode parseData(String content) throws Exception {
        return objectMapper.readTree(content).get("data");
    }
}
