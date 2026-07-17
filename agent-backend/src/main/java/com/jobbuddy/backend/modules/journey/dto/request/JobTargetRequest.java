package com.jobbuddy.backend.modules.journey.dto.request;

import lombok.Data;

@Data
public class JobTargetRequest {
  private String targetId;
  private String companyNature;
  private String companyScale;
  private String location;
  private String salaryRange;
  private String domains;
  private String positions;
  private String preferredCompanies;
  private String notes;
}
