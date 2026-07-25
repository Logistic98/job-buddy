package com.jobbuddy.backend.modules.project.dto.request;

import java.util.List;
import lombok.Data;

/**
 * 承载项目题目导入请求参数。
 */
@Data
public class ProjectQuestionImportRequest {
  private List<ProjectQuestionRequest> questions;
}
