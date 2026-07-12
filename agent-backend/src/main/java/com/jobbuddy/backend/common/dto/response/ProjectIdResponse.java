package com.jobbuddy.backend.common.dto.response;

/**
 * 项目创建或更新结果。
 *
 * @param projectId 已持久化项目的唯一标识
 */
public record ProjectIdResponse(String projectId) {
}
