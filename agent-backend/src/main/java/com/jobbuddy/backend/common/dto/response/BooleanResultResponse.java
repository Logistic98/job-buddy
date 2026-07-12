package com.jobbuddy.backend.common.dto.response;

import lombok.Data;

/** 表示操作是否成功完成的通用响应，适用于无需返回额外业务数据的接口。 */
@Data
public class BooleanResultResponse {
  /** 操作是否成功完成。 */
  private boolean ok;

  public BooleanResultResponse() {}

  public BooleanResultResponse(boolean ok) {
    this.ok = ok;
  }
}
