package com.jobbuddy.backend.modules.system.controller;

import com.jobbuddy.backend.common.dto.response.DeleteCountResponse;
import com.jobbuddy.backend.common.dto.response.MemoryIdResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.common.security.PermissionCodes;
import com.jobbuddy.backend.common.security.RequirePermission;
import com.jobbuddy.backend.modules.system.dto.request.SystemMemoryRequest;
import com.jobbuddy.backend.modules.system.dto.request.SystemSettingsRequest;
import com.jobbuddy.backend.modules.system.dto.response.ServiceStatusesResponse;
import com.jobbuddy.backend.modules.system.dto.response.SystemMemoryResponse;
import com.jobbuddy.backend.modules.system.dto.response.SystemSettingsResponse;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 系统设置接口，提供平台设置和长期记忆管理能力。 */
@Tag(name = "系统设置接口")
@RestController
@RequirePermission(PermissionCodes.TENANT_MANAGE)
@RequestMapping("/api/settings")
public class SystemSettingsController {
  private final SystemSettingsService systemSettingsService;

  public SystemSettingsController(SystemSettingsService systemSettingsService) {
    this.systemSettingsService = systemSettingsService;
  }

  /**
   * 查询系统设置。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "查询系统设置")
  @GetMapping
  public ApiResponse<SystemSettingsResponse> getSettings() {
    return ApiResponse.success(systemSettingsService.getSettings());
  }

  /**
   * 立即刷新服务健康状态。
   *
   * @return 最新服务状态与监测历史
   */
  @Operation(summary = "刷新服务健康状态")
  @PostMapping("/services/health/refresh")
  public ApiResponse<ServiceStatusesResponse> refreshServiceHealth() {
    return ApiResponse.success(systemSettingsService.refreshServiceStatuses());
  }

  /**
   * 保存系统设置。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "保存系统设置")
  @PutMapping
  public ApiResponse<SystemSettingsResponse> saveSettings(
      @RequestBody SystemSettingsRequest payload) {
    return ApiResponse.success(systemSettingsService.saveSettings(payload));
  }

  /**
   * 恢复运行参数默认值。
   *
   * @return 恢复后的系统设置
   */
  @Operation(summary = "恢复运行参数默认值")
  @PostMapping("/workspace/restore-defaults")
  public ApiResponse<SystemSettingsResponse> restoreWorkspaceDefaults() {
    return ApiResponse.success(systemSettingsService.restoreWorkspaceDefaults());
  }

  /**
   * 查询记忆列表。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "查询记忆列表")
  @GetMapping("/memories")
  public ApiResponse<List<SystemMemoryResponse>> listMemories(HttpServletRequest request) {
    return ApiResponse.success(
        systemSettingsService.listMemories(
            AuthenticatedUserContext.tenantId(request), AuthenticatedUserContext.userId(request)));
  }

  /**
   * 新增记忆。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "新增记忆")
  @PostMapping("/memories")
  public ApiResponse<SystemMemoryResponse> addMemory(
      @RequestBody SystemMemoryRequest payload, HttpServletRequest request) {
    return ApiResponse.success(
        systemSettingsService.addMemory(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            payload));
  }

  /**
   * 删除记忆。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "删除记忆")
  @DeleteMapping("/memories/{memoryId}")
  public ApiResponse<MemoryIdResponse> deleteMemory(
      @PathVariable String memoryId, HttpServletRequest request) {
    systemSettingsService.deleteMemory(
        AuthenticatedUserContext.tenantId(request),
        AuthenticatedUserContext.userId(request),
        memoryId);
    return ApiResponse.success(new MemoryIdResponse(memoryId));
  }

  /**
   * 清空记忆。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "清空记忆")
  @DeleteMapping("/memories")
  public ApiResponse<DeleteCountResponse> clearMemories(HttpServletRequest request) {
    int count =
        systemSettingsService.clearMemories(
            AuthenticatedUserContext.tenantId(request), AuthenticatedUserContext.userId(request));
    return ApiResponse.success(new DeleteCountResponse(count));
  }
}
