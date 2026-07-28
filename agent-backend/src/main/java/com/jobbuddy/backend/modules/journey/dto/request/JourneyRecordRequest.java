package com.jobbuddy.backend.modules.journey.dto.request;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 承载求职旅程记录请求参数。
 */
@Data
public class JourneyRecordRequest {
  @NotBlank(message = "企业名称不能为空")
  @Size(max = 120, message = "企业名称不能超过 120 个字符")
  private String company;

  private String city;
  private String companyNature;
  private String companyScale;
  private String positionName;
  private String salaryRange;
  private String favoriteKey;
  private String businessDirection;
  private String companyDescription;
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
