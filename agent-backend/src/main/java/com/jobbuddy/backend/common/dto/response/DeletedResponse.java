package com.jobbuddy.backend.common.dto.response;

/**
 * 单个资源删除结果。
 *
 * @param deleted 资源是否已被删除；资源不存在且未发生删除时为 {@code false}
 */
public record DeletedResponse(boolean deleted) {
}
