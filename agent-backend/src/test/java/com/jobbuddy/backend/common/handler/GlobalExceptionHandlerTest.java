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

class GlobalExceptionHandlerTest {

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

  private GlobalExceptionHandler handler() {
    return new GlobalExceptionHandler(new JsonCodec());
  }

  @RestController
  static class DisconnectingStreamController {
    @PostMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() throws AsyncRequestNotUsableException {
      throw new AsyncRequestNotUsableException(
          "Servlet container error notification for disconnected client");
    }

    @PostMapping(value = "/stream-timeout", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter streamTimeout() throws AsyncRequestTimeoutException {
      throw new AsyncRequestTimeoutException();
    }

    @PostMapping("/job-analysis")
    void jobAnalysis() {
      throw new JobAnalysisException("岗位匹配服务执行失败，请稍后重试");
    }

    @GetMapping("/missing-resource")
    void missingResource() throws NoResourceFoundException {
      throw new NoResourceFoundException(HttpMethod.GET, "missing-resource");
    }

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
