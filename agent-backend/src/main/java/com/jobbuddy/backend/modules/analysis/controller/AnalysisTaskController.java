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
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 提供分析任务接口。
 */
@Tag(name = "分析任务接口")
@RestController
@RequestMapping("/api/analysis-tasks")
public class AnalysisTaskController {
  private final AnalysisTaskService service;

  /**
   * 创建分析任务接口实例。
   *
   * @param service 服务
   */
  public AnalysisTaskController(AnalysisTaskService service) {
    this.service = service;
  }

  /**
   * 查询分析任务。
   *
   * @param taskId 任务标识
   * @param request 请求对象
   * @return 查询结果
   */
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

  /**
   * 取消分析任务。
   *
   * @param taskId 任务标识
   * @param request 请求对象
   * @return 取消结果
   */
  @Operation(summary = "取消分析任务")
  @PostMapping("/{taskId}/cancel")
  public ApiResponse<AnalysisTaskResponse> cancel(
      @PathVariable String taskId, HttpServletRequest request) {
    return ApiResponse.success(
        service.cancel(
            taskId,
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request)));
  }

  /**
   * 查询资源最近一次分析任务。
   *
   * @param taskType 任务类型
   * @param resourceKey 资源键
   * @param request 请求对象
   * @return 资源最近一次分析任务
   */
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

  /**
   * 订阅分析任务事件。
   *
   * @param taskId 任务标识
   * @param request 请求对象
   * @return SSE 事件流
   */
  @Operation(summary = "订阅分析任务事件")
  @GetMapping(value = "/{taskId}/stream", produces = "text/event-stream")
  public SseEmitter stream(@PathVariable String taskId, HttpServletRequest request) {
    return service.stream(
        taskId,
        AuthenticatedUserContext.tenantId(request),
        AuthenticatedUserContext.userId(request));
  }
}
