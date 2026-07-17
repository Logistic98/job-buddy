package com.jobbuddy.backend.modules.job.controller;

import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.common.security.PermissionCodes;
import com.jobbuddy.backend.common.security.RequirePermission;
import com.jobbuddy.backend.modules.analysis.dto.AnalysisTaskResponse;
import com.jobbuddy.backend.modules.analysis.service.AnalysisTaskService;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteAnalysisCommand;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteSaveCommand;
import com.jobbuddy.backend.modules.job.dto.request.BossFavoriteImportRequest;
import com.jobbuddy.backend.modules.job.dto.request.JobFavoriteRequest;
import com.jobbuddy.backend.modules.job.dto.response.BossFavoriteImportResponse;
import com.jobbuddy.backend.modules.job.dto.response.BossFavoritePreviewResponse;
import com.jobbuddy.backend.modules.job.dto.response.JobFavoriteResponse;
import com.jobbuddy.backend.modules.job.service.JobFavoriteService;
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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 岗位收藏接口，提供收藏岗位查询、保存和删除能力。 */
@Tag(name = "岗位收藏接口")
@RestController
@RequirePermission(PermissionCodes.JOBS_USE)
@RequestMapping("/api/jobs/favorites")
public class JobFavoriteController {
  private final JobFavoriteService service;
  private final AnalysisTaskService analysisTaskService;

  public JobFavoriteController(
      JobFavoriteService service, AnalysisTaskService analysisTaskService) {
    this.service = service;
    this.analysisTaskService = analysisTaskService;
  }

  /**
   * 查询收藏岗位列表。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "查询收藏岗位列表")
  @GetMapping
  public ApiResponse<List<JobFavoriteResponse>> list(HttpServletRequest request) {
    return ApiResponse.success(service.listFavorites(AuthenticatedUserContext.userId(request)));
  }

  @Operation(summary = "预览 Boss 收藏岗位")
  @GetMapping("/boss")
  public ApiResponse<BossFavoritePreviewResponse> previewBossFavorites(
      @RequestParam(value = "page", defaultValue = "1") int page,
      @RequestParam(value = "refresh", defaultValue = "false") boolean refresh,
      HttpServletRequest request) {
    if (page < 1) throw new IllegalArgumentException("页码必须大于 0");
    try {
      return ApiResponse.success(
          service.previewBossFavorites(AuthenticatedUserContext.userId(request), page, refresh));
    } catch (BossAuthRequiredException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      String message = exception.getMessage();
      if (message != null && message.startsWith("Boss 收藏列表获取失败：")) {
        return ApiResponse.error(5001, message.substring("Boss 收藏列表获取失败：".length()));
      }
      return ApiResponse.error(5001, "Boss 收藏岗位读取失败，请稍后重试。");
    }
  }

  @Operation(summary = "选择性导入 Boss 收藏岗位")
  @PostMapping("/boss/import")
  public ApiResponse<BossFavoriteImportResponse> importBossFavorites(
      @RequestBody(required = false) BossFavoriteImportRequest body, HttpServletRequest request) {
    return ApiResponse.success(
        service.importBossFavorites(AuthenticatedUserContext.userId(request), body));
  }

  /**
   * 保存收藏岗位。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "保存收藏岗位")
  @PostMapping
  public ApiResponse<List<JobFavoriteResponse>> save(
      @RequestBody JobFavoriteRequest job, HttpServletRequest request) {
    String userId = AuthenticatedUserContext.userId(request);
    service.saveFavorite(
        userId,
        job == null ? JobFavoriteSaveCommand.empty() : JobFavoriteSaveCommand.from(job.snapshot()));
    return ApiResponse.success(service.listFavorites(userId));
  }

  /**
   * 分析岗位与简历的匹配度。岗位可以是收藏岗位，也可以是会话中的推荐岗位快照； 命中收藏岗位时结果持久化，未收藏的临时岗位仅返回分析结果不落库。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "分析岗位匹配度")
  @PostMapping("/analyze")
  public ApiResponse<JobFavoriteResponse> analyzeByBody(
      @RequestBody(required = false) JobFavoriteRequest body, HttpServletRequest request) {
    String resumeId = body == null ? null : body.resumeId();
    JobFavoriteSaveCommand command =
        body == null
            ? JobFavoriteSaveCommand.empty()
            : JobFavoriteSaveCommand.from(body.snapshot());
    return ApiResponse.success(
        service.analyzeJob(AuthenticatedUserContext.userId(request), command, resumeId));
  }

  @Operation(summary = "启动收藏岗位异步分析")
  @PostMapping("/analysis-tasks")
  public ApiResponse<AnalysisTaskResponse> startAnalysisTask(
      @RequestBody(required = false) JobFavoriteRequest body, HttpServletRequest request) {
    if (body == null) throw new IllegalArgumentException("缺少岗位分析参数");
    return ApiResponse.success(
        analysisTaskService.startFavoriteJob(
            AuthenticatedUserContext.tenantId(request), AuthenticatedUserContext.userId(request),
            JobFavoriteSaveCommand.from(body.snapshot()), body.resumeId()));
  }

  /**
   * 分析收藏岗位与简历的匹配度，结果持久化并支持重新分析。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "分析收藏岗位")
  @PostMapping("/{jobKey}/analyze")
  public ApiResponse<JobFavoriteResponse> analyze(
      @PathVariable String jobKey,
      @RequestBody(required = false) JobFavoriteRequest body,
      HttpServletRequest request) {
    String resumeId = body == null ? null : body.resumeId();
    return ApiResponse.success(
        service.analyzeFavorite(
            AuthenticatedUserContext.userId(request),
            JobFavoriteAnalysisCommand.of(jobKey, resumeId)));
  }

  /**
   * 删除收藏岗位。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "删除收藏岗位")
  @DeleteMapping("/{jobKey}")
  public ApiResponse<List<JobFavoriteResponse>> delete(
      @PathVariable String jobKey, HttpServletRequest request) {
    String userId = AuthenticatedUserContext.userId(request);
    service.removeFavorite(userId, jobKey);
    return ApiResponse.success(service.listFavorites(userId));
  }
}
