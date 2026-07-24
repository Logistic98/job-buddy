package com.jobbuddy.backend.common.dto.response;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

/** 表示操作是否成功完成的通用响应，适用于无需返回额外业务数据的接口。 */
@Data
@Schema(description = "通用布尔操作结果")
public class BooleanResultResponse {
  /** 操作是否成功完成。 */
  @Schema(description = "操作是否成功完成", example = "true")
  private boolean ok;

  public BooleanResultResponse() {}

  public BooleanResultResponse(boolean ok) {
    this.ok = ok;
  }
}
