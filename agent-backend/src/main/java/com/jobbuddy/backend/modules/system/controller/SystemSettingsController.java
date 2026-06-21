package com.jobbuddy.backend.modules.system.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.jobbuddy.backend.common.dto.response.DeleteCountResponse;
import com.jobbuddy.backend.common.dto.response.NamedValueResponse;
import com.jobbuddy.backend.common.dto.MapBackedDto;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.modules.system.dto.request.SystemMemoryRequest;
import com.jobbuddy.backend.modules.system.dto.response.SystemMemoryResponse;
import com.jobbuddy.backend.modules.system.dto.request.SystemSettingsRequest;
import com.jobbuddy.backend.modules.system.dto.response.SystemSettingsResponse;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
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
 * 系统设置接口，提供平台设置和长期记忆管理能力。
 */
@Tag(name = "系统设置接口")
@RestController
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
        return ApiResponse.success(SystemSettingsResponse.from(systemSettingsService.getSettings()));
    }

    /**
     * 保存系统设置。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "保存系统设置")
    @PutMapping
    public ApiResponse<SystemSettingsResponse> saveSettings(@RequestBody SystemSettingsRequest payload) {
        return ApiResponse.success(SystemSettingsResponse.from(systemSettingsService.saveSettings(payload == null ? null : payload.toMap())));
    }

    /**
     * 查询记忆列表。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "查询记忆列表")
    @GetMapping("/memories")
    public ApiResponse<List<SystemMemoryResponse>> listMemories() {
        return ApiResponse.success(MapBackedDto.fromMapList(systemSettingsService.listMemories(), SystemMemoryResponse::from));
    }

    /**
     * 新增记忆。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "新增记忆")
    @PostMapping("/memories")
    public ApiResponse<SystemMemoryResponse> addMemory(@RequestBody SystemMemoryRequest payload) {
        return ApiResponse.success(SystemMemoryResponse.from(systemSettingsService.addMemory(payload == null ? null : payload.toMap())));
    }

    /**
     * 删除记忆。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "删除记忆")
    @DeleteMapping("/memories/{memoryId}")
    public ApiResponse<NamedValueResponse> deleteMemory(@PathVariable String memoryId) {
        systemSettingsService.deleteMemory(memoryId);
        return ApiResponse.success(new NamedValueResponse("memoryId", memoryId));
    }

    /**
     * 清空记忆。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "清空记忆")
    @DeleteMapping("/memories")
    public ApiResponse<DeleteCountResponse> clearMemories() {
        int count = systemSettingsService.clearMemories();
        return ApiResponse.success(new DeleteCountResponse(count));
    }
}
