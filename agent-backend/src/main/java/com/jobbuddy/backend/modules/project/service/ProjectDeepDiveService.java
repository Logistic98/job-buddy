package com.jobbuddy.backend.modules.project.service;

import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionGenerateRequest;
import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionRequest;
import com.jobbuddy.backend.modules.project.dto.request.ProjectRequest;
import com.jobbuddy.backend.modules.project.dto.response.ProjectResponse;
import java.io.IOException;
import java.util.List;
import org.springframework.web.multipart.MultipartFile;

public interface ProjectDeepDiveService {
  List<ProjectResponse> listProjects(String tenantId, String userId);

  ProjectResponse getProject(String tenantId, String userId, String projectId);

  ProjectResponse saveProject(
      String tenantId, String userId, ProjectRequest request, String projectId);

  void deleteProject(String tenantId, String userId, String projectId);

  ProjectResponse addMaterial(String tenantId, String userId, String projectId, MultipartFile file)
      throws IOException;

  ProjectMaterialFile openMaterial(String tenantId, String userId, String materialId);

  void deleteMaterial(String tenantId, String userId, String materialId);

  ProjectResponse generateQuestions(
      String tenantId, String userId, String projectId, ProjectQuestionGenerateRequest request);

  ProjectResponse addQuestion(
      String tenantId, String userId, String projectId, ProjectQuestionRequest request);

  ProjectResponse updateQuestion(
      String tenantId, String userId, String questionId, ProjectQuestionRequest request);

  void deleteQuestion(String tenantId, String userId, String questionId);
}
