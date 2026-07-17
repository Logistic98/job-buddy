package com.jobbuddy.backend.modules.resume.dto.response;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.Instant;
import lombok.Data;

@Data
public class ResumeSummaryResponse {
  private String resumeId;
  private String userId;
  private String originalName;
  private Long sizeBytes;
  private String suffix;
  private Instant uploadedAt;
  private String parseStatus;
  private JsonNode parsed;
  private String parseError;
}
