package com.jobbuddy.backend.common.dto.response;

import lombok.Data;

/** 批量删除操作结果。 */
@Data
public class DeleteCountResponse {
  /** 本次实际删除的资源数量。 */
  private int deleted;

  public DeleteCountResponse() {}

  public DeleteCountResponse(int deleted) {
    this.deleted = deleted;
  }
}
