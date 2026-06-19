package com.jobbuddy.backend.modules.project.mapper;

import java.sql.Timestamp;
import java.util.List;
import java.util.Map;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

/**
 * MyBatis mapper for project deep-dive materials and generated questions.
 */
@Mapper
public interface ProjectDeepDiveMapper {

    List<Map<String, Object>> listProjects();

    Map<String, Object> findProject(@Param("projectId") String projectId);

    int countProject(@Param("projectId") Object projectId);

    int insertProject(Map<String, Object> project);

    int updateProject(Map<String, Object> project);

    int deleteProject(
            @Param("projectId") String projectId,
            @Param("updatedAt") Timestamp updatedAt);

    int insertMaterial(Map<String, Object> material);

    int deleteMaterial(@Param("materialId") String materialId);

    List<Map<String, Object>> listMaterials(@Param("projectId") String projectId);

    int deleteQuestions(@Param("projectId") String projectId);

    int insertQuestion(Map<String, Object> question);

    List<Map<String, Object>> listQuestions(@Param("projectId") String projectId);

    int touchProject(
            @Param("projectId") String projectId,
            @Param("updatedAt") Timestamp updatedAt);
}
