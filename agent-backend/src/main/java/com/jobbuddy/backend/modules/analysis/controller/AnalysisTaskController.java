package com.jobbuddy.backend.modules.analysis.controller;

import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.modules.analysis.dto.AnalysisTaskResponse;
import com.jobbuddy.backend.modules.analysis.service.AnalysisTaskService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

@Tag(name = "分析任务接口")
@RestController
@RequestMapping("/api/analysis-tasks")
public class AnalysisTaskController {
  private final AnalysisTaskService service;

  public AnalysisTaskController(AnalysisTaskService service) {
    this.service = service;
  }

  @Operation(summary = "查询分析任务")
  @GetMapping("/{taskId}")
  public ApiResponse<AnalysisTaskResponse> get(
      @PathVariable String taskId, HttpServletRequest request) {
    return ApiResponse.success(
        service.getOwned(
            taskId,
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request)));
  }

  @Operation(summary = "查询资源最近一次分析任务")
  @GetMapping("/latest")
  public ApiResponse<AnalysisTaskResponse> latest(
      @RequestParam String taskType, @RequestParam String resourceKey, HttpServletRequest request) {
    return ApiResponse.success(
        service.findLatest(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            taskType,
            resourceKey));
  }

  @Operation(summary = "订阅分析任务事件")
  @GetMapping(value = "/{taskId}/stream", produces = "text/event-stream")
  public SseEmitter stream(@PathVariable String taskId, HttpServletRequest request) {
    return service.stream(
        taskId,
        AuthenticatedUserContext.tenantId(request),
        AuthenticatedUserContext.userId(request));
  }
}
