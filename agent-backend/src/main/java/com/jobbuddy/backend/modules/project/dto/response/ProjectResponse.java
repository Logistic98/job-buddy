package com.jobbuddy.backend.modules.project.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.List;
import lombok.Data;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ProjectResponse {
  private String projectId;
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
  private Boolean enabled;
  private Instant createdAt;
  private Instant updatedAt;
  private Integer materialCount;
  private Integer questionCount;
  private List<ProjectMaterialResponse> materials;
  private List<ProjectQuestionResponse> questions;
}
