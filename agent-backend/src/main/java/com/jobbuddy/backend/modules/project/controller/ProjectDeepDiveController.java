package com.jobbuddy.backend.modules.project.controller;

import com.jobbuddy.backend.common.dto.response.MaterialIdResponse;
import com.jobbuddy.backend.common.dto.response.ProjectIdResponse;
import com.jobbuddy.backend.common.dto.response.QuestionIdResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.common.security.PermissionCodes;
import com.jobbuddy.backend.common.security.RequirePermission;
import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionGenerateRequest;
import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionRequest;
import com.jobbuddy.backend.modules.project.dto.request.ProjectRequest;
import com.jobbuddy.backend.modules.project.dto.response.ProjectResponse;
import com.jobbuddy.backend.modules.project.service.ProjectDeepDiveService;
import com.jobbuddy.backend.modules.project.service.ProjectMaterialFile;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
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
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;

/** 项目深挖接口，提供项目、项目材料和项目面试题生成能力。 */
@Tag(name = "项目深挖接口")
@RestController
@RequirePermission(PermissionCodes.PROJECT_USE)
@RequestMapping("/api/project-deep-dive")
public class ProjectDeepDiveController {
  private final ProjectDeepDiveService service;

  public ProjectDeepDiveController(ProjectDeepDiveService service) {
    this.service = service;
  }

  /**
   * 查询项目列表。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "查询项目列表")
  @GetMapping("/projects")
  public ApiResponse<List<ProjectResponse>> projects(HttpServletRequest request) {
    return ApiResponse.success(
        service.listProjects(
            AuthenticatedUserContext.tenantId(request), AuthenticatedUserContext.userId(request)));
  }

  /**
   * 查询项目完整详情，材料与问题仅在进入项目后按需加载。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "查询项目详情")
  @GetMapping("/projects/{projectId}")
  public ApiResponse<ProjectResponse> project(
      @PathVariable String projectId, HttpServletRequest request) {
    return ApiResponse.success(
        service.getProject(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            projectId));
  }

  /**
   * 创建项目。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "创建项目")
  @PostMapping("/projects")
  public ApiResponse<ProjectResponse> createProject(
      @RequestBody ProjectRequest payload, HttpServletRequest request) {
    return ApiResponse.success(
        service.saveProject(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            payload,
            null));
  }

  /**
   * 更新项目。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "更新项目")
  @PutMapping("/projects/{projectId}")
  public ApiResponse<ProjectResponse> updateProject(
      @PathVariable String projectId,
      @RequestBody ProjectRequest payload,
      HttpServletRequest request) {
    return ApiResponse.success(
        service.saveProject(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            payload,
            projectId));
  }

  /**
   * 删除项目。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "删除项目")
  @DeleteMapping("/projects/{projectId}")
  public ApiResponse<ProjectIdResponse> deleteProject(
      @PathVariable String projectId, HttpServletRequest request) {
    service.deleteProject(
        AuthenticatedUserContext.tenantId(request),
        AuthenticatedUserContext.userId(request),
        projectId);
    return ApiResponse.success(new ProjectIdResponse(projectId));
  }

  /**
   * 新增项目材料。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "上传项目文件")
  @PostMapping(
      value = "/projects/{projectId}/materials",
      consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public ApiResponse<ProjectResponse> addMaterial(
      @PathVariable String projectId,
      @RequestParam("file") MultipartFile file,
      HttpServletRequest request)
      throws Exception {
    return ApiResponse.success(
        service.addMaterial(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            projectId,
            file));
  }

  /** 下载单个项目文件。 */
  @Operation(summary = "下载项目文件")
  @GetMapping("/materials/{materialId}/file")
  public ResponseEntity<Resource> materialFile(
      @PathVariable String materialId, HttpServletRequest request) {
    ProjectMaterialFile file =
        service.openMaterial(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            materialId);
    ContentDisposition disposition =
        ContentDisposition.attachment().filename(file.fileName(), StandardCharsets.UTF_8).build();
    return ResponseEntity.ok()
        .contentType(mediaType(file.contentType()))
        .contentLength(file.sizeBytes())
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(new InputStreamResource(file.inputStream()));
  }

