package com.jobbuddy.backend.common.controller;

import com.jobbuddy.backend.common.dto.response.HealthResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 提供无需认证的应用存活状态检查。 */
@Tag(name = "健康检查接口", description = "检查 agent-backend 是否可以正常响应请求")
@RestController
@RequestMapping("/api")
public class HealthController {

  /**
   * 查询应用健康状态。
   *
   * @return 当前服务名称和健康状态
   */
  @Operation(summary = "服务健康检查", description = "无需认证，返回 agent-backend 当前存活状态。")
  @SecurityRequirements
  @GetMapping("/health")
  public ApiResponse<HealthResponse> health() {
    return ApiResponse.success(new HealthResponse("UP", "agent-backend"));
  }
}
