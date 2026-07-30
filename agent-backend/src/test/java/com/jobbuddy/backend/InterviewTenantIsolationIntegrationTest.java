package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolArguments;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewBatchRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewExamRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewImportRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewQuestionRequest;
import com.jobbuddy.backend.modules.interview.dto.request.InterviewSmartExamRequest;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewBatchResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewExamResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewImportResponse;
import com.jobbuddy.backend.modules.interview.dto.response.InterviewQuestionResponse;
import com.jobbuddy.backend.modules.interview.service.InterviewService;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * 使用真实 MyBatis SQL 验证面试题库的租户隔离边界。
 */
@SpringBootTest(
    classes = AgentBackendApplication.class,
    properties = {
      "spring.datasource.url=jdbc:h2:mem:interview_tenant_isolation_test;MODE=MySQL;DATABASE_TO_UPPER=false;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.datasource.driver-class-name=org.h2.Driver",
      "spring.sql.init.mode=always",
      "spring.sql.init.schema-locations=classpath:/schema-test.sql",
      "spring.flyway.enabled=false",
      "job-buddy.auth.enabled=false",
      "job-buddy.service-monitor.initial-delay-ms=3600000"
    })
class InterviewTenantIsolationIntegrationTest {
  private static final String TENANT_A = "interview-tenant-a";
  private static final String TENANT_B = "interview-tenant-b";

  @Autowired private InterviewService interviewService;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private ObjectMapper objectMapper;

  @MockBean private AgentIntegrationService agentIntegrationService;

  /**
   * 清理测试租户的内存数据库记录。
   */
  @AfterEach
  void cleanTenantData() {
    jdbcTemplate.update(
        "DELETE FROM interview_exam_question WHERE exam_id IN "
            + "(SELECT exam_id FROM interview_exam WHERE tenant_id IN (?, ?))",
        TENANT_A,
        TENANT_B);
    jdbcTemplate.update("DELETE FROM interview_exam WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
    jdbcTemplate.update(
        "DELETE FROM interview_question WHERE tenant_id IN (?, ?)", TENANT_A, TENANT_B);
  }

  /**
   * 验证查询、元数据、更新、删除、批量、导入和手动选题都只作用于当前租户。
   */
  @Test
  void questionMaintenanceAndManualSelectionShouldStayWithinTenant() {
    InterviewQuestionResponse tenantAQuestion =
        interviewService.saveQuestion(
            TENANT_A, question("Tenant A question", "Tenant A category"), null);
    InterviewQuestionResponse tenantBQuestion =
        interviewService.saveQuestion(
            TENANT_B, question("Tenant B question", "Tenant B category"), null);

    assertEquals(
        Integer.valueOf(1),
        interviewService
            .pageQuestions(TENANT_A, "Tenant A question", null, null, null, 1, 20)
            .getTotal());
    assertEquals(
        Integer.valueOf(0),
        interviewService
            .pageQuestions(TENANT_B, "Tenant A question", null, null, null, 1, 20)
            .getTotal());
    assertTrue(
        interviewService
            .questionMeta(TENANT_A, null)
            .getCategories()
            .contains("Tenant A category"));
    assertFalse(
        interviewService
            .questionMeta(TENANT_A, null)
            .getCategories()
            .contains("Tenant B category"));

    IllegalArgumentException updateError =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                interviewService.saveQuestion(
                    TENANT_A,
                    question("Cross tenant update", "Cross tenant category"),
                    tenantBQuestion.getQuestionId()));
    assertEquals("题目不存在", updateError.getMessage());

    interviewService.deleteQuestion(TENANT_A, tenantBQuestion.getQuestionId());
    assertEquals(
        Integer.valueOf(1),
        interviewService
            .pageQuestions(TENANT_B, "Tenant B question", null, null, null, 1, 20)
            .getTotal());

    InterviewBatchRequest batchRequest = new InterviewBatchRequest();
    batchRequest.setAction("update");
    batchRequest.setQuestionIds(Collections.singletonList(tenantBQuestion.getQuestionId()));
    batchRequest.setCategory("Cross tenant batch");
    InterviewBatchResponse batchResponse = interviewService.batchQuestions(TENANT_A, batchRequest);
    assertEquals(Integer.valueOf(0), batchResponse.getCount());
    assertTrue(
        interviewService
            .questionMeta(TENANT_B, null)
            .getCategories()
            .contains("Tenant B category"));
    assertFalse(
        interviewService
            .questionMeta(TENANT_B, null)
            .getCategories()
            .contains("Cross tenant batch"));

    InterviewImportRequest importRequest = new InterviewImportRequest();
    importRequest.setItems(
        Collections.singletonList(question("Tenant A imported", "Tenant A imported category")));
    InterviewImportResponse importResponse =
        interviewService.importQuestions(TENANT_A, importRequest);
    assertEquals(Integer.valueOf(1), importResponse.getCount());
    assertEquals(
        TENANT_A,
        jdbcTemplate.queryForObject(
            "SELECT tenant_id FROM interview_question WHERE question_id = ?",
            String.class,
            importResponse.getItems().get(0).getQuestionId()));

    InterviewExamRequest examRequest = new InterviewExamRequest();
    examRequest.setTitle("Tenant A manual practice");
    examRequest.setRecorded(Boolean.FALSE);
    examRequest.setQuestionIds(
        Arrays.asList(tenantAQuestion.getQuestionId(), tenantBQuestion.getQuestionId()));
    InterviewExamResponse exam =
        interviewService.createRandomExam(TENANT_A, "tenant-a-user", examRequest);
    assertEquals(Integer.valueOf(1), exam.getTotalCount());
    assertEquals(tenantAQuestion.getQuestionId(), exam.getQuestions().get(0).getQuestionId());
  }

