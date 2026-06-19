package com.jobbuddy.backend.modules.project.service;

import java.util.List;
import java.util.Map;

public interface ProjectDeepDiveService {
    List<Map<String, Object>> listProjects();
    Map<String, Object> saveProject(Map<String, Object> payload, String projectId);
    void deleteProject(String projectId);
    Map<String, Object> addMaterial(String projectId, Map<String, Object> payload);
    void deleteMaterial(String materialId);
    Map<String, Object> generateQuestions(String projectId, Map<String, Object> payload);
}
