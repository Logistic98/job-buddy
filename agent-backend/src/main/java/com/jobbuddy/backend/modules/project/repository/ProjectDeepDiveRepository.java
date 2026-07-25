package com.jobbuddy.backend.modules.project.repository;

import com.jobbuddy.backend.modules.project.mapper.ProjectDeepDiveMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

/**
 * 项目深挖数据的仓储适配器。
 *
 * <p>聚合装配集中在此处，避免 Service 重复处理时间、材料预览与持久化细节。
 */
@Repository
public class ProjectDeepDiveRepository {
  private final ProjectDeepDiveMapper mapper;

  /**
   * 创建项目深度分析存储访问实例。
   *
   * @param mapper 数据映射
   */
  public ProjectDeepDiveRepository(ProjectDeepDiveMapper mapper) {
    this.mapper = mapper;
  }

  /**
   * 查询项目列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 项目列表
   */
  public List<Map<String, Object>> listProjects(String tenantId, String userId) {
    List<Map<String, Object>> projects = mapper.listProjects(tenantId, userId);
    for (Map<String, Object> project : projects) {
      normalizeTime(project, "createdAt");
      normalizeTime(project, "updatedAt");
    }
    return projects;
  }

  /**
   * 查找项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @return 项目
   */
  public Map<String, Object> findProject(String tenantId, String userId, String projectId) {
    return hydrateProject(tenantId, userId, mapper.findProject(tenantId, userId, projectId));
  }

  /**
   * 保存项目。
   *
   * @param project 项目
   */
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

  /**
   * 删除项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   */
  public void deleteProject(String tenantId, String userId, String projectId) {
    mapper.deleteProject(tenantId, userId, projectId, Timestamp.from(Instant.now()));
  }

  /**
   * 保存材料。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param material 材料
   */
  public void saveMaterial(String tenantId, String userId, Map<String, Object> material) {
    material.put("createdAt", Timestamp.from(Instant.now()));
    mapper.insertMaterial(material);
    touchProject(tenantId, userId, String.valueOf(material.get("projectId")));
  }

  /**
   * 删除材料。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param materialId 材料标识
   */
  public void deleteMaterial(String tenantId, String userId, String materialId) {
    Map<String, Object> material = findMaterial(tenantId, userId, materialId);
    mapper.deleteMaterial(tenantId, userId, materialId);
    if (material != null) {
      touchProject(tenantId, userId, String.valueOf(material.get("projectId")));
    }
  }

  /**
   * 查找材料。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param materialId 材料标识
   * @return 材料
   */
  public Map<String, Object> findMaterial(String tenantId, String userId, String materialId) {
    Map<String, Object> material = mapper.findMaterial(tenantId, userId, materialId);
    normalizeTime(material, "createdAt");
    return material;
  }

  /**
   * 按 SHA-256 摘要查找材料。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @param sha256 文件 SHA-256 摘要
   * @return 相同 SHA-256 的材料
   */
  public Map<String, Object> findMaterialBySha256(
      String tenantId, String userId, String projectId, String sha256) {
    Map<String, Object> material = mapper.findMaterialBySha256(tenantId, userId, projectId, sha256);
    normalizeTime(material, "createdAt");
    return material;
  }

  /**
   * 查询材料列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @return 材料列表
   */
  public List<Map<String, Object>> listMaterials(String tenantId, String userId, String projectId) {
    List<Map<String, Object>> rows = mapper.listMaterials(tenantId, userId, projectId);
    for (Map<String, Object> row : rows) {
      hydrateMaterial(row);
    }
    return rows;
  }

  /**
   * 替换题目列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @param questions 题目列表
   */
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

  /**
   * 查找题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param questionId 题目标识
   * @return 题目
   */
  public Map<String, Object> findQuestion(String tenantId, String userId, String questionId) {
    Map<String, Object> question = mapper.findQuestion(tenantId, userId, questionId);
    normalizeTime(question, "createdAt");
    return question;
  }

  /**
   * 保存题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param question 题目
   */
  public void saveQuestion(String tenantId, String userId, Map<String, Object> question) {
    question.put("createdAt", Timestamp.from(Instant.now()));
    mapper.insertQuestion(question);
    touchProject(tenantId, userId, String.valueOf(question.get("projectId")));
  }

  /**
   * 更新题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @param question 题目
   */
  public void updateQuestion(
      String tenantId, String userId, String projectId, Map<String, Object> question) {
    question.put("tenantId", tenantId);
    question.put("userId", userId);
    mapper.updateQuestion(question);
    touchProject(tenantId, userId, projectId);
  }

  /**
   * 删除题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param questionId 题目标识
   */
  public void deleteQuestion(String tenantId, String userId, String questionId) {
    Map<String, Object> question = mapper.findQuestion(tenantId, userId, questionId);
    mapper.deleteQuestion(tenantId, userId, questionId);
    if (question != null) {
      touchProject(tenantId, userId, String.valueOf(question.get("projectId")));
    }
  }

  /**
   * 查询题目列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @return 题目列表
   */
  public List<Map<String, Object>> listQuestions(String tenantId, String userId, String projectId) {
    List<Map<String, Object>> rows = mapper.listQuestions(tenantId, userId, projectId);
    for (Map<String, Object> row : rows) {
      normalizeTime(row, "createdAt");
    }
    return rows;
  }

  /**
   * 刷新项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   */
  private void touchProject(String tenantId, String userId, String projectId) {
    mapper.touchProject(tenantId, userId, projectId, Timestamp.from(Instant.now()));
  }

  /**
   * 补全项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param item 数据项
   * @return 补全后的记录
   */
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

  /**
   * 补全材料。
   *
   * @param item 数据项
   */
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

  /**
   * 规范化时间。
   *
   * @param item 数据项
   * @param key 键
   */
  private void normalizeTime(Map<String, Object> item, String key) {
    if (item != null && item.get(key) instanceof Timestamp) {
      item.put(key, ((Timestamp) item.get(key)).toInstant().toString());
    }
  }
}