  /** 将选中的项目文件流式打包为 ZIP 下载。 */
  @Operation(summary = "批量下载项目文件")
  @GetMapping(value = "/materials/batch-file", produces = "application/zip")
  public ResponseEntity<StreamingResponseBody> batchMaterialFiles(
      @RequestParam("materialIds") List<String> materialIds, HttpServletRequest request) {
    List<String> uniqueMaterialIds = normalizeMaterialIds(materialIds);
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    StreamingResponseBody body =
        outputStream -> {
          Set<String> usedEntryNames = new LinkedHashSet<String>();
          try (ZipOutputStream zip = new ZipOutputStream(outputStream, StandardCharsets.UTF_8)) {
            for (String materialId : uniqueMaterialIds) {
              ProjectMaterialFile file = service.openMaterial(tenantId, userId, materialId);
              String entryName = uniqueZipEntryName(file.fileName(), usedEntryNames);
              zip.putNextEntry(new ZipEntry(entryName));
              try (InputStream input = file.inputStream()) {
                input.transferTo(zip);
              }
              zip.closeEntry();
            }
          }
        };
    ContentDisposition disposition =
        ContentDisposition.attachment()
            .filename("project-materials.zip", StandardCharsets.UTF_8)
            .build();
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("application/zip"))
        .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
        .header("X-Content-Type-Options", "nosniff")
        .body(body);
  }

  /**
   * 删除项目材料。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "删除项目材料")
  @DeleteMapping("/materials/{materialId}")
  public ApiResponse<MaterialIdResponse> deleteMaterial(
      @PathVariable String materialId, HttpServletRequest request) {
    service.deleteMaterial(
        AuthenticatedUserContext.tenantId(request),
        AuthenticatedUserContext.userId(request),
        materialId);
    return ApiResponse.success(new MaterialIdResponse(materialId));
  }

  /**
   * 手动新增项目问题。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "新增项目问题")
  @PostMapping("/projects/{projectId}/questions")
  public ApiResponse<ProjectResponse> addQuestion(
      @PathVariable String projectId,
      @RequestBody ProjectQuestionRequest payload,
      HttpServletRequest request) {
    return ApiResponse.success(
        service.addQuestion(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            projectId,
            payload));
  }

  /**
   * 编辑项目问题，编辑后的问题在重新生成时保留。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "编辑项目问题")
  @PutMapping("/questions/{questionId}")
  public ApiResponse<ProjectResponse> updateQuestion(
      @PathVariable String questionId,
      @RequestBody ProjectQuestionRequest payload,
      HttpServletRequest request) {
    return ApiResponse.success(
        service.updateQuestion(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            questionId,
            payload));
  }

  /**
   * 删除项目问题。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "删除项目问题")
  @DeleteMapping("/questions/{questionId}")
  public ApiResponse<QuestionIdResponse> deleteQuestion(
      @PathVariable String questionId, HttpServletRequest request) {
    service.deleteQuestion(
        AuthenticatedUserContext.tenantId(request),
        AuthenticatedUserContext.userId(request),
        questionId);
    return ApiResponse.success(new QuestionIdResponse(questionId));
  }

  /**
   * 生成项目面试题。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "生成项目面试题")
  @PostMapping("/projects/{projectId}/generate")
  public ApiResponse<ProjectResponse> generate(
      @PathVariable String projectId,
      @RequestBody ProjectQuestionGenerateRequest payload,
      HttpServletRequest request) {
    return ApiResponse.success(
        service.generateQuestions(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            projectId,
            payload));
  }

  private List<String> normalizeMaterialIds(List<String> materialIds) {
    LinkedHashSet<String> uniqueIds = new LinkedHashSet<String>();
    if (materialIds != null) {
      for (String materialId : materialIds) {
        if (materialId != null && !materialId.isBlank()) uniqueIds.add(materialId.trim());
      }
    }
    if (uniqueIds.isEmpty()) throw new IllegalArgumentException("请至少选择一个项目文件");
    if (uniqueIds.size() > 100) throw new IllegalArgumentException("单次最多批量下载 100 个项目文件");
    return new ArrayList<String>(uniqueIds);
  }

  private String uniqueZipEntryName(String fileName, Set<String> usedNames) {
    String normalized = fileName == null ? "" : fileName.replace('\\', '/');
    normalized = normalized.substring(normalized.lastIndexOf('/') + 1).replace("\u0000", "").trim();
    if (normalized.isEmpty() || ".".equals(normalized) || "..".equals(normalized))
      normalized = "project-file";
    String candidate = normalized;
    int dot = normalized.lastIndexOf('.');
    String base = dot > 0 ? normalized.substring(0, dot) : normalized;
    String extension = dot > 0 ? normalized.substring(dot) : "";
    for (int index = 2; !usedNames.add(candidate); index++) {
      candidate = base + " (" + index + ")" + extension;
    }
    return candidate;
  }

  private MediaType mediaType(String contentType) {
    try {
      return MediaType.parseMediaType(
          contentType == null ? MediaType.APPLICATION_OCTET_STREAM_VALUE : contentType);
    } catch (IllegalArgumentException ignored) {
      return MediaType.APPLICATION_OCTET_STREAM;
    }
  }
}