  /**
   * 验证智能组卷只向 Runtime 披露当前租户的启用题目。
   */
  @Test
  void smartSelectionShouldOnlyExposeCurrentTenantCandidates() {
    InterviewQuestionResponse tenantAQuestion =
        interviewService.saveQuestion(
            TENANT_A, question("Tenant A smart question", "Tenant A smart category"), null);
    interviewService.saveQuestion(
        TENANT_B, question("Tenant B smart question", "Tenant B smart category"), null);

    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("title", "Tenant A smart practice");
    output.put("duration_minutes", Integer.valueOf(30));
    output.put("show_answer", Boolean.FALSE);
    output.put("question_ids", Collections.singletonList(tenantAQuestion.getQuestionId()));
    output.put("selection_summary", "Only tenant A question is selected.");
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", Boolean.TRUE);
    toolResult.put("output", output);
    when(agentIntegrationService.invokeRuntimeTool(
            eq("interview_paper_compose"), any(RuntimeToolArguments.class)))
        .thenReturn(RuntimeToolResult.fromJson(objectMapper.valueToTree(toolResult)));

    InterviewSmartExamRequest request = new InterviewSmartExamRequest();
    request.setRequirements("Choose the current tenant question for a focused practice.");
    interviewService.createSmartExam(TENANT_A, "tenant-a-user", request);

    ArgumentCaptor<RuntimeToolArguments> argumentsCaptor =
        ArgumentCaptor.forClass(RuntimeToolArguments.class);
    verify(agentIntegrationService)
        .invokeRuntimeTool(eq("interview_paper_compose"), argumentsCaptor.capture());
    Map<String, Object> arguments = argumentsCaptor.getValue().toMap(new JsonCodec());
    List<Map<String, Object>> candidates = castList(arguments.get("candidates"));
    assertEquals(Integer.valueOf(1), Integer.valueOf(candidates.size()));
    assertEquals(tenantAQuestion.getQuestionId(), candidates.get(0).get("question_id"));
  }

  /**
   * 构造简答题请求。
   *
   * @param title 标题
   * @param category 分类
   * @return 题目请求
   */
  private InterviewQuestionRequest question(String title, String category) {
    InterviewQuestionRequest request = new InterviewQuestionRequest();
    request.setTitle(title);
    request.setContent(title + " content");
    request.setBankType("qa");
    request.setCategory(category);
    request.setDifficulty("中等");
    request.setQuestionType("简答");
    request.setAnswer(title + " answer");
    return request;
  }

  /**
   * 转换候选题列表。
   *
   * @param value 原始值
   * @return 候选题列表
   */
  @SuppressWarnings("unchecked")
  private List<Map<String, Object>> castList(Object value) {
    return (List<Map<String, Object>>) value;
  }
}
