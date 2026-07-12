package com.jobbuddy.backend.common.dto.response;

import lombok.Data;

/** 仅返回资源唯一标识的通用操作结果。 */
@Data
public class IdResponse {
  /** 已创建、更新或定位的资源标识。 */
  private String id;

  public IdResponse() {}

  public IdResponse(String id) {
    this.id = id;
  }
}
