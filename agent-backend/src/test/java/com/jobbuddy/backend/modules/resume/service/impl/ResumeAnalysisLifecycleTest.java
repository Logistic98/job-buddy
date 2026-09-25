package com.jobbuddy.backend.modules.resume.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.analysis.dto.AnalysisPartialResult;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.repository.ResumeRecordRepository;
import com.jobbuddy.backend.modules.resume.storage.ResumeObjectStorage;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class ResumeAnalysisLifecycleTest {
  @TempDir Path directory;
  private final JsonCodec json = new JsonCodec();
  private final RuntimeToolClient tools = mock(RuntimeToolClient.class);
  private final ResumeRecordRepository repository = mock(ResumeRecordRepository.class);
  private final ResumeObjectStorage storage = mock(ResumeObjectStorage.class);
  private final BossCliService boss = mock(BossCliService.class);
  private ResumeStorageServiceImpl service;
  private ResumeRecord record;
  private Path downloaded;

  @BeforeEach
  void setup() throws Exception {
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setResumeRuntimeWorkspace(directory.toString());
    service = new ResumeStorageServiceImpl(properties, tools, repository, storage, boss, json);
    record = new ResumeRecord();
    record.setResumeId("resume");
    record.setTenantId("tenant");
    record.setUserId("owner");
    record.setSuffix("pdf");
    record.setParsed(Map.of("folder", "saved-folder"));
    downloaded = Files.writeString(directory.resolve("resume.pdf"), "synthetic test file");
    when(repository.findById("resume")).thenReturn(record);
    when(storage.downloadToTempFile(eq(record), anyString())).thenReturn(downloaded);
    when(tools.invoke(eq("resume_parse"), any(), any(), anyString()))
        .thenReturn(result(Map.of("resume", Map.of("name", "candidate"))));
    when(tools.invoke(eq("resume_analyze"), any(), any(), anyString()))
        .thenReturn(result(Map.of("analysis", Map.of("summary", "verified"))));
  }

  @ParameterizedTest
  @ValueSource(strings = {"parse", "analyze", "incremental"})
  void analysisPersistsResultsAndDeletesTemporaryFile(String mode) {
    List<AnalysisPartialResult> partials = new ArrayList<>();
    ResumeRecord result = run(mode, partials);
    assertSame(record, result);
    assertEquals("success", result.getParseStatus());
    assertNull(result.getParseError());
    assertEquals("saved-folder", result.getParsed().get("folder"));
    assertEquals(Map.of("summary", "verified"), result.getParsed().get("analysis"));
    assertFalse(Files.exists(downloaded));
    verify(tools).invoke(eq("resume_parse"), any(), any(), eq(directory.toString()));
    verify(tools, times(mode.equals("incremental") ? 3 : 1))
        .invoke(eq("resume_analyze"), any(), any(), anyString());
    if (mode.equals("incremental")) {
      assertEquals(
          List.of("overview", "content", "actions"),
          partials.stream().map(AnalysisPartialResult::getSection).toList());
      assertTrue(partials.get(2).getPayload().toString().contains("verified"));
    }
  }

  @ParameterizedTest
  @ValueSource(strings = {"parse", "analyze", "incremental"})
  void toolFailurePersistsErrorAndStillDeletesTemporaryFile(String mode) {
    when(tools.invoke(eq("resume_analyze"), any(), any(), anyString()))
        .thenThrow(new IllegalStateException("model timeout"));
    assertThrows(IllegalStateException.class, () -> run(mode, new ArrayList<>()));
    assertEquals("fail", record.getParseStatus());
    assertEquals("model timeout", record.getParseError());
    verify(repository, atLeastOnce()).save(record);
    assertFalse(Files.exists(downloaded));
  }

  @Test
  void alreadyParsedRecordAvoidsDownloadingOrCallingTools() {
    record.setParsed(Map.of("name", "candidate"));
    record.setParseStatus("success");
    assertSame(record, service.parseSync("resume", "session", "tenant", "owner"));
    verifyNoInteractions(storage, tools);
    assertTrue(Files.exists(downloaded));
  }

  @Test
  void analysisReusesParsedContentWithoutCallingParser() {
    record.setParsed(Map.of("name", "candidate"));
    service.analyzeSync("resume", "session", "tenant", "owner");
    verify(tools, never()).invoke(eq("resume_parse"), any(), any(), anyString());
    assertEquals("candidate", record.getParsed().get("name"));
  }

  @Test
  void missingAndUnsupportedFilesAreRejectedBeforeDownload() {
    assertThrows(IllegalArgumentException.class, () -> service.parseSync("missing", "session"));
    assertThrows(IllegalArgumentException.class, () -> service.analyzeSync("missing", "session"));
    record.setSuffix("txt");
    assertThrows(IllegalArgumentException.class, () -> service.analyzeSync("resume", "session"));
    verifyNoInteractions(storage, tools);
  }

  @Test
  void manualProfileIsStoredWithSourceAndCanBeReused() throws Exception {
    when(repository.findLatestByUserId(isNull(), eq("owner"), anyInt())).thenReturn(List.of());
    ResumeRecord created =
        service.saveJobProfile("tenant", "owner", json.toTree(Map.of("name", "candidate")));
    assertEquals("tenant", created.getTenantId());
    assertEquals("owner", created.getUserId());
    assertEquals("candidate", created.getParsed().get("name"));
    assertEquals("success", created.getParseStatus());
    verify(storage)
        .uploadBytes(
            any(byte[].class), startsWith("owner/profile_"), eq("text/plain; charset=utf-8"));
    when(repository.findLatestByUserId(isNull(), eq("owner"), anyInt()))
        .thenReturn(List.of(created));
    assertSame(created, service.getOrCreateJobProfile("owner"));
    assertEquals(created.getResumeId(), service.getJobProfileOrEmpty("owner").getResumeId());
  }

  @Test
  void emptyProfileIsDraftWithoutWritingUserData() {
    assertEquals("draft", service.getJobProfileOrEmpty("tenant", "owner").getParseStatus());
    verify(repository, never()).save(any());
    verifyNoInteractions(storage, tools, boss);
  }

  private ResumeRecord run(String mode, List<AnalysisPartialResult> partials) {
    return switch (mode) {
      case "parse" -> service.parseSync("resume", "session", "tenant", "owner");
      case "analyze" -> service.analyzeSync("resume", "session", "tenant", "owner");
      case "incremental" ->
          service.analyzeIncrementally("resume", "session", "tenant", "owner", partials::add);
      default -> throw new IllegalArgumentException(mode);
    };
  }

  @Test
  void thumbnailRendersPdfAndRemovesDownloadedFile() throws Exception {
    try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
      document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
      document.save(downloaded.toFile());
    }
    byte[] png = service.thumbnail("resume", "tenant", "owner");
    var image = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
    assertEquals(260, image.getWidth());
    assertFalse(Files.exists(downloaded));
  }

  @Test
  void invalidPdfReturnsPlaceholderAndStillRemovesTemporaryFile() throws Exception {
    byte[] png = service.thumbnail("resume", "tenant", "owner");
    assertNotNull(javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png)));
    assertFalse(Files.exists(downloaded));
  }

  @Test
  void thumbnailCacheAvoidsDownloadingAndCorruptCacheDegradesSafely() throws Exception {
    var redis = mock(org.springframework.data.redis.core.StringRedisTemplate.class);
    org.springframework.data.redis.core.ValueOperations<String, String> values =
        mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    var properties = new JobBuddyProperties();
    properties.setResumeRuntimeWorkspace(directory.toString());
    service =
        new ResumeStorageServiceImpl(properties, tools, repository, storage, boss, json, redis);
    when(values.get("job-buddy:resume-thumbnail:resume"))
        .thenReturn(java.util.Base64.getEncoder().encodeToString(new byte[] {1, 2}));
    assertArrayEquals(new byte[] {1, 2}, service.thumbnail("resume", "tenant", "owner"));
    verifyNoInteractions(storage);
    when(values.get(anyString())).thenReturn("!invalid-base64");
    try (var document = new org.apache.pdfbox.pdmodel.PDDocument()) {
      document.addPage(new org.apache.pdfbox.pdmodel.PDPage());
      document.save(downloaded.toFile());
    }
    byte[] rendered = service.thumbnail("resume", "tenant", "owner");
    verify(values)
        .set(
            eq("job-buddy:resume-thumbnail:resume"),
            eq(java.util.Base64.getEncoder().encodeToString(rendered)),
            any(java.time.Duration.class));
    assertFalse(Files.exists(downloaded));
  }

  @Test
  void parsedContentUpdatesStatusWhileFolderMetadataPreservesFailure() {
    record.setParseStatus("fail");
    record.setParseError("parser failed");
    service.updateParsed("resume", json.toTree(Map.of("folder", "new")), "tenant", "owner");
    assertEquals("fail", record.getParseStatus());
    assertEquals("parser failed", record.getParseError());
    service.updateParsed("resume", json.toTree(Map.of("name", "Candidate")), "tenant", "owner");
    assertEquals("success", record.getParseStatus());
    assertNull(record.getParseError());
    verify(repository, times(2)).save(record);
  }

  @Test
  void deletingResumeRemovesObjectBeforeMetadataAndPreservesMetadataOnFailure() {
    doThrow(new IllegalStateException("unavailable")).when(storage).delete(record);
    assertThrows(IllegalStateException.class, () -> service.delete("resume", "tenant", "owner"));
    verify(repository, never()).deleteById(anyString());
    doNothing().when(storage).delete(record);
    service.delete("resume", "tenant", "owner");
    var order = inOrder(storage, repository);
    order.verify(storage, times(2)).delete(record);
    order.verify(repository).deleteById("resume");
  }

  private RuntimeToolResult result(Map<String, Object> output) {
    return json.convert(Map.of("success", true, "output", output), RuntimeToolResult.class);
  }
}
