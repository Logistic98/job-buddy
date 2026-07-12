package com.jobbuddy.backend.common.dto.response;

/**
 * 会话创建或恢复结果。
 *
 * @param sessionId 会话的唯一标识
 */
public record SessionIdResponse(String sessionId) {
}
