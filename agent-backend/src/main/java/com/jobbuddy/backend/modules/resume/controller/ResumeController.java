package com.jobbuddy.backend.modules.resume.controller;

import com.jobbuddy.backend.common.dto.response.DeletedResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.common.security.PermissionCodes;
import com.jobbuddy.backend.common.security.RequirePermission;
import com.jobbuddy.backend.modules.analysis.dto.AnalysisTaskResponse;
import com.jobbuddy.backend.modules.analysis.dto.ResumeAnalysisTaskRequest;
import com.jobbuddy.backend.modules.analysis.service.AnalysisTaskService;
import com.jobbuddy.backend.modules.resume.dto.request.ResumeProfileRequest;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeAssetUploadResponse;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeProfileSummaryResponse;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeSummaryResponse;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 简历接口，提供简历上传、解析、画像、资源、预览和下载能力。
 */
@Tag(name = "简历接口")
@RestController
@RequirePermission(PermissionCodes.RESUME_USE)
@RequestMapping("/api/resume")
public class ResumeController {
  private final ResumeStorageService resumeStorageService;
  private final AnalysisTaskService analysisTaskService;

  /**
   * 创建简历接口实例。
   *
   * @param resumeStorageService 简历存储服务
   * @param analysisTaskService 分析任务服务
   */
  public ResumeController(
      ResumeStorageService resumeStorageService, AnalysisTaskService analysisTaskService) {
    this.resumeStorageService = resumeStorageService;
    this.analysisTaskService = analysisTaskService;
  }

  /**
   * 查询简历列表。
   *
   * @param request 请求对象
   * @return 统一接口响应
   */
  @Operation(summary = "查询简历列表")
  @GetMapping
  public ApiResponse<List<ResumeSummaryResponse>> list(HttpServletRequest request) {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    return ApiResponse.success(resumeStorageService.list(tenantId, userId));
  }

  /**
   * 查询求职画像。
   *
   * @param request 请求对象
   * @return 统一接口响应
   */
  @Operation(summary = "查询求职画像")
  @GetMapping("/profile")
  public ApiResponse<ResumeSummaryResponse> profile(HttpServletRequest request) {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    return ApiResponse.success(resumeStorageService.getJobProfileOrEmpty(tenantId, userId));
  }

  /**
   * 保存求职画像。
   *
   * @param request 请求对象
   * @param body 请求体
   * @return 统一接口响应
   * @throws Exception 执行失败时抛出
   */
  @Operation(summary = "保存求职画像")
  @PutMapping("/profile")
  public ApiResponse<ResumeSummaryResponse> saveProfile(
      HttpServletRequest request, @RequestBody ResumeProfileRequest body) throws Exception {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    return ApiResponse.success(
        resumeStorageService.summarize(
            resumeStorageService.saveJobProfile(
                tenantId, userId, body == null ? null : body.parsedPayload())));
  }

  /**
   * 生成画像摘要。
   *
   * @param body 请求体
   * @param sessionId 会话标识
   * @return 统一接口响应
   */
  @Operation(summary = "生成画像摘要")
  @PostMapping("/profile/summary")
  public ApiResponse<ResumeProfileSummaryResponse> generateProfileSummary(
      @RequestBody ResumeProfileRequest body,
      @RequestParam(value = "sessionId", required = false) String sessionId) {
    return ApiResponse.success(
        resumeStorageService.generateJobProfileSummary(
            body == null ? null : body.parsedPayload(), sessionId));
  }

  /**
   * 同步 Boss 在线简历。
   *
   * @param request 请求对象
   * @return 统一接口响应
   * @throws Exception 执行失败时抛出
   */
  @Operation(summary = "同步 Boss 在线简历")
  @PostMapping("/boss/sync")
  public ApiResponse<ResumeSummaryResponse> syncBossOnlineResume(HttpServletRequest request)
      throws Exception {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    ResumeRecord record = resumeStorageService.syncBossOnlineResume(tenantId, userId);
    return ApiResponse.success(resumeStorageService.summarize(record));
  }

