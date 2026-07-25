package com.jobbuddy.backend.modules.project.mapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * 项目深挖、资料、会话与版本数据的 MyBatis 访问接口。
 */
@Mapper
public interface ProjectDeepDiveMapper {
  /**
   * 查询项目列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 项目列表
   */
  List<Map<String, Object>> listProjects(
      @Param("tenantId") String tenantId, @Param("userId") String userId);

  /**
   * 查找项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @return 项目
   */
  Map<String, Object> findProject(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId);

  /**
   * 统计项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @return 统计数量
   */
  int countProject(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") Object projectId);

  /**
   * 新增项目。
   *
   * @param project 项目
   * @return 项目
   */
  int insertProject(Map<String, Object> project);

  /**
   * 更新项目。
   *
   * @param project 项目
   * @return 项目
   */
  int updateProject(Map<String, Object> project);

  /**
   * 删除项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @param updatedAt 更新时间
   * @return 项目
   */
  int deleteProject(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId,
      @Param("updatedAt") Timestamp updatedAt);

  /**
   * 新增材料。
   *
   * @param material 材料
   * @return 材料
   */
  int insertMaterial(Map<String, Object> material);

  /**
   * 删除材料。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param materialId 材料标识
   * @return 材料
   */
  int deleteMaterial(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("materialId") String materialId);

  /**
   * 查找材料。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param materialId 材料标识
   * @return 材料
   */
  Map<String, Object> findMaterial(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("materialId") String materialId);

  /**
   * 按 SHA-256 摘要查找材料。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @param sha256 文件 SHA-256 摘要
   * @return 相同 SHA-256 的材料
   */
  Map<String, Object> findMaterialBySha256(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId,
      @Param("sha256") String sha256);

  /**
   * 查询材料列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @return 材料列表
   */
  List<Map<String, Object>> listMaterials(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId);

  /**
   * 删除题目列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @return 删除的题目数
   */
  int deleteQuestions(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId);

  /**
   * 新增题目。
   *
   * @param question 题目
   * @return 题目
   */
  int insertQuestion(Map<String, Object> question);

  /**
   * 查找题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param questionId 题目标识
   * @return 题目
   */
  Map<String, Object> findQuestion(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("questionId") String questionId);

  /**
   * 更新题目。
   *
   * @param question 题目
   * @return 题目
   */
  int updateQuestion(Map<String, Object> question);

  /**
   * 删除题目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param questionId 题目标识
   * @return 题目
   */
  int deleteQuestion(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("questionId") String questionId);

  /**
   * 查询题目列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @return 题目列表
   */
  List<Map<String, Object>> listQuestions(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId);

  /**
   * 刷新项目。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param projectId 项目标识
   * @param updatedAt 更新时间
   * @return 项目
   */
  int touchProject(
      @Param("tenantId") String tenantId,
      @Param("userId") String userId,
      @Param("projectId") String projectId,
      @Param("updatedAt") Timestamp updatedAt);
}
