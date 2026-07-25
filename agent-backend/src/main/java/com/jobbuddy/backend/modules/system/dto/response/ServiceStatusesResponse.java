package com.jobbuddy.backend.modules.system.dto.response;

import com.fasterxml.jackson.annotation.JsonValue;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 承载服务状态列表响应数据。
 */
public class ServiceStatusesResponse {
  private final JsonNode statuses;

  /**
   * 创建服务状态列表响应实例。
   *
   * @param statuses 状态列表
   */
  public ServiceStatusesResponse(JsonNode statuses) {
    this.statuses = statuses;
  }

  /**
   * 读取服务状态列表。
   *
   * @return 服务状态列表
   */
  @JsonValue
  public JsonNode statuses() {
    return statuses;
  }
}