  /**
   * 上传简历文件。
   *
   * @param file 上传文件
   * @param request 请求对象
   * @param originalNameEncoded 编码后的原始文件名
   * @param sessionId 会话标识
   * @return 统一接口响应
   * @throws Exception 执行失败时抛出
   */
  @Operation(summary = "上传简历文件")
  @PostMapping("/upload")
  public ApiResponse<ResumeSummaryResponse> upload(
      @RequestParam("file") MultipartFile file,
      HttpServletRequest request,
      @RequestParam(value = "originalNameEncoded", required = false) String originalNameEncoded,
      @RequestParam(value = "sessionId", required = false) String sessionId)
      throws Exception {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    String originalName =
        originalNameEncoded == null
            ? null
            : URLDecoder.decode(originalNameEncoded, StandardCharsets.UTF_8);
    ResumeRecord record = resumeStorageService.upload(file, originalName, tenantId, userId);
    return ApiResponse.success(resumeStorageService.summarize(record));
  }

  /**
   * 上传简历资源。
   *
   * @param file 上传文件
   * @param request 请求对象
   * @return 统一接口响应
   * @throws Exception 执行失败时抛出
   */
  @Operation(summary = "上传简历资源")
  @PostMapping("/assets/upload")
  public ApiResponse<ResumeAssetUploadResponse> uploadAsset(
      @RequestParam("file") MultipartFile file, HttpServletRequest request) throws Exception {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    return ApiResponse.success(resumeStorageService.uploadAsset(file, tenantId, userId));
  }

  /**
   * 读取简历资源。
   *
   * @param encodedObjectName 编码后的对象名
   * @param request 请求对象
   * @return 统一接口响应
   */
  @Operation(summary = "读取简历资源")
  @GetMapping("/assets/{encodedObjectName}")
  public ResponseEntity<Resource> asset(
      @PathVariable String encodedObjectName, HttpServletRequest request) {
    String userId = AuthenticatedUserContext.userId(request);
    InputStreamResource resource =
        new InputStreamResource(resumeStorageService.openAsset(encodedObjectName, userId));
    return ResponseEntity.ok()
        .contentType(
            MediaType.parseMediaType(
                resumeStorageService.assetContentType(encodedObjectName, userId)))
        .body(resource);
  }

  /**
   * 查询简历详情。
   *
   * @param resumeId 简历标识
   * @param request 请求对象
   * @return 统一接口响应
   */
  @Operation(summary = "查询简历详情")
  @GetMapping("/{resumeId}")
  public ApiResponse<ResumeSummaryResponse> get(
      @PathVariable String resumeId, HttpServletRequest request) {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    return ApiResponse.success(
        resumeStorageService.summarize(resumeStorageService.get(resumeId, tenantId, userId)));
  }

  /**
   * 分析指定简历。
   *
   * @param resumeId 简历标识
   * @param request 请求对象
   * @param sessionId 会话标识
   * @return 统一接口响应
   */
  @Operation(summary = "分析指定简历")
  @PostMapping("/{resumeId}/analyze")
  public ApiResponse<ResumeSummaryResponse> analyze(
      @PathVariable String resumeId,
      HttpServletRequest request,
      @RequestParam(value = "sessionId", required = false) String sessionId) {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    return ApiResponse.success(
        resumeStorageService.summarize(
            resumeStorageService.analyzeSync(resumeId, sessionId, tenantId, userId)));
  }

  /**
   * 启动简历异步分析。
   *
   * @param body 请求体
   * @param request 请求对象
   * @return 启动后的分析任务
   */
  @Operation(summary = "启动简历异步分析")
  @PostMapping("/analysis-tasks")
  public ApiResponse<AnalysisTaskResponse> startAnalysisTask(
      @RequestBody ResumeAnalysisTaskRequest body, HttpServletRequest request) {
    if (body == null) throw new IllegalArgumentException("缺少简历分析参数");
    return ApiResponse.success(
        analysisTaskService.startResume(
            AuthenticatedUserContext.tenantId(request), AuthenticatedUserContext.userId(request),
            body.getResumeId(), body.getSessionId()));
  }

  /**
   * 更新简历解析内容。
   *
   * @param resumeId 简历标识
   * @param request 请求对象
   * @param body 请求体
   * @return 统一接口响应
   */
  @Operation(summary = "更新简历解析内容")
  @PutMapping("/{resumeId}/parsed")
  public ApiResponse<ResumeSummaryResponse> updateParsed(
      @PathVariable String resumeId,
      HttpServletRequest request,
      @RequestBody ResumeProfileRequest body) {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    return ApiResponse.success(
        resumeStorageService.summarize(
            resumeStorageService.updateParsed(
                resumeId, body == null ? null : body.parsedPayload(), tenantId, userId)));
  }

