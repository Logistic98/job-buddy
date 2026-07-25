package com.jobbuddy.backend.modules.project.dto.response;

import java.time.Instant;
import lombok.Data;

/**
 * 承载项目材料响应数据。
 */
@Data
public class ProjectMaterialResponse {
  private String materialId;
  private String projectId;
  private String fileName;
  private String contentType;
  private Long sizeBytes;
  private Instant createdAt;
}
