package com.jobbuddy.backend.modules.job.dto.response;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 承载收藏岗位响应数据。
 */
public class JobFavoriteResponse {
  private final JsonNode value;

  /**
   * 创建收藏岗位响应实例。
   *
   * @param value 待处理值
   */
  public JobFavoriteResponse(JsonNode value) {
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
