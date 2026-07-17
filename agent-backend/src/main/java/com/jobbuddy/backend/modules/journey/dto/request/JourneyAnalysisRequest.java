package com.jobbuddy.backend.modules.journey.dto.request;

import java.util.List;
import lombok.Data;

@Data
public class JourneyAnalysisRequest {
  private String recordId;
  private List<String> recordIds;
}
