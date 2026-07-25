package com.jobbuddy.backend.modules.analysis.entity;

import java.time.Instant;

/**
 * 定义分析任务。
 */
public class AnalysisTask {
  private String taskId;
  private String tenantId;
  private String userId;
  private String taskType;
  private String resourceKey;
  private String status;
  private String stage;
  private String message;
  private String requestJson;
  private String resultJson;
  private String partialResultJson;
  private String errorMessage;
  private long version;
  private Instant createdAt;
  private Instant startedAt;
  private Instant completedAt;
  private Instant updatedAt;

  /**
   * 获取任务标识。
   *
   * @return 任务标识
   */
  public String getTaskId() {
    return taskId;
  }

  /**
   * 设置任务标识。
   *
   * @param taskId 任务标识
   */
  public void setTaskId(String taskId) {
    this.taskId = taskId;
  }

  /**
   * 获取租户标识。
   *
   * @return 租户标识
   */
  public String getTenantId() {
    return tenantId;
  }

  /**
   * 设置租户标识。
   *
   * @param tenantId 租户标识
   */
  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  /**
   * 获取用户标识。
   *
   * @return 用户标识
   */
  public String getUserId() {
    return userId;
  }

  /**
   * 设置用户标识。
   *
   * @param userId 用户标识
   */
  public void setUserId(String userId) {
    this.userId = userId;
  }

  /**
   * 获取任务类型。
   *
   * @return 任务类型
   */
  public String getTaskType() {
    return taskType;
  }

  /**
   * 设置任务类型。
   *
   * @param taskType 任务类型
   */
  public void setTaskType(String taskType) {
    this.taskType = taskType;
  }

  /**
   * 获取资源键。
   *
   * @return 资源键
   */
  public String getResourceKey() {
    return resourceKey;
  }

  /**
   * 设置资源键。
   *
   * @param resourceKey 资源键
   */
  public void setResourceKey(String resourceKey) {
    this.resourceKey = resourceKey;
  }

  /**
   * 获取状态。
   *
   * @return 状态
   */
  public String getStatus() {
    return status;
  }

  /**
   * 设置状态。
   *
   * @param status 状态
   */
  public void setStatus(String status) {
    this.status = status;
  }

  /**
   * 获取处理阶段。
   *
   * @return 处理阶段
   */
  public String getStage() {
    return stage;
  }

  /**
   * 设置处理阶段。
   *
   * @param stage 阶段
   */
  public void setStage(String stage) {
    this.stage = stage;
  }

  /**
   * 获取消息。
   *
   * @return 消息
   */
  public String getMessage() {
    return message;
  }

  /**
   * 设置消息。
   *
   * @param message 消息内容
   */
  public void setMessage(String message) {
    this.message = message;
  }

  /**
   * 获取请求 JSON。
   *
   * @return 请求 JSON
   */
  public String getRequestJson() {
    return requestJson;
  }

  /**
   * 设置请求 JSON。
   *
   * @param requestJson 请求 JSON
   */
  public void setRequestJson(String requestJson) {
    this.requestJson = requestJson;
  }

  /**
   * 获取结果 JSON。
   *
   * @return 结果 JSON
   */
  public String getResultJson() {
    return resultJson;
  }

  /**
   * 设置结果 JSON。
   *
   * @param resultJson 结果 JSON
   */
  public void setResultJson(String resultJson) {
    this.resultJson = resultJson;
  }

  /**
   * 获取增量结果 JSON。
   *
   * @return 增量结果 JSON
   */
  public String getPartialResultJson() {
    return partialResultJson;
  }

  /**
   * 设置增量结果 JSON。
   *
   * @param partialResultJson 部分结果 JSON
   */
  public void setPartialResultJson(String partialResultJson) {
    this.partialResultJson = partialResultJson;
  }

  /**
   * 获取错误消息。
   *
   * @return 错误消息
   */
  public String getErrorMessage() {
    return errorMessage;
  }

  /**
   * 设置错误消息。
   *
   * @param errorMessage 错误消息
   */
  public void setErrorMessage(String errorMessage) {
    this.errorMessage = errorMessage;
  }

  /**
   * 获取版本。
   *
   * @return 版本
   */
  public long getVersion() {
    return version;
  }

  /**
   * 设置版本。
   *
   * @param version 版本
   */
  public void setVersion(long version) {
    this.version = version;
  }

  /**
   * 获取创建时间。
   *
   * @return 创建时间
   */
  public Instant getCreatedAt() {
    return createdAt;
  }

  /**
   * 设置创建时间。
   *
   * @param createdAt 创建时间
   */
  public void setCreatedAt(Instant createdAt) {
    this.createdAt = createdAt;
  }

  /**
   * 获取开始时间。
   *
   * @return 开始时间
   */
  public Instant getStartedAt() {
    return startedAt;
  }

  /**
   * 设置开始时间。
   *
   * @param startedAt 开始时间
   */
  public void setStartedAt(Instant startedAt) {
    this.startedAt = startedAt;
  }

  /**
   * 获取完成时间。
   *
   * @return 完成时间
   */
  public Instant getCompletedAt() {
    return completedAt;
  }

  /**
   * 设置完成时间。
   *
   * @param completedAt 完成时间
   */
  public void setCompletedAt(Instant completedAt) {
    this.completedAt = completedAt;
  }

  /**
   * 获取更新时间。
   *
   * @return 更新时间
   */
  public Instant getUpdatedAt() {
    return updatedAt;
  }

  /**
   * 设置更新时间。
   *
   * @param updatedAt 更新时间
   */
  public void setUpdatedAt(Instant updatedAt) {
    this.updatedAt = updatedAt;
  }

  /**
   * 判断是否终态。
   *
   * @return 是否已进入终态
   */
  public boolean isTerminal() {
    return "succeeded".equals(status) || "failed".equals(status) || "cancelled".equals(status);
  }
}
