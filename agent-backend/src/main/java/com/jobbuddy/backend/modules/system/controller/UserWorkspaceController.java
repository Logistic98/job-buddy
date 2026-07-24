package com.jobbuddy.backend.modules.system.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.common.dto.response.StateKeyResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.modules.system.service.UserWorkspaceService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户工作区状态接口。
 *
 * <p>状态按当前登录用户和 {@code stateKey} 隔离，适合保存页面级偏好与可恢复 UI 状态，不用于持久化业务实体。
 */
@Tag(name = "用户工作区接口", description = "管理当前用户的页面偏好和可恢复工作区状态")
@RestController
@RequestMapping("/api/workspace/state")
public class UserWorkspaceController {
  private final UserWorkspaceService service;

  public UserWorkspaceController(UserWorkspaceService service) {
    this.service = service;
  }

  /**
   * 查询当前用户指定键的工作区状态。
   *
   * @param stateKey 工作区状态键
   * @return 已保存的 JSON 状态；不存在时返回空对象
   */
  @Operation(summary = "查询工作区状态", description = "按当前登录用户和状态键读取 JSON 状态；未保存时返回空 JSON 对象。")
  @GetMapping("/{stateKey}")
  public ApiResponse<JsonNode> get(
      @Parameter(description = "工作区状态键", example = "runtime.settings") @PathVariable
          String stateKey,
      HttpServletRequest request) {
    return ApiResponse.success(service.get(AuthenticatedUserContext.userId(request), stateKey));
  }

  /**
   * 保存当前用户指定键的工作区状态。
   *
   * @param stateKey 工作区状态键
   * @param payload 任意 JSON 状态；空请求体按空对象保存
   * @return 实际保存的 JSON 状态
   */
  @Operation(summary = "保存工作区状态", description = "覆盖保存当前用户指定状态键的 JSON 数据；空请求体会规范化为空 JSON 对象。")
  @PutMapping("/{stateKey}")
  public ApiResponse<JsonNode> save(
      @Parameter(description = "工作区状态键", example = "runtime.settings") @PathVariable
          String stateKey,
      @io.swagger.v3.oas.annotations.parameters.RequestBody(description = "需要保存的 JSON 状态；省略时保存空对象")
          @RequestBody(required = false)
          JsonNode payload,
      HttpServletRequest request) {
    return ApiResponse.success(
        service.save(AuthenticatedUserContext.userId(request), stateKey, payload));
  }

  /**
   * 删除当前用户指定键的工作区状态。
   *
   * @param stateKey 工作区状态键
   * @return 已删除的状态键
   */
  @Operation(summary = "删除工作区状态", description = "仅删除当前登录用户在指定状态键下保存的数据。")
  @DeleteMapping("/{stateKey}")
  public ApiResponse<StateKeyResponse> delete(
      @Parameter(description = "工作区状态键", example = "runtime.settings") @PathVariable
          String stateKey,
      HttpServletRequest request) {
    service.delete(AuthenticatedUserContext.userId(request), stateKey);
    return ApiResponse.success(new StateKeyResponse(stateKey));
  }
}
