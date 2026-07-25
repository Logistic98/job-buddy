package com.jobbuddy.backend.common.dto.response;

import lombok.Data;

/**
 * 批量删除操作结果。
 */
@Data
public class DeleteCountResponse {
  /**
   * 本次实际删除的资源数量。
   */
  private int deleted;

  /**
   * 创建删除数量响应实例。
   */
  public DeleteCountResponse() {}

  /**
   * 创建删除数量响应实例。
   *
   * @param deleted 删除数量
   */
  public DeleteCountResponse(int deleted) {
    this.deleted = deleted;
  }
}
