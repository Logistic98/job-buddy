package com.jobbuddy.backend.modules.interview.dto.response;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * 承载面试文档提取响应数据。
 */
@Data
@AllArgsConstructor
public class InterviewDocumentExtractResponse {
  private String fileName;
  private String contentType;
  private String text;
  private Integer characterCount;
  private Boolean truncated;
}
