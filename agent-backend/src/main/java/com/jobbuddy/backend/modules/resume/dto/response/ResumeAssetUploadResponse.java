package com.jobbuddy.backend.modules.resume.dto.response;

import lombok.Data;

/**
 * 承载简历资源上传响应数据。
 */
@Data
public class ResumeAssetUploadResponse {
  private String assetId;
  private String url;
  private String contentType;
  private Long sizeBytes;
}
