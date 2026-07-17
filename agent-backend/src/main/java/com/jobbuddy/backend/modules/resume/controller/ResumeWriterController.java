package com.jobbuddy.backend.modules.resume.controller;

import com.jobbuddy.backend.common.dto.response.DeletedResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.common.security.PermissionCodes;
import com.jobbuddy.backend.common.security.RequirePermission;
import com.jobbuddy.backend.modules.resume.dto.request.ResumeWriterRestoreRequest;
import com.jobbuddy.backend.modules.resume.dto.request.ResumeWriterVersionCreateRequest;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeWriterVersionResponse;
import com.jobbuddy.backend.modules.resume.service.ResumeWriterVersionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 简历撰写接口：版本历史的查看、创建、回退与删除。 */
@Tag(name = "简历撰写接口")
@RestController
@RequirePermission(PermissionCodes.RESUME_USE)
@RequestMapping("/api/resume/writer")
public class ResumeWriterController {
  private final ResumeWriterVersionService versionService;

  public ResumeWriterController(ResumeWriterVersionService versionService) {
    this.versionService = versionService;
  }

  /**
   * 查询版本历史列表(仅元信息,不含快照内容)。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "查询撰写版本历史")
  @GetMapping("/versions")
  public ApiResponse<List<ResumeWriterVersionResponse>> listVersions(HttpServletRequest request) {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    return ApiResponse.success(versionService.list(tenantId, userId));
  }

  /**
   * 查询单个版本详情(含快照内容)。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "查询版本快照详情")
  @GetMapping("/versions/{versionId}")
  public ApiResponse<ResumeWriterVersionResponse> getVersion(
      @PathVariable String versionId, HttpServletRequest request) {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    return ApiResponse.success(versionService.get(tenantId, userId, versionId));
  }

  /**
   * 创建版本快照。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "创建版本快照")
  @PostMapping("/versions")
  public ApiResponse<ResumeWriterVersionResponse> createVersion(
      @RequestBody ResumeWriterVersionCreateRequest body, HttpServletRequest request) {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    if (body == null) throw new IllegalArgumentException("请求体不能为空");
    return ApiResponse.success(versionService.create(tenantId, userId, body));
  }

  /**
   * 回退到指定版本:先备份当前状态,再返回目标版本快照,由前端应用到撰写器。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "回退到指定版本")
  @PostMapping("/versions/{versionId}/restore")
  public ApiResponse<ResumeWriterVersionResponse> restoreVersion(
      @PathVariable String versionId,
      @RequestBody(required = false) ResumeWriterRestoreRequest body,
      HttpServletRequest request) {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    return ApiResponse.success(versionService.restore(tenantId, userId, versionId, body));
  }

  /**
   * 删除单条版本。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "删除版本快照")
  @DeleteMapping("/versions/{versionId}")
  public ApiResponse<DeletedResponse> deleteVersion(
      @PathVariable String versionId, HttpServletRequest request) {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    versionService.delete(tenantId, userId, versionId);
    return ApiResponse.success(new DeletedResponse(true));
  }
}
