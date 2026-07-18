package com.jobbuddy.backend.modules.project.repository;

import com.jobbuddy.backend.modules.project.mapper.ProjectDeepDiveMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * Repository adapter for project deep-dive data.
 *
 * <p>Hydration is kept here so services can work with normalized timestamps, material previews, and
 * project aggregates without duplicating persistence details.
 */
@Repository
public class ProjectDeepDiveRepository {
  private final ProjectDeepDiveMapper mapper;

  public ProjectDeepDiveRepository(ProjectDeepDiveMapper mapper) {
    this.mapper = mapper;
  }

  public List<Map<String, Object>> listProjects(String tenantId, String userId) {
    List<Map<String, Object>> projects = mapper.listProjects(tenantId, userId);
    for (Map<String, Object> project : projects) {
      normalizeTime(project, "createdAt");
      normalizeTime(project, "updatedAt");
    }
    return projects;
  }

  public Map<String, Object> findProject(String tenantId, String userId, String projectId) {
    return hydrateProject(tenantId, userId, mapper.findProject(tenantId, userId, projectId));
  }

  public void saveProject(Map<String, Object> project) {
    Timestamp now = Timestamp.from(Instant.now());
    project.put("updatedAt", now);
    if (mapper.countProject(
            project.get("tenantId").toString(),
            project.get("userId").toString(),
            project.get("projectId"))
        > 0) {
      mapper.updateProject(project);
    } else {
      project.put("createdAt", now);
      mapper.insertProject(project);
    }
  }

  public void deleteProject(String tenantId, String userId, String projectId) {
    mapper.deleteProject(tenantId, userId, projectId, Timestamp.from(Instant.now()));
  }

  public void saveMaterial(String tenantId, String userId, Map<String, Object> material) {
    material.put("createdAt", Timestamp.from(Instant.now()));
    mapper.insertMaterial(material);
    touchProject(tenantId, userId, String.valueOf(material.get("projectId")));
  }

  public void deleteMaterial(String tenantId, String userId, String materialId) {
    Map<String, Object> material = findMaterial(tenantId, userId, materialId);
    mapper.deleteMaterial(tenantId, userId, materialId);
    if (material != null) {
      touchProject(tenantId, userId, String.valueOf(material.get("projectId")));
    }
  }

  public Map<String, Object> findMaterial(String tenantId, String userId, String materialId) {
    Map<String, Object> material = mapper.findMaterial(tenantId, userId, materialId);
    normalizeTime(material, "createdAt");
    return material;
  }

  public Map<String, Object> findMaterialBySha256(
      String tenantId, String userId, String projectId, String sha256) {
    Map<String, Object> material = mapper.findMaterialBySha256(tenantId, userId, projectId, sha256);
    normalizeTime(material, "createdAt");
    return material;
  }

  public List<Map<String, Object>> listMaterials(String tenantId, String userId, String projectId) {
    List<Map<String, Object>> rows = mapper.listMaterials(tenantId, userId, projectId);
    for (Map<String, Object> row : rows) {
      hydrateMaterial(row);
    }
    return rows;
  }

  public void replaceQuestions(
      String tenantId, String userId, String projectId, List<Map<String, Object>> questions) {
    mapper.deleteQuestions(tenantId, userId, projectId);
    Timestamp now = Timestamp.from(Instant.now());
    for (Map<String, Object> question : questions) {
      question.put("projectId", projectId);
      question.put("createdAt", now);
      mapper.insertQuestion(question);
    }
    touchProject(tenantId, userId, projectId);
  }

  public Map<String, Object> findQuestion(String tenantId, String userId, String questionId) {
    Map<String, Object> question = mapper.findQuestion(tenantId, userId, questionId);
    normalizeTime(question, "createdAt");
    return question;
  }

  public void saveQuestion(String tenantId, String userId, Map<String, Object> question) {
    question.put("createdAt", Timestamp.from(Instant.now()));
    mapper.insertQuestion(question);
    touchProject(tenantId, userId, String.valueOf(question.get("projectId")));
  }

  public void updateQuestion(
      String tenantId, String userId, String projectId, Map<String, Object> question) {
    question.put("tenantId", tenantId);
    question.put("userId", userId);
    mapper.updateQuestion(question);
    touchProject(tenantId, userId, projectId);
  }

  public void deleteQuestion(String tenantId, String userId, String questionId) {
    Map<String, Object> question = mapper.findQuestion(tenantId, userId, questionId);
    mapper.deleteQuestion(tenantId, userId, questionId);
    if (question != null) {
      touchProject(tenantId, userId, String.valueOf(question.get("projectId")));
    }
  }

  public List<Map<String, Object>> listQuestions(String tenantId, String userId, String projectId) {
    List<Map<String, Object>> rows = mapper.listQuestions(tenantId, userId, projectId);
    for (Map<String, Object> row : rows) {
      normalizeTime(row, "createdAt");
    }
    return rows;
  }

  private void touchProject(String tenantId, String userId, String projectId) {
    mapper.touchProject(tenantId, userId, projectId, Timestamp.from(Instant.now()));
  }

  private Map<String, Object> hydrateProject(
      String tenantId, String userId, Map<String, Object> item) {
    if (item == null) {
      return null;
    }

    normalizeTime(item, "createdAt");
    normalizeTime(item, "updatedAt");
    item.put("materials", listMaterials(tenantId, userId, String.valueOf(item.get("projectId"))));
    item.put("questions", listQuestions(tenantId, userId, String.valueOf(item.get("projectId"))));
    return item;
  }

  private void hydrateMaterial(Map<String, Object> item) {
    normalizeTime(item, "createdAt");
    if (item.get("sizeBytes") == null) {
      String content = item.get("content") == null ? "" : String.valueOf(item.get("content"));
      item.put(
          "sizeBytes",
          Long.valueOf(content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length));
    }
    item.remove("content");
    item.remove("storagePath");
    item.remove("sha256");
  }

  private void normalizeTime(Map<String, Object> item, String key) {
    if (item != null && item.get(key) instanceof Timestamp) {
      item.put(key, ((Timestamp) item.get(key)).toInstant().toString());
    }
  }
}
