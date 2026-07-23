package com.jobbuddy.backend.modules.analysis.service;

import com.jobbuddy.backend.modules.analysis.dto.AnalysisTaskResponse;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteSaveCommand;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

public interface AnalysisTaskService {
  String TYPE_RESUME = "resume";
  String TYPE_FAVORITE_JOB = "favorite_job";

  AnalysisTaskResponse startResume(
      String tenantId, String userId, String resumeId, String sessionId);

  AnalysisTaskResponse startFavoriteJob(
      String tenantId, String userId, JobFavoriteSaveCommand command, String resumeId);

  AnalysisTaskResponse getOwned(String taskId, String tenantId, String userId);

  AnalysisTaskResponse findLatest(
      String tenantId, String userId, String taskType, String resourceKey);

  SseEmitter stream(String taskId, String tenantId, String userId);
}
