package com.jobbuddy.backend.modules.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class InterviewDocumentExtractResponse {
  private String fileName;
  private String contentType;
  private String text;
  private Integer characterCount;
  private Boolean truncated;
}
