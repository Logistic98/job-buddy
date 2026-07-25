package com.jobbuddy.backend.modules.journey.dto.request;

import java.util.List;
import lombok.Data;

/**
 * 承载求职旅程分析请求参数。
 */
@Data
public class JourneyAnalysisRequest {
  private String recordId;
  private List<String> recordIds;
}
