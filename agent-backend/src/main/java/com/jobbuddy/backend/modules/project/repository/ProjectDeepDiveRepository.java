package com.jobbuddy.backend.modules.project.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Repository;

import com.jobbuddy.backend.modules.project.mapper.ProjectDeepDiveMapper;

/**
 * Repository adapter for project deep-dive data.
 *
 * <p>Hydration is kept here so services can work with normalized timestamps, material previews,
 * and project aggregates without duplicating persistence details.</p>
 */
@Repository
public class ProjectDeepDiveRepository {
    private final ProjectDeepDiveMapper mapper;

    public ProjectDeepDiveRepository(ProjectDeepDiveMapper mapper) {
        this.mapper = mapper;
    }

    public List<Map<String, Object>> listProjects() {
        List<Map<String, Object>> rows = mapper.listProjects();
        for (Map<String, Object> row : rows) {
            hydrateProject(row);
        }
        return rows;
    }

    public Map<String, Object> findProject(String projectId) {
        return hydrateProject(mapper.findProject(projectId));
    }

    public void saveProject(Map<String, Object> project) {
        Timestamp now = Timestamp.from(Instant.now());
        project.put("updatedAt", now);
        if (mapper.countProject(project.get("projectId")) > 0) {
            mapper.updateProject(project);
        } else {
            project.put("createdAt", now);
            mapper.insertProject(project);
        }
    }

    public void deleteProject(String projectId) {
        mapper.deleteProject(projectId, Timestamp.from(Instant.now()));
    }

    public void saveMaterial(Map<String, Object> material) {
        material.put("createdAt", Timestamp.from(Instant.now()));
        mapper.insertMaterial(material);
        touchProject(String.valueOf(material.get("projectId")));
    }

    public void deleteMaterial(String materialId) {
        mapper.deleteMaterial(materialId);
    }

    public List<Map<String, Object>> listMaterials(String projectId) {
        List<Map<String, Object>> rows = mapper.listMaterials(projectId);
        for (Map<String, Object> row : rows) {
            hydrateMaterial(row);
        }
        return rows;
    }

    public void replaceQuestions(String projectId, List<Map<String, Object>> questions) {
        mapper.deleteQuestions(projectId);
        Timestamp now = Timestamp.from(Instant.now());
        for (Map<String, Object> question : questions) {
            question.put("projectId", projectId);
            question.put("createdAt", now);
            mapper.insertQuestion(question);
        }
        touchProject(projectId);
    }

    public List<Map<String, Object>> listQuestions(String projectId) {
        List<Map<String, Object>> rows = mapper.listQuestions(projectId);
        for (Map<String, Object> row : rows) {
            normalizeTime(row, "createdAt");
        }
        return rows;
    }

    private void touchProject(String projectId) {
        mapper.touchProject(projectId, Timestamp.from(Instant.now()));
    }

    private Map<String, Object> hydrateProject(Map<String, Object> item) {
        if (item == null) {
            return null;
        }

        normalizeTime(item, "createdAt");
        normalizeTime(item, "updatedAt");
        item.put("materials", listMaterials(String.valueOf(item.get("projectId"))));
        item.put("questions", listQuestions(String.valueOf(item.get("projectId"))));
        return item;
    }

    private void hydrateMaterial(Map<String, Object> item) {
        normalizeTime(item, "createdAt");
        String content = item.get("content") == null ? "" : String.valueOf(item.get("content"));
        item.put("preview", content.substring(0, Math.min(180, content.length())));
    }

    private void normalizeTime(Map<String, Object> item, String key) {
        if (item != null && item.get(key) instanceof Timestamp) {
            item.put(key, ((Timestamp) item.get(key)).toInstant().toString());
        }
    }
}
