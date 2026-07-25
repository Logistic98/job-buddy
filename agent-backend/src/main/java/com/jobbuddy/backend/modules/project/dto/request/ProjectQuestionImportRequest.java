package com.jobbuddy.backend.modules.project.dto.request;

import java.util.List;
import lombok.Data;

@Data
public class ProjectQuestionImportRequest {
  private List<ProjectQuestionRequest> questions;
}
