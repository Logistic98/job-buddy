package com.jobbuddy.backend.modules.journey.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.jobbuddy.backend.common.dto.response.NamedValueResponse;
import com.jobbuddy.backend.common.dto.MapBackedDto;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.modules.journey.dto.request.JobTargetRequest;
import com.jobbuddy.backend.modules.journey.dto.response.JobTargetResponse;
import com.jobbuddy.backend.modules.journey.dto.request.JourneyAnalysisRequest;
import com.jobbuddy.backend.modules.journey.dto.response.JourneyAnalysisResponse;
import com.jobbuddy.backend.modules.journey.dto.request.JourneyRecordRequest;
import com.jobbuddy.backend.modules.journey.dto.response.JourneyRecordResponse;
import com.jobbuddy.backend.modules.journey.service.JobJourneyService;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.Collections;
import java.util.List;

/**
 * 求职旅程接口，提供求职目标、投递记录和进展分析能力。
 */
@Tag(name = "求职旅程接口")
@RestController
@RequestMapping("/api/journey")
public class JobJourneyController {
    private final JobJourneyService service;

    public JobJourneyController(JobJourneyService service) {
        this.service = service;
    }

    /**
     * 查询求职目标。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "查询求职目标")
    @GetMapping("/target")
    public ApiResponse<JobTargetResponse> target(HttpServletRequest request) {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(JobTargetResponse.from(service.getTarget(userId)));
    }

    /**
     * 保存求职目标。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "保存求职目标")
    @PutMapping("/target")
    public ApiResponse<JobTargetResponse> saveTarget(HttpServletRequest request,
                                                     @RequestBody JobTargetRequest payload) {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(JobTargetResponse.from(service.saveTarget(userId, payload == null ? Collections.<String, Object>emptyMap() : payload.toMap())));
    }

    /**
     * 查询求职记录列表。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "查询求职记录列表")
    @GetMapping("/records")
    public ApiResponse<List<JourneyRecordResponse>> records(HttpServletRequest request,
                                                            @RequestParam(value = "keyword", required = false) String keyword,
                                                            @RequestParam(value = "status", required = false) String status,
                                                            @RequestParam(value = "result", required = false) String result) {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(MapBackedDto.fromMapList(service.listRecords(userId, keyword, status, result), JourneyRecordResponse::from));
    }

    /**
     * 分析求职进展。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "分析求职进展")
    @PostMapping("/analysis")
    public ApiResponse<JourneyAnalysisResponse> analyze(HttpServletRequest request,
                                                        @RequestBody(required = false) JourneyAnalysisRequest payload) {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(JourneyAnalysisResponse.from(service.analyzeProgress(userId, payload == null ? Collections.<String, Object>emptyMap() : payload.toMap())));
    }

    /**
     * 查询求职记录详情。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "查询求职记录详情")
    @GetMapping("/records/{recordId}")
    public ApiResponse<JourneyRecordResponse> record(@PathVariable String recordId) {
        return ApiResponse.success(JourneyRecordResponse.from(service.getRecord(recordId)));
    }

    /**
     * 创建求职记录。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "创建求职记录")
    @PostMapping("/records")
    public ApiResponse<JourneyRecordResponse> createRecord(HttpServletRequest request,
                                                           @RequestBody JourneyRecordRequest payload) {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(JourneyRecordResponse.from(service.saveRecord(userId, payload == null ? Collections.<String, Object>emptyMap() : payload.toMap(), null)));
    }

    /**
     * 更新求职记录。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "更新求职记录")
    @PutMapping("/records/{recordId}")
    public ApiResponse<JourneyRecordResponse> updateRecord(HttpServletRequest request,
                                                           @PathVariable String recordId,
                                                           @RequestBody JourneyRecordRequest payload) {
        String userId = AuthenticatedUserContext.userId(request);
        return ApiResponse.success(JourneyRecordResponse.from(service.saveRecord(userId, payload == null ? Collections.<String, Object>emptyMap() : payload.toMap(), recordId)));
    }

    /**
     * 删除求职记录。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "删除求职记录")
    @DeleteMapping("/records/{recordId}")
    public ApiResponse<NamedValueResponse> deleteRecord(@PathVariable String recordId) {
        service.deleteRecord(recordId);
        return ApiResponse.success(new NamedValueResponse("recordId", recordId));
    }
}
