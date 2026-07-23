package com.jobbuddy.backend.modules.analysis.mapper;

import com.jobbuddy.backend.modules.analysis.entity.AnalysisTask;
import java.util.List;
import org.apache.ibatis.annotations.Param;

public interface AnalysisTaskMapper {
  int insert(AnalysisTask task);

  AnalysisTask findOwned(
      @Param("taskId") String taskId,
      @Param("tenantId") String tenantId,
      @Param("userId") String userId);

  AnalysisTask findLatest(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("taskType") String taskType,
      @Param("resourceKey") String resourceKey);

  AnalysisTask findActive(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("taskType") String taskType,
      @Param("resourceKey") String resourceKey);

  AnalysisTask findById(@Param("taskId") String taskId);

  List<AnalysisTask> findRecoverable();

  int markRunning(
      @Param("taskId") String taskId,
      @Param("stage") String stage,
      @Param("message") String message);

  int updateProgress(
      @Param("taskId") String taskId,
      @Param("stage") String stage,
      @Param("message") String message);

  int updatePartialResult(
      @Param("taskId") String taskId,
      @Param("stage") String stage,
      @Param("message") String message,
      @Param("partialResultJson") String partialResultJson);

  int markSucceeded(@Param("taskId") String taskId, @Param("resultJson") String resultJson);

  int markFailed(@Param("taskId") String taskId, @Param("errorMessage") String errorMessage);

  int markCancelled(@Param("taskId") String taskId);
}
