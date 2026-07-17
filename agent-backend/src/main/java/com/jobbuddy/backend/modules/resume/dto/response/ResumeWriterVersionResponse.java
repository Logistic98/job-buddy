package com.jobbuddy.backend.modules.resume.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ResumeWriterVersionResponse {
  private String versionId;
  private String tenantId;
  private String userId;
  private String resumeId;
  private Long versionNo;
  private String source;
  private String title;
  private String snapshotJson;
  private Integer snapshotBytes;
  private Instant createdAt;
}
