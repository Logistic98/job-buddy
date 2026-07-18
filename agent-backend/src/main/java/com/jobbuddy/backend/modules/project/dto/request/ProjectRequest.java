package com.jobbuddy.backend.modules.project.dto.request;

import lombok.Data;

@Data
public class ProjectRequest {
  private String name;
  private String role;
  private String summary;
  private String techStack;
  private String projectPeriod;
  private String teamSize;
  private String projectType;
  private String businessDomain;
  private String projectStatus;
  private String background;
  private String responsibilities;
  private String highlights;
  private String challenges;
  private String outcomes;
}
