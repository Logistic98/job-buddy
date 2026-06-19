package com.jobbuddy.backend.modules.job.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.jobbuddy.backend.common.dto.MapBackedDto;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.modules.job.dto.request.JobFavoriteRequest;
import com.jobbuddy.backend.modules.job.dto.response.JobFavoriteResponse;
import com.jobbuddy.backend.modules.job.service.JobFavoriteService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 岗位收藏接口，提供收藏岗位查询、保存和删除能力。
 */
@Tag(name = "岗位收藏接口")
@RestController
@RequestMapping("/api/jobs/favorites")
public class JobFavoriteController {
    private final JobFavoriteService service;

    public JobFavoriteController(JobFavoriteService service) {
        this.service = service;
    }

    /**
     * 查询收藏岗位列表。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "查询收藏岗位列表")
    @GetMapping
    public ApiResponse<List<JobFavoriteResponse>> list() {
        return ApiResponse.success(MapBackedDto.fromMapList(service.listFavorites(), JobFavoriteResponse::from));
    }

    /**
     * 保存收藏岗位。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "保存收藏岗位")
    @PostMapping
    public ApiResponse<List<JobFavoriteResponse>> save(@RequestBody JobFavoriteRequest job) {
        service.saveFavorite(job == null ? null : job.toMap());
        return ApiResponse.success(MapBackedDto.fromMapList(service.listFavorites(), JobFavoriteResponse::from));
    }

    /**
     * 分析收藏岗位与简历的匹配度，结果持久化并支持重新分析。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "分析收藏岗位")
    @PostMapping("/analyze")
    public ApiResponse<JobFavoriteResponse> analyzeByBody(@RequestBody(required = false) JobFavoriteRequest body) {
        String jobKey = body == null || body.get("jobKey") == null ? null : String.valueOf(body.get("jobKey"));
        String resumeId = body == null || body.get("resumeId") == null ? null : String.valueOf(body.get("resumeId"));
        return ApiResponse.success(JobFavoriteResponse.from(service.analyzeFavorite(jobKey, resumeId)));
    }

    /**
     * 分析收藏岗位与简历的匹配度，结果持久化并支持重新分析。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "分析收藏岗位")
    @PostMapping("/{jobKey}/analyze")
    public ApiResponse<JobFavoriteResponse> analyze(@PathVariable String jobKey,
                                                    @RequestBody(required = false) JobFavoriteRequest body) {
        String resumeId = body == null || body.get("resumeId") == null ? null : String.valueOf(body.get("resumeId"));
        return ApiResponse.success(JobFavoriteResponse.from(service.analyzeFavorite(jobKey, resumeId)));
    }

    /**
     * 删除收藏岗位。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "删除收藏岗位")
    @DeleteMapping("/{jobKey}")
    public ApiResponse<List<JobFavoriteResponse>> delete(@PathVariable String jobKey) {
        service.removeFavorite(jobKey);
        return ApiResponse.success(MapBackedDto.fromMapList(service.listFavorites(), JobFavoriteResponse::from));
    }
}
