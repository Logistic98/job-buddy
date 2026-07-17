package com.jobbuddy.backend.modules.resume.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

@Data
public class ResumeProfileSummaryResponse {
  private String oldSummary;
  private String newSummary;
  private JsonNode highlights;
  private JsonNode missingFields;
  private String provider;
}
