package com.jobbuddy.backend.common.dto.response;

import lombok.Data;

/**
 * 服务健康检查响应。
 */
@Data
public class HealthResponse {
  /**
   * 当前健康状态，例如 {@code UP}。
   */
  private String status;

  /**
   * 返回健康状态的服务名称。
   */
  private String service;

  /**
   * 创建健康状态响应实例。
   */
  public HealthResponse() {}

  /**
   * 创建健康状态响应实例。
   *
   * @param status 状态
   * @param service 服务
   */
  public HealthResponse(String status, String service) {
    this.status = status;
    this.service = service;
  }
}