  /**
   * 删除简历。
   *
   * @param resumeId 简历标识
   * @param request 请求对象
   * @return 统一接口响应
   */
  @Operation(summary = "删除简历")
  @DeleteMapping("/{resumeId}")
  public ApiResponse<DeletedResponse> delete(
      @PathVariable String resumeId, HttpServletRequest request) {
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    analysisTaskService.cancelActiveResource(
        tenantId, userId, AnalysisTaskService.TYPE_RESUME, resumeId);
    resumeStorageService.delete(resumeId, tenantId, userId);
    return ApiResponse.success(new DeletedResponse(true));
  }

  /**
   * 预览简历原文件。
   *
   * @param resumeId 简历标识
   * @param request 请求对象
   * @return 统一接口响应
   */
  @Operation(summary = "预览简历原文件")
  @GetMapping("/{resumeId}/preview")
  public ResponseEntity<Resource> preview(
      @PathVariable String resumeId, HttpServletRequest request) {
    ResumeRecord record =
        requireRecord(
            resumeId,
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request));
    return fileResponse(record, false);
  }

  /**
   * 获取简历缩略图。
   *
   * @param resumeId 简历标识
   * @param request 请求对象
   * @return 统一接口响应
   */
  @Operation(summary = "获取简历缩略图")
  @GetMapping("/{resumeId}/thumbnail")
  public ResponseEntity<Resource> thumbnail(
      @PathVariable String resumeId, HttpServletRequest request) {
    byte[] bytes =
        resumeStorageService.thumbnail(
            resumeId,
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request));
    return ResponseEntity.ok()
        .contentType(MediaType.IMAGE_PNG)
        .cacheControl(
            org.springframework.http.CacheControl.maxAge(7, java.util.concurrent.TimeUnit.DAYS)
                .cachePublic())
        .body(new ByteArrayResource(bytes));
  }

  /**
   * 下载简历原文件。
   *
   * @param resumeId 简历标识
   * @param request 请求对象
   * @return 统一接口响应
   */
  @Operation(summary = "下载简历原文件")
  @GetMapping("/{resumeId}/download")
  public ResponseEntity<Resource> download(
      @PathVariable String resumeId, HttpServletRequest request) {
    ResumeRecord record =
        requireRecord(
            resumeId,
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request));
    return fileResponse(record, true);
  }

  /**
   * 校验并获取记录。
   *
   * @param resumeId 简历标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 校验后的并获取记录
   */
  private ResumeRecord requireRecord(String resumeId, String tenantId, String userId) {
    ResumeRecord record = resumeStorageService.get(resumeId, tenantId, userId);
    if (record == null) throw new IllegalArgumentException("简历不存在: " + resumeId);
    return record;
  }

  /**
   * 获取文件响应。
   *
   * @param record 记录
   * @param attachment 响应附件头
   * @return 文件响应
   */
  private ResponseEntity<Resource> fileResponse(ResumeRecord record, boolean attachment) {
    InputStreamResource resource =
        new InputStreamResource(
            resumeStorageService.openOriginalFile(
                record.getResumeId(), record.getTenantId(), record.getUserId()));
    ContentDisposition disposition =
        (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
            .filename(record.getOriginalName(), StandardCharsets.UTF_8)
            .build();
    return ResponseEntity.ok()
        .contentType(mediaType(record))
        .contentLength(record.getSizeBytes())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .body(resource);
  }

  /**
   * 解析媒体类型。
   *
   * @param record 记录
   * @return 媒体类型
   */
  private MediaType mediaType(ResumeRecord record) {
    String suffix = record.getSuffix() == null ? "" : record.getSuffix().toLowerCase(Locale.ROOT);
    if ("pdf".equals(suffix)) return MediaType.APPLICATION_PDF;
    if ("md".equals(suffix) || "txt".equals(suffix))
      return MediaType.valueOf("text/plain; charset=utf-8");
    return MediaType.APPLICATION_OCTET_STREAM;
  }
}
