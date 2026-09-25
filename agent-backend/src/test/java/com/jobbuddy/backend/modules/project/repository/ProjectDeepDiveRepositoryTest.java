package com.jobbuddy.backend.modules.project.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.modules.project.mapper.ProjectDeepDiveMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ProjectDeepDiveRepositoryTest {
  private final ProjectDeepDiveMapper mapper = mock(ProjectDeepDiveMapper.class);
  private final ProjectDeepDiveRepository repository = new ProjectDeepDiveRepository(mapper);
  private final Instant instant = Instant.parse("2026-01-01T00:00:00Z");

  @Test
  void hydratedProjectRemovesPrivateStorageMetadataAndComputesUtf8Size() {
    var project = row("projectId", "project", "createdAt", Timestamp.from(instant));
    var material =
        row(
            "content",
            "中文",
            "storagePath",
            "private/path",
            "sha256",
            "digest",
            "createdAt",
            Timestamp.from(instant));
    var question = row("questionId", "question", "createdAt", Timestamp.from(instant));
    when(mapper.findProject("tenant", "owner", "project")).thenReturn(project);
    when(mapper.listMaterials("tenant", "owner", "project")).thenReturn(List.of(material));
    when(mapper.listQuestions("tenant", "owner", "project")).thenReturn(List.of(question));
    var result = repository.findProject("tenant", "owner", "project");
    assertEquals(instant.toString(), result.get("createdAt"));
    assertEquals(List.of(material), result.get("materials"));
    assertEquals(6L, material.get("sizeBytes"));
    assertFalse(material.containsKey("content"));
    assertFalse(material.containsKey("storagePath"));
    assertFalse(material.containsKey("sha256"));
    assertEquals(instant.toString(), question.get("createdAt"));
    when(mapper.findProject("tenant", "other", "project")).thenReturn(null);
    assertNull(repository.findProject("tenant", "other", "project"));
  }

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void saveChoosesInsertOrUpdateWithinOwnerScope(boolean exists) {
    var project = row("tenantId", "tenant", "userId", "owner", "projectId", "project");
    when(mapper.countProject("tenant", "owner", "project")).thenReturn(exists ? 1 : 0);
    repository.saveProject(project);
    assertInstanceOf(Timestamp.class, project.get("updatedAt"));
    assertEquals(!exists, project.containsKey("createdAt"));
    verify(mapper, times(exists ? 0 : 1)).insertProject(project);
    verify(mapper, times(exists ? 1 : 0)).updateProject(project);
  }

  @Test
  void questionReplacementDeletesOldSetThenTouchesProject() {
    var question = row("questionId", "question");
    repository.replaceQuestions("tenant", "owner", "project", List.of(question));
    assertEquals("project", question.get("projectId"));
    assertInstanceOf(Timestamp.class, question.get("createdAt"));
    var order = inOrder(mapper);
    order.verify(mapper).deleteQuestions("tenant", "owner", "project");
    order.verify(mapper).insertQuestion(question);
    order.verify(mapper).touchProject(eq("tenant"), eq("owner"), eq("project"), any());
  }

  @Test
  void questionWritesAndDeletesKeepOwnerScope() {
    var question = row("questionId", "question", "projectId", "project");
    when(mapper.findQuestion("tenant", "owner", "question")).thenReturn(question);
    repository.saveQuestion("tenant", "owner", question);
    repository.updateQuestion("tenant", "owner", "project", question);
    assertEquals("tenant", question.get("tenantId"));
    assertEquals("owner", question.get("userId"));
    repository.deleteQuestion("tenant", "owner", "question");
    repository.deleteQuestion("tenant", "owner", "missing");
    verify(mapper, times(3)).touchProject(eq("tenant"), eq("owner"), eq("project"), any());
    assertSame(question, repository.findQuestion("tenant", "owner", "question"));
  }

  @Test
  void materialWritesAndDeletesUpdateParentTimestamp() {
    var material = row("materialId", "material", "projectId", "project");
    repository.saveMaterial("tenant", "owner", material);
    assertInstanceOf(Timestamp.class, material.get("createdAt"));
    when(mapper.findMaterial("tenant", "owner", "material")).thenReturn(material);
    repository.deleteMaterial("tenant", "owner", "material");
    repository.deleteMaterial("tenant", "owner", "missing");
    verify(mapper, times(2)).touchProject(eq("tenant"), eq("owner"), eq("project"), any());
    when(mapper.findMaterialBySha256("tenant", "owner", "project", "digest")).thenReturn(material);
    assertSame(material, repository.findMaterialBySha256("tenant", "owner", "project", "digest"));
  }

  @Test
  void listingAndDeletionRetainTenantFilter() {
    var project = row("projectId", "project", "updatedAt", Timestamp.from(instant));
    when(mapper.listProjects("tenant", "owner")).thenReturn(List.of(project));
    assertEquals(
        instant.toString(), repository.listProjects("tenant", "owner").get(0).get("updatedAt"));
    repository.deleteProject("tenant", "owner", "project");
    verify(mapper).deleteProject(eq("tenant"), eq("owner"), eq("project"), any());
  }

  private Map<String, Object> row(Object... values) {
    Map<String, Object> row = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index += 2)
      row.put((String) values[index], values[index + 1]);
    return row;
  }
}
