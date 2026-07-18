package com.jobbuddy.backend.modules.project.mapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface ProjectDeepDiveMapper {
  List<Map<String, Object>> listProjects(
      @Param("tenantId") String tenantId, @Param("userId") String userId);

  Map<String, Object> findProject(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId);

  int countProject(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") Object projectId);

  int insertProject(Map<String, Object> project);

  int updateProject(Map<String, Object> project);

  int deleteProject(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId,
      @Param("updatedAt") Timestamp updatedAt);

  int insertMaterial(Map<String, Object> material);

  int deleteMaterial(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("materialId") String materialId);

  Map<String, Object> findMaterial(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("materialId") String materialId);

  Map<String, Object> findMaterialBySha256(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId,
      @Param("sha256") String sha256);

  List<Map<String, Object>> listMaterials(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId);

  int deleteQuestions(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId);

  int insertQuestion(Map<String, Object> question);

  Map<String, Object> findQuestion(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("questionId") String questionId);

  int updateQuestion(Map<String, Object> question);

  int deleteQuestion(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("questionId") String questionId);

  List<Map<String, Object>> listQuestions(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId);

  int touchProject(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId,
      @Param("updatedAt") Timestamp updatedAt);
}
