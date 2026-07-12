package com.jobbuddy.backend.common.controller;

import com.jobbuddy.backend.common.dto.response.HealthResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "健康检查接口")
@RestController
@RequestMapping("/api")
public class HealthController {

  @Operation(summary = "服务健康检查")
  @GetMapping("/health")
  public ApiResponse<HealthResponse> health() {
    return ApiResponse.success(new HealthResponse("UP", "agent-backend"));
  }
}
