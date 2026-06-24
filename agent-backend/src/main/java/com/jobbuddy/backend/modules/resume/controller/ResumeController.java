package com.jobbuddy.backend.modules.resume.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.jobbuddy.backend.common.dto.response.NamedValueResponse;
import com.jobbuddy.backend.common.dto.MapBackedDto;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeAssetUploadResponse;
import com.jobbuddy.backend.modules.resume.dto.request.ResumeProfileRequest;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeProfileResponse;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeProfileSummaryResponse;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeSummaryResponse;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
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

import javax.servlet.http.HttpServletRequest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * 简历接口，提供简历上传、解析、画像、资源、预览和下载能力。
 */
@Tag(name = "简历接口")
@RestController
@RequestMapping("/api/resume")
public class ResumeController {
    private final ResumeStorageService resumeStorageService;

    public ResumeController(ResumeStorageService resumeStorageService) {
        this.resumeStorageService = resumeStorageService;
    }

    /**
     * 查询简历列表。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "查询简历列表")
    @GetMapping
    public ApiResponse<List<ResumeSummaryResponse>> list(HttpServletRequest request) {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(MapBackedDto.fromMapList(resumeStorageService.list(userId), ResumeSummaryResponse::from));
    }

    /**
     * 查询求职画像。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "查询求职画像")
    @GetMapping("/profile")
    public ApiResponse<ResumeProfileResponse> profile(HttpServletRequest request) {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(ResumeProfileResponse.from(resumeStorageService.getJobProfileOrEmpty(userId)));
    }

    /**
     * 保存求职画像。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "保存求职画像")
    @PutMapping("/profile")
    public ApiResponse<ResumeSummaryResponse> saveProfile(HttpServletRequest request,
                                                          @RequestBody ResumeProfileRequest body) throws Exception {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(ResumeSummaryResponse.from(resumeStorageService.summarize(resumeStorageService.saveJobProfile(userId, body == null ? null : body.parsedPayload()))));
    }

    /**
     * 生成画像摘要。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "生成画像摘要")
    @PostMapping("/profile/summary")
    public ApiResponse<ResumeProfileSummaryResponse> generateProfileSummary(@RequestBody ResumeProfileRequest body,
                                                                            @RequestParam(value = "sessionId", required = false) String sessionId) {
        return ApiResponse.success(ResumeProfileSummaryResponse.from(resumeStorageService.generateJobProfileSummary(body == null ? null : body.parsedPayload(), sessionId)));
    }

    /**
     * 同步 Boss 在线简历。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "同步 Boss 在线简历")
    @PostMapping("/boss/sync")
    public ApiResponse<ResumeSummaryResponse> syncBossOnlineResume(HttpServletRequest request) throws Exception {
        String userId = AuthenticatedUserContext.userId(request);
        ResumeRecord record = resumeStorageService.syncBossOnlineResume(userId);
        return ApiResponse.success(ResumeSummaryResponse.from(resumeStorageService.summarize(record)));
    }

    /**
     * 上传并解析简历。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "上传并解析简历")
    @PostMapping("/upload")
    public ApiResponse<ResumeSummaryResponse> upload(@RequestParam("file") MultipartFile file,
                                                     HttpServletRequest request,
                                                     @RequestParam(value = "sessionId", required = false) String sessionId) throws Exception {
        String userId = AuthenticatedUserContext.userId(request);
        ResumeRecord record = resumeStorageService.upload(file, userId);
        try {
            record = resumeStorageService.parseSync(record.getResumeId(), sessionId, userId);
        } catch (RuntimeException e) {
            ResumeRecord latest = resumeStorageService.get(record.getResumeId(), userId);
            if (latest != null) record = latest;
        }
        return ApiResponse.success(ResumeSummaryResponse.from(resumeStorageService.summarize(record)));
    }

    /**
     * 上传简历资源。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "上传简历资源")
    @PostMapping("/assets/upload")
    public ApiResponse<ResumeAssetUploadResponse> uploadAsset(@RequestParam("file") MultipartFile file,
                                                              HttpServletRequest request) throws Exception {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(ResumeAssetUploadResponse.from(resumeStorageService.uploadAsset(file, userId)));
    }

    /**
     * 读取简历资源。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "读取简历资源")
    @GetMapping("/assets/{encodedObjectName}")
    public ResponseEntity<Resource> asset(@PathVariable String encodedObjectName, HttpServletRequest request) {
        String userId = AuthenticatedUserContext.userId(request);
        InputStreamResource resource = new InputStreamResource(resumeStorageService.openAsset(encodedObjectName, userId));
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(resumeStorageService.assetContentType(encodedObjectName, userId)))
                .body(resource);
    }

    /**
     * 查询简历详情。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "查询简历详情")
    @GetMapping("/{resumeId}")
    public ApiResponse<ResumeSummaryResponse> get(@PathVariable String resumeId, HttpServletRequest request) {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(ResumeSummaryResponse.from(resumeStorageService.summarize(resumeStorageService.get(resumeId, userId))));
    }

    /**
     * 分析指定简历。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "分析指定简历")
    @PostMapping("/{resumeId}/analyze")
    public ApiResponse<ResumeSummaryResponse> analyze(@PathVariable String resumeId,
                                                      HttpServletRequest request,
                                                      @RequestParam(value = "sessionId", required = false) String sessionId) {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(ResumeSummaryResponse.from(resumeStorageService.summarize(resumeStorageService.analyzeSync(resumeId, sessionId, userId))));
    }

    /**
     * 按查询参数分析简历。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "按查询参数分析简历")
    @PostMapping("/analyze")
    public ApiResponse<ResumeSummaryResponse> analyzeByQuery(@RequestParam("resumeId") String resumeId,
                                                             HttpServletRequest request,
                                                             @RequestParam(value = "sessionId", required = false) String sessionId) {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(ResumeSummaryResponse.from(resumeStorageService.summarize(resumeStorageService.analyzeSync(resumeId, sessionId, userId))));
    }

    /**
     * 更新简历解析内容。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "更新简历解析内容")
    @PutMapping("/{resumeId}/parsed")
    public ApiResponse<ResumeSummaryResponse> updateParsed(@PathVariable String resumeId,
                                                           HttpServletRequest request,
                                                           @RequestBody ResumeProfileRequest body) {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(ResumeSummaryResponse.from(resumeStorageService.summarize(resumeStorageService.updateParsed(resumeId, body == null ? null : body.parsedPayload(), userId))));
    }

    /**
     * 删除简历。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "删除简历")
    @DeleteMapping("/{resumeId}")
    public ApiResponse<NamedValueResponse> delete(@PathVariable String resumeId,
                                                  HttpServletRequest request) {
        String userId = AuthenticatedUserContext.userId(request);
        resumeStorageService.delete(resumeId, userId);
        return ApiResponse.success(new NamedValueResponse("deleted", true));
    }

    /**
     * 预览简历原文件。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "预览简历原文件")
    @GetMapping("/{resumeId}/preview")
    public ResponseEntity<Resource> preview(@PathVariable String resumeId, HttpServletRequest request) {
        ResumeRecord record = requireRecord(resumeId, AuthenticatedUserContext.userId(request));
        return fileResponse(record, false);
    }

    /**
     * 获取简历缩略图。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "获取简历缩略图")
    @GetMapping("/{resumeId}/thumbnail")
    public ResponseEntity<Resource> thumbnail(@PathVariable String resumeId, HttpServletRequest request) {
        byte[] bytes = resumeStorageService.thumbnail(resumeId, AuthenticatedUserContext.userId(request));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_PNG)
                .cacheControl(org.springframework.http.CacheControl.maxAge(7, java.util.concurrent.TimeUnit.DAYS).cachePublic())
                .body(new ByteArrayResource(bytes));
    }

    /**
     * 下载简历原文件。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "下载简历原文件")
    @GetMapping("/{resumeId}/download")
    public ResponseEntity<Resource> download(@PathVariable String resumeId, HttpServletRequest request) {
        ResumeRecord record = requireRecord(resumeId, AuthenticatedUserContext.userId(request));
        return fileResponse(record, true);
    }

    private ResumeRecord requireRecord(String resumeId, String userId) {
        ResumeRecord record = resumeStorageService.get(resumeId, userId);
        if (record == null) throw new IllegalArgumentException("简历不存在: " + resumeId);
        return record;
    }

    private ResponseEntity<Resource> fileResponse(ResumeRecord record, boolean attachment) {
        InputStreamResource resource = new InputStreamResource(resumeStorageService.openOriginalFile(record.getResumeId(), record.getUserId()));
        ContentDisposition disposition = (attachment ? ContentDisposition.attachment() : ContentDisposition.inline())
                .filename(record.getOriginalName(), StandardCharsets.UTF_8)
                .build();
        return ResponseEntity.ok()
                .contentType(mediaType(record))
                .contentLength(record.getSizeBytes())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .body(resource);
    }

    private MediaType mediaType(ResumeRecord record) {
        String suffix = record.getSuffix() == null ? "" : record.getSuffix().toLowerCase(Locale.ROOT);
        if ("pdf".equals(suffix)) return MediaType.APPLICATION_PDF;
        if ("md".equals(suffix) || "txt".equals(suffix)) return MediaType.valueOf("text/plain; charset=utf-8");
        return MediaType.APPLICATION_OCTET_STREAM;
    }
}
