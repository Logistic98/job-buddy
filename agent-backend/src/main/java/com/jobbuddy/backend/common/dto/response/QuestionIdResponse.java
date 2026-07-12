package com.jobbuddy.backend.common.dto.response;

/**
 * 题目创建或更新结果。
 *
 * @param questionId 已持久化题目的唯一标识
 */
public record QuestionIdResponse(String questionId) {
}
