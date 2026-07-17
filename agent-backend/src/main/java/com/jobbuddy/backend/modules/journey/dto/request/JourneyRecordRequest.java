package com.jobbuddy.backend.modules.journey.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class JourneyRecordRequest {
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
  private JsonNode tags;
}
