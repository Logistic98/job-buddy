package com.jobbuddy.backend.modules.project.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.jobbuddy.backend.common.dto.response.NamedValueResponse;
import com.jobbuddy.backend.common.dto.MapBackedDto;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.modules.project.dto.request.ProjectMaterialRequest;
import com.jobbuddy.backend.modules.project.dto.response.ProjectMaterialResponse;
import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionGenerateRequest;
import com.jobbuddy.backend.modules.project.dto.response.ProjectQuestionGenerateResponse;
import com.jobbuddy.backend.modules.project.dto.request.ProjectRequest;
import com.jobbuddy.backend.modules.project.dto.response.ProjectResponse;
import com.jobbuddy.backend.modules.project.service.ProjectDeepDiveService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 项目深挖接口，提供项目、项目材料和项目面试题生成能力。
 */
@Tag(name = "项目深挖接口")
@RestController
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
    public ApiResponse<List<ProjectResponse>> projects() {
        return ApiResponse.success(MapBackedDto.fromMapList(service.listProjects(), ProjectResponse::from));
    }

    /**
     * 创建项目。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "创建项目")
    @PostMapping("/projects")
    public ApiResponse<ProjectResponse> createProject(@RequestBody ProjectRequest payload) {
        return ApiResponse.success(ProjectResponse.from(service.saveProject(payload == null ? null : payload.toMap(), null)));
    }

    /**
     * 更新项目。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "更新项目")
    @PutMapping("/projects/{projectId}")
    public ApiResponse<ProjectResponse> updateProject(@PathVariable String projectId, @RequestBody ProjectRequest payload) {
        return ApiResponse.success(ProjectResponse.from(service.saveProject(payload == null ? null : payload.toMap(), projectId)));
    }

    /**
     * 删除项目。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "删除项目")
    @DeleteMapping("/projects/{projectId}")
    public ApiResponse<NamedValueResponse> deleteProject(@PathVariable String projectId) {
        service.deleteProject(projectId);
        return ApiResponse.success(new NamedValueResponse("projectId", projectId));
    }

    /**
     * 新增项目材料。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "新增项目材料")
    @PostMapping("/projects/{projectId}/materials")
    public ApiResponse<ProjectMaterialResponse> addMaterial(@PathVariable String projectId, @RequestBody ProjectMaterialRequest payload) {
        return ApiResponse.success(ProjectMaterialResponse.from(service.addMaterial(projectId, payload == null ? null : payload.toMap())));
    }

    /**
     * 删除项目材料。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "删除项目材料")
    @DeleteMapping("/materials/{materialId}")
    public ApiResponse<NamedValueResponse> deleteMaterial(@PathVariable String materialId) {
        service.deleteMaterial(materialId);
        return ApiResponse.success(new NamedValueResponse("materialId", materialId));
    }

    /**
     * 生成项目面试题。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "生成项目面试题")
    @PostMapping("/projects/{projectId}/generate")
    public ApiResponse<ProjectQuestionGenerateResponse> generate(@PathVariable String projectId, @RequestBody ProjectQuestionGenerateRequest payload) {
        return ApiResponse.success(ProjectQuestionGenerateResponse.from(service.generateQuestions(projectId, payload == null ? null : payload.toMap())));
    }
}
