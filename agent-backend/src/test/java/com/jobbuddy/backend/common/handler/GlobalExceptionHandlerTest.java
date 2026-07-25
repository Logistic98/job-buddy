package com.jobbuddy.backend.common.handler;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.job.exception.JobAnalysisException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 验证 GlobalExceptionHandler 的核心行为、异常路径与边界条件。
 */
class GlobalExceptionHandlerTest {

  /**
   * 验证 GlobalExceptionHandler 中流式响应的流式生命周期与中断边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void asyncClientDisconnectShouldNotBeConvertedToJsonForEventStream() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new DisconnectingStreamController())
            .setControllerAdvice(handler())
            .build();

    mockMvc
        .perform(post("/stream").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isOk())
        .andExpect(content().string(""));
  }

  /**
   * 验证 GlobalExceptionHandler 中流式响应的失败恢复、超时与降级边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void asyncRequestTimeoutShouldNotBeConvertedToJsonForEventStream() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new DisconnectingStreamController())
            .setControllerAdvice(handler())
            .build();

    mockMvc
        .perform(post("/stream-timeout").accept(MediaType.TEXT_EVENT_STREAM))
        .andExpect(status().isOk())
        .andExpect(content().string(""));
  }

  /**
   * 验证 GlobalExceptionHandler 中岗位的失败恢复、超时与降级边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void jobAnalysisFailureShouldReturnActionableDependencyError() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new DisconnectingStreamController())
            .setControllerAdvice(handler())
            .build();

    mockMvc
        .perform(post("/job-analysis"))
        .andExpect(status().isBadGateway())
        .andExpect(jsonPath("$.code").value(5001))
        .andExpect(jsonPath("$.message").value("岗位匹配服务执行失败，请稍后重试"));
  }

  /**
   * 验证 GlobalExceptionHandler 的输入校验与拒绝边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void missingStaticResourceShouldReturn404WithoutInternalError() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new DisconnectingStreamController())
            .setControllerAdvice(handler())
            .build();

    mockMvc
        .perform(get("/missing-resource"))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value(404));
  }

  /**
   * 验证 GlobalExceptionHandler 中认证的输入校验与拒绝边界。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void bossAuthRequiredShouldPreserveDynamicDataAsJsonObject() throws Exception {
    MockMvc mockMvc =
        MockMvcBuilders.standaloneSetup(new DisconnectingStreamController())
            .setControllerAdvice(handler())
            .build();

    mockMvc
        .perform(get("/boss-auth-required"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.code").value(4001))
        .andExpect(jsonPath("$.message").value("need login"))
        .andExpect(jsonPath("$.data.authRequired").value(true))
        .andExpect(jsonPath("$.data.login.qrSessionId").value("qr-1"));
  }

  /**
   * 验证处理器。
   *
   * @return 待测试处理器
   */
  private GlobalExceptionHandler handler() {
    return new GlobalExceptionHandler(new JsonCodec());
  }

  /**
   * 验证连接断开流式响应的行为与边界。
   */
  @RestController
  static class DisconnectingStreamController {
    /**
     * 验证流式响应。
     *
     * @return 订阅
     * @throws AsyncRequestNotUsableException 处理失败时抛出
     */
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() throws AsyncRequestNotUsableException {
      throw new AsyncRequestNotUsableException(
          "Servlet container error notification for disconnected client");
    }

    /**
     * 验证流式响应超时。
     *
     * @return 订阅超时
     * @throws AsyncRequestTimeoutException 处理失败时抛出
     */
    @PostMapping(value = "/stream-timeout", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter streamTimeout() throws AsyncRequestTimeoutException {
      throw new AsyncRequestTimeoutException();
    }

    /**
     * 验证岗位分析。
     */
    @PostMapping("/job-analysis")
    void jobAnalysis() {
      throw new JobAnalysisException("岗位匹配服务执行失败，请稍后重试");
    }

    /**
     * 验证 GlobalExceptionHandler 的输入校验与拒绝边界。
     *
     * @throws NoResourceFoundException 处理失败时抛出
     */
    @GetMapping("/missing-resource")
    void missingResource() throws NoResourceFoundException {
      throw new NoResourceFoundException(HttpMethod.GET, "missing-resource");
    }

    /**
     * 验证 GlobalExceptionHandler 中认证的输入校验与拒绝边界。
     */
    @GetMapping("/boss-auth-required")
    void bossAuthRequired() {
      Map<String, Object> login = new LinkedHashMap<String, Object>();
      login.put("qrSessionId", "qr-1");
      Map<String, Object> authData = new LinkedHashMap<String, Object>();
      authData.put("authRequired", true);
      authData.put("login", login);
      throw new BossAuthRequiredException("need login", authData);
    }
  }
}
