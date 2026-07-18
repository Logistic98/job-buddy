package com.jobbuddy.backend.modules.analysis.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.analysis.entity.AnalysisTask;
import java.time.Instant;

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

  public static AnalysisTaskResponse from(AnalysisTask task, JsonCodec jsonCodec) {
    return task == null
        ? null
        : new AnalysisTaskResponse(
            task,
            jsonCodec.readTree(task.getResultJson()),
            jsonCodec.readTree(task.getPartialResultJson()));
  }

  public String getTaskId() {
    return taskId;
  }

  public String getTaskType() {
    return taskType;
  }

  public String getResourceKey() {
    return resourceKey;
  }

  public String getStatus() {
    return status;
  }

  public String getStage() {
    return stage;
  }

  public String getMessage() {
    return message;
  }

  public JsonNode getResult() {
    return result.deepCopy();
  }

  public JsonNode getPartialResult() {
    return partialResult.deepCopy();
  }

  public String getErrorMessage() {
    return errorMessage;
  }

  public long getVersion() {
    return version;
  }

  public Instant getCreatedAt() {
    return createdAt;
  }

  public Instant getStartedAt() {
    return startedAt;
  }

  public Instant getCompletedAt() {
    return completedAt;
  }

  public Instant getUpdatedAt() {
    return updatedAt;
  }
}
