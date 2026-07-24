package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import java.util.List;
import java.util.Map;

public interface JobRuntimeService {
  Map<String, Object> startBossLogin(String sessionId);

  boolean hasUsableBossCredential();

  List<Map<String, Object>> recommendJobs(IntentResult intent, String sessionId);

  List<Map<String, Object>> recommendJobs(
      IntentResult intent, String sessionId, JobProgressConsumer consumer);

  List<Map<String, Object>> recommendJobsFast(
      IntentResult intent, String sessionId, JobProgressConsumer consumer);

  int bossCandidatePoolTimeoutSeconds();

  JobRecommendationResult prequalifyRecommendations(
      ResumeRecord resume, List<Map<String, Object>> jobs, String sessionId);

  JobRecommendationResult prequalifyRecommendationsWithContinuation(
      ResumeRecord resume,
      IntentResult intent,
      List<Map<String, Object>> initialJobs,
      String sessionId);

  Map<String, Object> matchResume(
      ResumeRecord resume, List<Map<String, Object>> jobs, String sessionId);

  Map<String, Object> matchResumeSections(
      ResumeRecord resume, List<Map<String, Object>> jobs, String sessionId, List<String> sections);

  interface JobProgressConsumer {
    void accept(
        List<Map<String, Object>> previewJobs,
        List<Map<String, Object>> latestBatch,
        String query,
        int page);
  }
}
