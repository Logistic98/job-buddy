package com.jobbuddy.backend.modules.resume.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * 承载简历画像摘要响应数据。
 */
@Data
public class ResumeProfileSummaryResponse {
  private String oldSummary;
  private String newSummary;
  private JsonNode highlights;
  private JsonNode missingFields;
  private String provider;
}
