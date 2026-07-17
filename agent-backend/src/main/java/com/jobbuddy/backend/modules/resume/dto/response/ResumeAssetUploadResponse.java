package com.jobbuddy.backend.modules.resume.dto.response;

import lombok.Data;

@Data
public class ResumeAssetUploadResponse {
  private String assetId;
  private String url;
  private String contentType;
  private Long sizeBytes;
}
