package com.jobbuddy.backend.modules.project.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.modules.project.repository.ProjectDeepDiveRepository;
import com.jobbuddy.backend.modules.project.storage.ProjectMaterialStorage;
import java.io.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

class ProjectMaterialLifecycleTest {
  final ProjectDeepDiveRepository repository = mock(ProjectDeepDiveRepository.class);
  final ProjectMaterialStorage storage = mock(ProjectMaterialStorage.class);
  final ProjectDeepDiveServiceImpl service = new ProjectDeepDiveServiceImpl(repository, storage);
  final MockMultipartFile file =
      new MockMultipartFile("file", "design.txt", "text/plain", "design".getBytes());

  ProjectMaterialLifecycleTest() {
    when(repository.findProject("tenant", "user", "project"))
        .thenReturn(Map.of("projectId", "project"));
    when(repository.findMaterialBySha256(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(null);
  }

  @Test
  void uploadsContentWithHashAndScopedMetadata() throws Exception {
    service.addMaterial("tenant", "user", "project", file);
    ArgumentCaptor<Map> metadata = ArgumentCaptor.forClass(Map.class);
    verify(repository).saveMaterial(eq("tenant"), eq("user"), metadata.capture());
    Map<?, ?> saved = metadata.getValue();
    assertEquals("project", saved.get("projectId"));
    assertEquals("design.txt", saved.get("fileName"));
    assertEquals(6L, saved.get("sizeBytes"));
    assertEquals(64, saved.get("sha256").toString().length());
    verify(storage).upload(file, saved.get("storagePath").toString(), "text/plain");
  }

  @Test
  void duplicateContentDoesNotWriteAnotherObject() throws Exception {
    when(repository.findMaterialBySha256(anyString(), anyString(), anyString(), anyString()))
        .thenReturn(Map.of("materialId", "existing"));
    assertEquals("project", service.addMaterial("tenant", "user", "project", file).getProjectId());
    verifyNoInteractions(storage);
    verify(repository, never()).saveMaterial(any(), any(), any());
  }

  @Test
  void metadataFailureCleansObjectAndPreservesCleanupError() {
    IllegalStateException failure = new IllegalStateException("database unavailable");
    doThrow(failure).when(repository).saveMaterial(any(), any(), any());
    doThrow(new IllegalStateException("cleanup failed")).when(storage).delete(any());
    assertSame(
        failure,
        assertThrows(
            IllegalStateException.class,
            () -> service.addMaterial("tenant", "user", "project", file)));
    assertEquals("cleanup failed", failure.getSuppressed()[0].getMessage());
    verify(storage).delete(startsWith("project-materials/project/"));
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty", "missing", "oversize", "traversal", "unnamed"})
  void rejectsInvalidUploadBeforeStorage(String kind) throws Exception {
    MultipartFile invalid = file;
    switch (kind) {
      case "missing" -> invalid = null;
      case "empty" -> invalid = new MockMultipartFile("file", new byte[0]);
      case "oversize" -> {
        invalid = mock(MultipartFile.class);
        when(invalid.getSize()).thenReturn(ProjectDeepDiveServiceImpl.MAX_MATERIAL_SIZE_BYTES + 1);
      }
      case "traversal" ->
          invalid = new MockMultipartFile("file", "../../private", null, new byte[] {1});
      case "unnamed" -> invalid = new MockMultipartFile("file", "", null, new byte[] {1});
    }
    MultipartFile input = invalid;
    assertThrows(
        IllegalArgumentException.class,
        () -> service.addMaterial("tenant", "user", "project", input));
    verifyNoInteractions(storage);
  }

  @Test
  void missingOwnerScopedProjectPreventsReadingUpload() {
    when(repository.findProject("tenant", "user", "project")).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class,
        () -> service.addMaterial("tenant", "user", "project", file));
    verifyNoInteractions(storage);
  }

  @Test
  void opensOwnedMaterialWithSafeMetadataAndDeletesObjectFirst() {
    when(repository.findMaterial("tenant", "user", "material"))
        .thenReturn(Map.of("storagePath", "object", "sizeBytes", "invalid"));
    InputStream content = new ByteArrayInputStream(new byte[] {1});
    when(storage.open("object")).thenReturn(content);
    var opened = service.openMaterial("tenant", "user", "material");
    assertEquals("application/octet-stream", opened.contentType());
    assertEquals(0, opened.sizeBytes());
    assertSame(content, opened.inputStream());
    service.deleteMaterial("tenant", "user", "material");
    var order = inOrder(storage, repository);
    order.verify(storage).delete("object");
    order.verify(repository).deleteMaterial("tenant", "user", "material");
  }

  @Test
  void missingMaterialAndMissingObjectPathAreDistinctErrors() {
    when(repository.findMaterial("tenant", "user", "material")).thenReturn(null);
    assertThrows(
        IllegalArgumentException.class, () -> service.openMaterial("tenant", "user", "material"));
    when(repository.findMaterial("tenant", "user", "material"))
        .thenReturn(Map.of("fileName", "legacy"));
    assertThrows(
        IllegalStateException.class, () -> service.openMaterial("tenant", "user", "material"));
    service.deleteMaterial("tenant", "user", "material");
    verifyNoInteractions(storage);
    verify(repository).deleteMaterial("tenant", "user", "material");
  }

  @Test
  void objectDeleteFailureKeepsDatabaseMetadata() {
    when(repository.findMaterial("tenant", "user", "material"))
        .thenReturn(Map.of("storagePath", "object"));
    doThrow(new IllegalStateException("storage unavailable")).when(storage).delete("object");
    assertThrows(
        IllegalStateException.class, () -> service.deleteMaterial("tenant", "user", "material"));
    verify(repository, never()).deleteMaterial(any(), any(), any());
  }
}
