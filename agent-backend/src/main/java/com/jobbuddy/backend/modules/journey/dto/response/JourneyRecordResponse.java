package com.jobbuddy.backend.modules.journey.dto.response;

import java.time.Instant;
import java.util.List;
import lombok.Data;

@Data
public class JourneyRecordResponse {
  private String recordId;
  private String userId;
  private String company;
  private String city;
  private String companyNature;
  private String companyScale;
  private String positionName;
  private String salaryRange;
  private String favoriteKey;
  private String businessDirection;
  private String interviewRound;
  private String interviewTime;
  private String interviewContent;
  private String interviewFormat;
  private String result;
  private String reflection;
  private String jobDescription;
  private String interviewProcess;
  private String nextAction;
  private String status;
  private String priority;
  private List<Tag> tags;
  private Boolean enabled;
  private Instant createdAt;
  private Instant updatedAt;

  @Data
  public static class Tag {
    private String label;
  }
}
