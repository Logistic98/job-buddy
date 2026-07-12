package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.project.dto.request.ProjectRequest;
import com.jobbuddy.backend.modules.project.repository.ProjectDeepDiveRepository;
import com.jobbuddy.backend.modules.project.service.impl.ProjectDeepDiveServiceImpl;
import com.jobbuddy.backend.modules.project.storage.ProjectMaterialStorage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ProjectDeepDiveServiceImplTest {
  private static final JsonCodec JSON = new JsonCodec();

  @Test
  void saveProjectShouldPersistExtendedOverviewFields() {
    ProjectDeepDiveRepository repository = mock(ProjectDeepDiveRepository.class);
    ProjectMaterialStorage materialStorage = mock(ProjectMaterialStorage.class);
    ProjectDeepDiveServiceImpl service =
        new ProjectDeepDiveServiceImpl(repository, materialStorage);
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("name", "示例平台项目");
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
}
