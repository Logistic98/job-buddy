package com.jobbuddy.backend.modules.job.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.job.dto.response.JobDetailResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 岗位详情接口：用户点击岗位卡片时按需拉取职位描述（JD）。
 *
 * 采用懒加载，仅在用户对某个岗位感兴趣时才访问 Boss 直聘获取详情，把请求量压到 O(用户兴趣)，
 * 避免列表阶段批量抓取触发风控。
 */
@Tag(name = "岗位详情接口")
@RestController
@RequestMapping("/api/jobs/detail")
public class JobDetailController {
    private final BossCliService bossCliService;

    public JobDetailController(BossCliService bossCliService) {
        this.bossCliService = bossCliService;
    }

    /**
     * 按 securityId 与原始链接懒加载岗位详情。
     *
     * @return 统一接口响应，未登录时由全局异常处理器返回 4001 与登录引导数据
     */
    @Operation(summary = "懒加载岗位详情")
    @GetMapping
    public ApiResponse<JobDetailResponse> detail(
            @RequestParam(value = "securityId", required = false) String securityId,
            @RequestParam(value = "url", required = false) String url) {
        try {
            return ApiResponse.success(JobDetailResponse.from(bossCliService.jobDetail(securityId, url)));
        } catch (BossAuthRequiredException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            return ApiResponse.error(5001, detailErrorMessage(exception));
        }
    }

    private String detailErrorMessage(RuntimeException exception) {
        String message = exception == null || exception.getMessage() == null ? "" : exception.getMessage().trim();
        while (message.startsWith("岗位详情获取失败：")) {
            message = message.substring("岗位详情获取失败：".length()).trim();
        }
        while (message.startsWith("获取岗位详情失败：")) {
            message = message.substring("获取岗位详情失败：".length()).trim();
        }
        if (message.isEmpty()) return "岗位详情暂时无法获取，请稍后重试或打开 Boss 原岗位查看。";
        return message;
    }
}
