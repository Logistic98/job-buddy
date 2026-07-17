package com.jobbuddy.backend.modules.resume.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.modules.analysis.dto.AnalysisPartialResult;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeAssetUploadResponse;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeProfileSummaryResponse;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeSummaryResponse;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.web.multipart.MultipartFile;

public interface ResumeStorageService {
  ResumeRecord upload(MultipartFile file, String tenantId, String userId) throws IOException;

  ResumeAssetUploadResponse uploadAsset(MultipartFile file, String tenantId, String userId)
      throws IOException;

  InputStream openAsset(String assetToken, String userId);

  String assetContentType(String assetToken, String userId);

  ResumeRecord syncBossOnlineResume(String tenantId, String userId) throws IOException;

  ResumeSummaryResponse getJobProfileOrEmpty(String userId);

  ResumeSummaryResponse getJobProfileOrEmpty(String tenantId, String userId);

  ResumeRecord getOrCreateJobProfile(String userId) throws IOException;

  ResumeRecord saveJobProfile(String tenantId, String userId, JsonNode parsed) throws IOException;

  ResumeProfileSummaryResponse generateJobProfileSummary(JsonNode parsed, String sessionId);

  ResumeRecord get(String resumeId);

  ResumeRecord get(String resumeId, String userId);

  ResumeRecord get(String resumeId, String tenantId, String userId);

  InputStream openOriginalFile(String resumeId, String tenantId, String userId);

  byte[] thumbnail(String resumeId, String tenantId, String userId);

  ResumeRecord updateParsed(String resumeId, JsonNode parsed, String tenantId, String userId);

  void delete(String resumeId, String tenantId, String userId);

  List<ResumeSummaryResponse> list(String userId);

  List<ResumeSummaryResponse> list(String tenantId, String userId);

  ResumeRecord analyzeSync(String resumeId, String sessionId);

  ResumeRecord analyzeSync(String resumeId, String sessionId, String tenantId, String userId);

  ResumeRecord analyzeIncrementally(
      String resumeId,
      String sessionId,
      String tenantId,
      String userId,
      Consumer<AnalysisPartialResult> consumer);

  ResumeRecord parseSync(String resumeId, String sessionId);

  ResumeRecord parseSync(String resumeId, String sessionId, String tenantId, String userId);

  ResumeSummaryResponse summarize(ResumeRecord record);
}
