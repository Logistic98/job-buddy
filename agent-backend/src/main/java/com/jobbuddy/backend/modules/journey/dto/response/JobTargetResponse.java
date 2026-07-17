package com.jobbuddy.backend.modules.journey.dto.response;

import java.time.Instant;
import lombok.Data;

@Data
public class JobTargetResponse {
  private String targetId;
  private String userId;
  private String companyNature;
  private String companyScale;
  private String location;
  private String salaryRange;
  private String domains;
  private String positions;
  private String preferredCompanies;
  private String notes;
  private Instant updatedAt;
}
