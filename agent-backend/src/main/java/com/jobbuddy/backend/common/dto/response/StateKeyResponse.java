package com.jobbuddy.backend.common.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 工作区状态操作结果。
 *
 * @param stateKey 已完成操作的工作区状态键
 */
@Schema(description = "工作区状态操作结果")
public record StateKeyResponse(String stateKey) {
}
