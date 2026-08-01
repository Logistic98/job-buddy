package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionGenerateRequest;
import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionImportRequest;
import com.jobbuddy.backend.modules.project.dto.request.ProjectQuestionRequest;
import com.jobbuddy.backend.modules.project.dto.request.ProjectRequest;
import com.jobbuddy.backend.modules.project.dto.response.ProjectQuestionResponse;
import com.jobbuddy.backend.modules.project.dto.response.ProjectResponse;
import com.jobbuddy.backend.modules.project.repository.ProjectDeepDiveRepository;
import com.jobbuddy.backend.modules.project.service.impl.ProjectDeepDiveServiceImpl;
import com.jobbuddy.backend.modules.project.storage.ProjectMaterialStorage;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * 验证 ProjectDeepDiveServiceImpl 的核心行为、异常路径与边界条件。
 */
class ProjectDeepDiveServiceImplTest {
  private static final JsonCodec JSON = new JsonCodec();

  /**
   * 验证 ProjectDeepDiveServiceImpl 中项目的持久化与状态变更规则。
   */
  @Test
  void saveProjectShouldPersistExtendedOverviewFields() {
    ProjectDeepDiveRepository repository = mock(ProjectDeepDiveRepository.class);
    ProjectMaterialStorage materialStorage = mock(ProjectMaterialStorage.class);
    ProjectDeepDiveServiceImpl service =
        new ProjectDeepDiveServiceImpl(repository, materialStorage);
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("name", "示例模型服务平台");
    payload.put("role", "后端负责人");
    payload.put("techStack", "Java, Python, Slurm");
    payload.put("projectPeriod", "2024.03 - 2025.06");
    payload.put("teamSize", "8 人");
    payload.put("projectType", "内部研发平台");
    payload.put("businessDomain", "AI 基础设施");
    payload.put("projectStatus", "持续迭代");
    payload.put("summary", "模型研发与推理一体化平台");
    payload.put("background", "解决研发流程割裂问题");
    payload.put("responsibilities", "负责平台架构与核心服务");
    payload.put("highlights", "统一训练与推理工作流");
    payload.put("challenges", "异构资源调度与任务恢复");
    payload.put("outcomes", "交付周期缩短 40%");
    when(repository.findProject("tenant-1", "user-1", "p1")).thenReturn(payload);

    service.saveProject("tenant-1", "user-1", JSON.convert(payload, ProjectRequest.class), "p1");

    ArgumentCaptor<Map<String, Object>> projectCaptor = ArgumentCaptor.forClass(Map.class);
    verify(repository).saveProject(projectCaptor.capture());
    Map<String, Object> saved = projectCaptor.getValue();
    assertEquals("2024.03 - 2025.06", saved.get("projectPeriod"));
    assertEquals("8 人", saved.get("teamSize"));
    assertEquals("内部研发平台", saved.get("projectType"));
    assertEquals("AI 基础设施", saved.get("businessDomain"));
    assertEquals("持续迭代", saved.get("projectStatus"));
    assertEquals("解决研发流程割裂问题", saved.get("background"));
    assertEquals("负责平台架构与核心服务", saved.get("responsibilities"));
    assertEquals("统一训练与推理工作流", saved.get("highlights"));
    assertEquals("异构资源调度与任务恢复", saved.get("challenges"));
    assertEquals("交付周期缩短 40%", saved.get("outcomes"));
    verify(repository).findProject("tenant-1", "user-1", "p1");
  }

  /**
   * 验证 ProjectDeepDiveServiceImpl 中项目的输入校验与拒绝边界。
   */
  @Test
  void saveProjectShouldDefaultMissingBasicInformationFields() {
    ProjectDeepDiveRepository repository = mock(ProjectDeepDiveRepository.class);
    ProjectMaterialStorage materialStorage = mock(ProjectMaterialStorage.class);
    ProjectDeepDiveServiceImpl service =
        new ProjectDeepDiveServiceImpl(repository, materialStorage);
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("name", "历史项目");
    when(repository.findProject("tenant-1", "user-1", "p2")).thenReturn(payload);

    service.saveProject("tenant-1", "user-1", JSON.convert(payload, ProjectRequest.class), "p2");

    ArgumentCaptor<Map<String, Object>> projectCaptor = ArgumentCaptor.forClass(Map.class);
    verify(repository).saveProject(projectCaptor.capture());
    Map<String, Object> saved = projectCaptor.getValue();
    assertEquals("", saved.get("projectType"));
    assertEquals("", saved.get("businessDomain"));
    assertEquals("", saved.get("projectStatus"));
  }

