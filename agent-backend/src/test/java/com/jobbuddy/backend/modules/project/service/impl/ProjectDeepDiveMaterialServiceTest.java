package com.jobbuddy.backend.modules.project.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.modules.project.repository.ProjectDeepDiveRepository;
import com.jobbuddy.backend.modules.project.storage.ProjectMaterialStorage;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class ProjectDeepDiveMaterialServiceTest {

  @Test
  void shouldUploadArbitraryBinaryFileWithoutFormatRestriction() throws Exception {
    ProjectDeepDiveRepository repository = mock(ProjectDeepDiveRepository.class);
    ProjectMaterialStorage storage = mock(ProjectMaterialStorage.class);
    Map<String, Object> project = new LinkedHashMap<String, Object>();
    project.put("projectId", "project-1");
    when(repository.findProject("tenant-1", "user-1", "project-1")).thenReturn(project);
    when(repository.findMaterialBySha256(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq("user-1"),
            org.mockito.ArgumentMatchers.eq("project-1"),
            anyString()))
        .thenReturn(null);
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "source-code.zip", "application/zip", new byte[] {0x50, 0x4b, 0x03, 0x04});
    ProjectDeepDiveServiceImpl service = new ProjectDeepDiveServiceImpl(repository, storage);

    service.addMaterial("tenant-1", "user-1", "project-1", file);

    ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
    verify(repository)
        .saveMaterial(
            org.mockito.ArgumentMatchers.eq("tenant-1"),
            org.mockito.ArgumentMatchers.eq("user-1"),
            captor.capture());
    Map<String, Object> material = captor.getValue();
    assertEquals("source-code.zip", material.get("fileName"));
    assertEquals("application/zip", material.get("contentType"));
    assertEquals(4L, material.get("sizeBytes"));
    verify(storage).upload(file, String.valueOf(material.get("storagePath")), "application/zip");
  }

  @Test
  void shouldRejectEmptyAndOversizedFilesBeforeStorage() throws Exception {
    ProjectDeepDiveRepository repository = mock(ProjectDeepDiveRepository.class);
    ProjectMaterialStorage storage = mock(ProjectMaterialStorage.class);
    when(repository.findProject("tenant-1", "user-1", "project-1"))
        .thenReturn(Map.of("projectId", "project-1"));
    ProjectDeepDiveServiceImpl service = new ProjectDeepDiveServiceImpl(repository, storage);
    MockMultipartFile empty =
        new MockMultipartFile("file", "empty.bin", "application/octet-stream", new byte[0]);
    MultipartFile oversized = mock(MultipartFile.class);
    when(oversized.isEmpty()).thenReturn(false);
    when(oversized.getSize()).thenReturn(ProjectDeepDiveServiceImpl.MAX_MATERIAL_SIZE_BYTES + 1);

    assertThrows(
        IllegalArgumentException.class,
        () -> service.addMaterial("tenant-1", "user-1", "project-1", empty));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.addMaterial("tenant-1", "user-1", "project-1", oversized));
    verify(storage, never()).upload(org.mockito.ArgumentMatchers.any(), anyString(), anyString());
  }

  @Test
  void shouldDeleteStoredObjectAndDatabaseRecord() {
    ProjectDeepDiveRepository repository = mock(ProjectDeepDiveRepository.class);
    ProjectMaterialStorage storage = mock(ProjectMaterialStorage.class);
    Map<String, Object> material = new LinkedHashMap<String, Object>();
    material.put("projectId", "project-1");
    material.put("storagePath", "project-materials/project-1/material-1");
    when(repository.findMaterial("tenant-1", "user-1", "material-1")).thenReturn(material);
    ProjectDeepDiveServiceImpl service = new ProjectDeepDiveServiceImpl(repository, storage);

    service.deleteMaterial("tenant-1", "user-1", "material-1");

    verify(storage).delete("project-materials/project-1/material-1");
    verify(repository).deleteMaterial("tenant-1", "user-1", "material-1");
  }
}
