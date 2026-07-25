package com.jobbuddy.backend.modules.analysis.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.analysis.entity.AnalysisTask;
import java.time.Instant;

/**
 * 承载分析任务响应数据。
 */
public class AnalysisTaskResponse {
  private final String taskId;
  private final String taskType;
  private final String resourceKey;
  private final String status;
  private final String stage;
  private final String message;
  private final JsonNode result;
  private final JsonNode partialResult;
  private final String errorMessage;
  private final long version;
  private final Instant createdAt;
  private final Instant startedAt;
  private final Instant completedAt;
  private final Instant updatedAt;

  /**
   * 创建分析任务响应实例。
   *
   * @param task 任务
   * @param result 结果
   * @param partialResult 部分结果
   */
  private AnalysisTaskResponse(AnalysisTask task, JsonNode result, JsonNode partialResult) {
    this.taskId = task.getTaskId();
    this.taskType = task.getTaskType();
    this.resourceKey = task.getResourceKey();
    this.status = task.getStatus();
    this.stage = task.getStage();
    this.message = task.getMessage();
    this.result = result == null ? JsonNodeFactory.instance.objectNode() : result;
    this.partialResult =
        partialResult == null ? JsonNodeFactory.instance.objectNode() : partialResult;
    this.errorMessage = task.getErrorMessage();
    this.version = task.getVersion();
    this.createdAt = task.getCreatedAt();
    this.startedAt = task.getStartedAt();
    this.completedAt = task.getCompletedAt();
    this.updatedAt = task.getUpdatedAt();
  }

  /**
   * 根据源数据创建对象。
   *
   * @param task 任务
   * @param jsonCodec JSON 编解码器
   * @return 创建后的对象
   */
  public static AnalysisTaskResponse from(AnalysisTask task, JsonCodec jsonCodec) {
    return task == null
        ? null
        : new AnalysisTaskResponse(
            task,
            jsonCodec.readTree(task.getResultJson()),
            jsonCodec.readTree(task.getPartialResultJson()));
  }

  /**
   * 获取任务标识。
   *
   * @return 任务标识
   */
  public String getTaskId() {
    return taskId;
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
   * 获取资源键。
   *
   * @return 资源键
   */
  public String getResourceKey() {
    return resourceKey;
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
   * 获取处理阶段。
   *
   * @return 处理阶段
   */
  public String getStage() {
    return stage;
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
   * 获取结果。
   *
   * @return 结果
   */
  public JsonNode getResult() {
    return result.deepCopy();
  }

  /**
   * 获取增量结果。
   *
   * @return 增量结果
   */
  public JsonNode getPartialResult() {
    return partialResult.deepCopy();
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
   * 获取版本。
   *
   * @return 版本
   */
  public long getVersion() {
    return version;
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
   * 获取开始时间。
   *
   * @return 开始时间
   */
  public Instant getStartedAt() {
    return startedAt;
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
   * 获取更新时间。
   *
   * @return 更新时间
   */
  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
