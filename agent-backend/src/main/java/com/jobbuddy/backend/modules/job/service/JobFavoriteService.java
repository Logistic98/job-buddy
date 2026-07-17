package com.jobbuddy.backend.modules.job.service;

import com.jobbuddy.backend.modules.analysis.dto.AnalysisPartialResult;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteAnalysisCommand;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteSaveCommand;
import com.jobbuddy.backend.modules.job.dto.request.BossFavoriteImportRequest;
import com.jobbuddy.backend.modules.job.dto.response.BossFavoriteImportResponse;
import com.jobbuddy.backend.modules.job.dto.response.BossFavoritePreviewResponse;
import com.jobbuddy.backend.modules.job.dto.response.JobFavoriteResponse;
import java.util.List;
import java.util.function.Consumer;

public interface JobFavoriteService {
  List<JobFavoriteResponse> listFavorites(String userId);

  void saveFavorite(String userId, JobFavoriteSaveCommand command);

  BossFavoritePreviewResponse previewBossFavorites(String userId, int page, boolean forceRefresh);

  BossFavoriteImportResponse importBossFavorites(String userId, BossFavoriteImportRequest request);

  void removeFavorite(String userId, String jobKey);

  JobFavoriteResponse analyzeFavorite(String userId, JobFavoriteAnalysisCommand command);

  JobFavoriteResponse analyzeJob(String userId, JobFavoriteSaveCommand command, String resumeId);

  JobFavoriteResponse analyzeJobIncrementally(
      String userId,
      JobFavoriteSaveCommand command,
      String resumeId,
      Consumer<AnalysisPartialResult> consumer);
}
