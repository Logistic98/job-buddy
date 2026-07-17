package com.jobbuddy.backend.modules.journey.service;

import com.jobbuddy.backend.modules.journey.dto.request.JobTargetRequest;
import com.jobbuddy.backend.modules.journey.dto.request.JourneyAnalysisRequest;
import com.jobbuddy.backend.modules.journey.dto.request.JourneyRecordRequest;
import com.jobbuddy.backend.modules.journey.dto.response.JobTargetResponse;
import com.jobbuddy.backend.modules.journey.dto.response.JourneyAnalysisResponse;
import com.jobbuddy.backend.modules.journey.dto.response.JourneyRecordResponse;
import java.util.List;

public interface JobJourneyService {
  JobTargetResponse getTarget(String userId);

  JobTargetResponse saveTarget(String userId, JobTargetRequest request);

  List<JourneyRecordResponse> listRecords(
      String userId, String keyword, String status, String result);

  JourneyRecordResponse getRecord(String recordId, String userId);

  JourneyRecordResponse saveRecord(String userId, JourneyRecordRequest request, String recordId);

  void deleteRecord(String recordId, String userId);

  JourneyAnalysisResponse analyzeProgress(String userId, JourneyAnalysisRequest request);
}
