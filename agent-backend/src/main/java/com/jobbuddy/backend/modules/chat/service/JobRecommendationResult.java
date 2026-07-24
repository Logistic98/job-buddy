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
  private final int rejectedCount;
  private final Map<String, Integer> rejectionReasons;
  private final List<String> warnings;

  public JobRecommendationResult(
      List<Map<String, Object>> jobs,
      int candidateCount,
      Map<String, Integer> rejectionReasons,
      List<String> warnings) {
    this.jobs = copyJobs(jobs);
    if (candidateCount < 0) {
      throw new IllegalArgumentException("candidateCount 不能为负数");
    }
    this.candidateCount = candidateCount;
    this.qualifiedCount = this.jobs.size();
    this.rejectionReasons = copyRejectionReasons(rejectionReasons);
    this.rejectedCount = sumRejections(this.rejectionReasons);
    int funnelAccountedCount = Math.addExact(this.qualifiedCount, this.rejectedCount);
    if (this.candidateCount != funnelAccountedCount) {
      throw new IllegalArgumentException(
          "岗位推荐漏斗计数不守恒：candidateCount="
              + this.candidateCount
              + ", qualifiedCount="
              + this.qualifiedCount
              + ", rejectedCount="
              + this.rejectedCount);
    }
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

  public int getRejectedCount() {
    return rejectedCount;
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

  private static Map<String, Integer> copyRejectionReasons(Map<String, Integer> source) {
    if (source == null || source.isEmpty()) return Collections.<String, Integer>emptyMap();
    Map<String, Integer> result = new LinkedHashMap<String, Integer>();
    for (Map.Entry<String, Integer> entry : source.entrySet()) {
      if (entry.getValue() == null || entry.getValue().intValue() < 0) {
        throw new IllegalArgumentException("岗位推荐拒绝原因计数不能为 null 或负数：" + entry.getKey());
      }
      int count = entry.getValue().intValue();
      result.put(entry.getKey(), Integer.valueOf(count));
    }
    return result;
  }

  private static int sumRejections(Map<String, Integer> rejectionReasons) {
    int total = 0;
    for (Integer count : rejectionReasons.values()) {
      total = Math.addExact(total, count.intValue());
    }
    return total;
  }
}
