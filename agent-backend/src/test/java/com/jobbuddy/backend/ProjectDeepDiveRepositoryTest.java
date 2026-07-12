package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.modules.project.mapper.ProjectDeepDiveMapper;
import com.jobbuddy.backend.modules.project.repository.ProjectDeepDiveRepository;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectDeepDiveRepositoryTest {

  @Test
  void projectListShouldLoadOnlySummariesAndCounts() {
    ProjectDeepDiveMapper mapper = mock(ProjectDeepDiveMapper.class);
    List<Map<String, Object>> rows = rows("p1", "p2");
    rows.get(0).put("materialCount", 2L);
    rows.get(0).put("questionCount", 58L);
    when(mapper.listProjects("tenant-1", "user-1")).thenReturn(rows);
    ProjectDeepDiveRepository repository = new ProjectDeepDiveRepository(mapper);

    List<Map<String, Object>> projects = repository.listProjects("tenant-1", "user-1");

    assertEquals(2, projects.size());
    assertEquals(2L, projects.get(0).get("materialCount"));
    assertEquals(58L, projects.get(0).get("questionCount"));
    verify(mapper).listProjects("tenant-1", "user-1");
    verifyNoMoreInteractions(mapper);
  }

  @Test
  void saveQuestionShouldInsertAndTouchProject() {
    ProjectDeepDiveMapper mapper = mock(ProjectDeepDiveMapper.class);
    ProjectDeepDiveRepository repository = new ProjectDeepDiveRepository(mapper);
    Map<String, Object> question = new LinkedHashMap<String, Object>();
    question.put("questionId", "pdq_1");
    question.put("projectId", "p1");
    question.put("question", "手动问题");

    repository.saveQuestion("tenant-1", "user-1", question);

    assertNotNull(question.get("createdAt"));
    verify(mapper).insertQuestion(question);
    verify(mapper).touchProject(eq("tenant-1"), eq("user-1"), eq("p1"), any());
    verifyNoMoreInteractions(mapper);
  }

  @Test
  void updateQuestionShouldScopeByTenantAndTouchProject() {
    ProjectDeepDiveMapper mapper = mock(ProjectDeepDiveMapper.class);
    ProjectDeepDiveRepository repository = new ProjectDeepDiveRepository(mapper);
    Map<String, Object> question = new LinkedHashMap<String, Object>();
    question.put("questionId", "pdq_1");
    question.put("question", "编辑后的问题");

    repository.updateQuestion("tenant-1", "user-1", "p1", question);

    assertEquals("tenant-1", question.get("tenantId"));
    assertEquals("user-1", question.get("userId"));
    verify(mapper).updateQuestion(question);
    verify(mapper).touchProject(eq("tenant-1"), eq("user-1"), eq("p1"), any());
    verifyNoMoreInteractions(mapper);
  }

  @Test
  void deleteQuestionShouldRemoveAndTouchOwningProject() {
    ProjectDeepDiveMapper mapper = mock(ProjectDeepDiveMapper.class);
    Map<String, Object> existing = new LinkedHashMap<String, Object>();
    existing.put("questionId", "pdq_1");
    existing.put("projectId", "p1");
    when(mapper.findQuestion("tenant-1", "user-1", "pdq_1")).thenReturn(existing);
    ProjectDeepDiveRepository repository = new ProjectDeepDiveRepository(mapper);

    repository.deleteQuestion("tenant-1", "user-1", "pdq_1");

    verify(mapper).findQuestion("tenant-1", "user-1", "pdq_1");
    verify(mapper).deleteQuestion("tenant-1", "user-1", "pdq_1");
    verify(mapper).touchProject(eq("tenant-1"), eq("user-1"), eq("p1"), any());
    verifyNoMoreInteractions(mapper);
  }

  @Test
  void deleteQuestionShouldSkipTouchWhenQuestionMissing() {
    ProjectDeepDiveMapper mapper = mock(ProjectDeepDiveMapper.class);
    when(mapper.findQuestion("tenant-1", "user-1", "pdq_missing")).thenReturn(null);
    ProjectDeepDiveRepository repository = new ProjectDeepDiveRepository(mapper);

    repository.deleteQuestion("tenant-1", "user-1", "pdq_missing");

    verify(mapper).findQuestion("tenant-1", "user-1", "pdq_missing");
    verify(mapper).deleteQuestion("tenant-1", "user-1", "pdq_missing");
    verifyNoMoreInteractions(mapper);
  }

  private List<Map<String, Object>> rows(String... projectIds) {
    List<Map<String, Object>> rows = new ArrayList<Map<String, Object>>();
    for (String projectId : projectIds) {
      Map<String, Object> row = new LinkedHashMap<String, Object>();
      row.put("projectId", projectId);
      row.put("content", "material");
      rows.add(row);
    }
    return rows;
  }
}
