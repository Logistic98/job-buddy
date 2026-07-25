package com.jobbuddy.backend.common.dto.response;

/**
 * 单个资源删除结果。
 *
 * @param deleted 删除数量
 */
public record DeletedResponse(boolean deleted) {
}
