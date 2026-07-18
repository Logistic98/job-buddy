package com.jobbuddy.backend.modules.project.dto.response;

import java.time.Instant;
import lombok.Data;

@Data
public class ProjectMaterialResponse {
  private String materialId;
  private String projectId;
  private String fileName;
  private String contentType;
  private Long sizeBytes;
  private Instant createdAt;
}
