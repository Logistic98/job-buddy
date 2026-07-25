package com.jobbuddy.backend.modules.job.dto.response;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 承载岗位详情响应数据。
 */
public class JobDetailResponse {
  private final JsonNode value;

  /**
   * 创建岗位详情响应实例。
   *
   * @param value 待处理值
   */
  public JobDetailResponse(JsonNode value) {
    this.value = value;
  }

  /**
   * 读取当前值。
   *
   * @return 当前值
   */
  @JsonValue
  public JsonNode value() {
    return value;
  }
}
