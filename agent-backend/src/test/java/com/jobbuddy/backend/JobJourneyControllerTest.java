package com.jobbuddy.backend;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobbuddy.backend.common.handler.GlobalExceptionHandler;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.journey.controller.JobJourneyController;
import com.jobbuddy.backend.modules.journey.service.JobJourneyService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * 验证 JobJourneyController 的请求校验和拒绝边界。
 */
class JobJourneyControllerTest {
  private JobJourneyService service;
  private LocalValidatorFactoryBean validator;
  private MockMvc mockMvc;

  /**
   * 为每个用例创建独立的 Controller、Validator 与 Service mock。
   */
  @BeforeEach
  void setUp() {
    service = mock(JobJourneyService.class);
    validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new JobJourneyController(service))
            .setControllerAdvice(new GlobalExceptionHandler(new JsonCodec()))
            .setValidator(validator)
            .build();
  }

  /**
   * 关闭校验器持有的资源。
   */
  @AfterEach
  void tearDown() {
    validator.close();
  }

  /**
   * 创建记录时，空白企业名称应在进入 Service 前返回统一的 400 响应。
   *
   * @throws Exception 请求执行失败时抛出
   */
  @Test
  void createRecordShouldRejectBlankCompany() throws Exception {
    mockMvc
        .perform(
            post("/api/journey/records")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"company\":\"   \",\"positionName\":\"Agent 工程师\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("company: 企业名称不能为空"));

    verifyNoInteractions(service);
  }

  /**
   * 更新记录时，空白企业名称应在进入 Service 前返回统一的 400 响应。
   *
   * @throws Exception 请求执行失败时抛出
   */
  @Test
  void updateRecordShouldRejectBlankCompany() throws Exception {
    mockMvc
        .perform(
            put("/api/journey/records/journey-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"company\":\"\",\"positionName\":\"Agent 工程师\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value(400))
        .andExpect(jsonPath("$.message").value("company: 企业名称不能为空"));

    verifyNoInteractions(service);
  }
}
