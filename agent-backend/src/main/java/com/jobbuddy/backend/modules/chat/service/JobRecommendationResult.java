package com.jobbuddy.backend.modules.chat.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 岗位推荐严格质量门结果，包含最终岗位和不含敏感正文的候选漏斗摘要。 */
public class JobRecommendationResult {
  private final List<Map<String, Object>> jobs;
  private final int candidateCount;
  private final int qualifiedCount;
  private final Map<String, Integer> rejectionReasons;
  private final List<String> warnings;

  public JobRecommendationResult(
      List<Map<String, Object>> jobs,
      int candidateCount,
      Map<String, Integer> rejectionReasons,
      List<String> warnings) {
    this.jobs = copyJobs(jobs);
    this.candidateCount = Math.max(0, candidateCount);
    this.qualifiedCount = this.jobs.size();
    this.rejectionReasons =
        rejectionReasons == null
            ? Collections.<String, Integer>emptyMap()
            : new LinkedHashMap<String, Integer>(rejectionReasons);
    this.warnings =
        warnings == null ? Collections.<String>emptyList() : new ArrayList<String>(warnings);
  }

  public List<Map<String, Object>> getJobs() {
    return copyJobs(jobs);
  }

  public int getCandidateCount() {
    return candidateCount;
  }

  public int getQualifiedCount() {
    return qualifiedCount;
  }

  public Map<String, Integer> getRejectionReasons() {
    return new LinkedHashMap<String, Integer>(rejectionReasons);
  }

  public List<String> getWarnings() {
    return new ArrayList<String>(warnings);
  }

  private static List<Map<String, Object>> copyJobs(List<Map<String, Object>> source) {
    List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
    if (source == null) return result;
    for (Map<String, Object> row : source) {
      if (row != null) result.add(new LinkedHashMap<String, Object>(row));
    }
    return result;
  }
}