  /**
   * 验证 ProjectDeepDiveServiceImpl 中题目的持久化与状态变更规则。
   */
  @Test
  void generateQuestionsShouldReturnCandidatesWithoutPersistingThem() {
    ProjectDeepDiveRepository repository = mock(ProjectDeepDiveRepository.class);
    ProjectDeepDiveServiceImpl service =
        new ProjectDeepDiveServiceImpl(repository, mock(ProjectMaterialStorage.class));
    Map<String, Object> project = new LinkedHashMap<String, Object>();
    project.put("name", "Agent 平台");
    when(repository.findProject("tenant-1", "user-1", "p1")).thenReturn(project);
    ProjectQuestionGenerateRequest request = new ProjectQuestionGenerateRequest();
    request.setCount(3);
    request.setRequirements("问题由浅入深，参考答案包含量化结果");

    List<ProjectQuestionResponse> candidates =
        service.generateQuestions("tenant-1", "user-1", "p1", request);

    assertEquals(3, candidates.size());
    assertNull(candidates.get(0).getQuestionId());
    assertEquals(
        List.of("简单", "中等", "困难"),
        candidates.stream().map(ProjectQuestionResponse::getDifficulty).toList());
    candidates.forEach(
        candidate -> assertTrue(candidate.getAnswer().contains("回答约束：问题由浅入深，参考答案包含量化结果")));
    verify(repository, never()).replaceQuestions(anyString(), anyString(), anyString(), anyList());
  }

  /**
   * 验证 ProjectDeepDiveServiceImpl 中题目的输入校验与拒绝边界。
   */
  @Test
  void generateQuestionsShouldRejectOversizedRequirements() {
    ProjectDeepDiveRepository repository = mock(ProjectDeepDiveRepository.class);
    ProjectDeepDiveServiceImpl service =
        new ProjectDeepDiveServiceImpl(repository, mock(ProjectMaterialStorage.class));
    when(repository.findProject("tenant-1", "user-1", "p1"))
        .thenReturn(Map.of("projectId", "p1", "name", "Agent 平台"));
    ProjectQuestionGenerateRequest request = new ProjectQuestionGenerateRequest();
    request.setRequirements("要".repeat(1001));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.generateQuestions("tenant-1", "user-1", "p1", request));

    assertEquals("生成要求不能超过 1000 个字符", error.getMessage());
  }

  /**
   * 验证 ProjectDeepDiveServiceImpl 中题目的持久化与状态变更规则。
   */
  @Test
  void importQuestionsShouldPersistOnlyReviewedCandidates() {
    ProjectDeepDiveRepository repository = mock(ProjectDeepDiveRepository.class);
    ProjectDeepDiveServiceImpl service =
        new ProjectDeepDiveServiceImpl(repository, mock(ProjectMaterialStorage.class));
    when(repository.findProject("tenant-1", "user-1", "p1"))
        .thenReturn(Map.of("projectId", "p1", "name", "Agent 平台"));
    ProjectQuestionRequest question = new ProjectQuestionRequest();
    question.setQuestion("如何设计 Agent Loop？");
    question.setAnswer("围绕上下文、行动和验证形成闭环。");
    question.setCategory("架构设计");
    question.setDifficulty("困难");
    ProjectQuestionImportRequest request = new ProjectQuestionImportRequest();
    request.setQuestions(List.of(question));

    ProjectResponse response = service.importQuestions("tenant-1", "user-1", "p1", request);

    ArgumentCaptor<Map<String, Object>> questionCaptor = ArgumentCaptor.forClass(Map.class);
    verify(repository).saveQuestion(eq("tenant-1"), eq("user-1"), questionCaptor.capture());
    assertEquals("generated", questionCaptor.getValue().get("source"));
    assertEquals("p1", response.getProjectId());
  }
}
