package com.jobbuddy.backend.modules.resume.service.impl;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeProfileSummaryResponse;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.repository.ResumeRecordRepository;
import com.jobbuddy.backend.modules.resume.storage.ResumeObjectStorage;
import java.io.ByteArrayOutputStream;
import java.time.Duration;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockMultipartFile;

class ResumeStorageServiceImplTest {

  private final JsonCodec jsonCodec = new JsonCodec();
  private final RuntimeToolClient toolClient = mock(RuntimeToolClient.class);
  private final ResumeRecordRepository repository = mock(ResumeRecordRepository.class);
  private final ResumeObjectStorage objectStorage = mock(ResumeObjectStorage.class);
  private final BossCliService bossCliService = mock(BossCliService.class);
  private final ResumeStorageServiceImpl service =
      new ResumeStorageServiceImpl(
          new JobBuddyProperties(),
          toolClient,
          repository,
          objectStorage,
          bossCliService,
          jsonCodec);

  @Test
  void generateJobProfileSummaryReadsBusinessSummaryFromToolOutput() {
    Map<String, Object> output = new LinkedHashMap<String, Object>();
    output.put("summary", "具备五年 Java 与 Python 研发经验，聚焦杭州云原生后端开发岗位，期望月薪 20-30k。");
    output.put("highlights", Arrays.asList("五年研发经验", "云原生后端开发"));
    output.put("missing_fields", Arrays.asList("硬性排除项"));

    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", true);
    toolResult.put("summary", "job_profile_summary 执行成功");
    toolResult.put("output", output);
    when(toolClient.invoke(eq("job_profile_summary"), any(), any(), anyString()))
        .thenReturn(runtimeToolResult(toolResult));

    ResumeProfileSummaryResponse response =
        service.generateJobProfileSummary(profile("当前人工摘要"), "session-1");

    assertEquals("当前人工摘要", response.getOldSummary());
    assertEquals(output.get("summary"), response.getNewSummary());
    assertEquals("五年研发经验", response.getHighlights().get(0).asText());
    assertEquals("硬性排除项", response.getMissingFields().get(0).asText());
    assertEquals("AI", response.getProvider());
  }

  @Test
  void generateJobProfileSummaryFallsBackWhenToolOutputIsInvalid() {
    Map<String, Object> toolResult = new LinkedHashMap<String, Object>();
    toolResult.put("success", true);
    toolResult.put("summary", "job_profile_summary 执行成功");
    when(toolClient.invoke(eq("job_profile_summary"), any(), any(), anyString()))
        .thenReturn(runtimeToolResult(toolResult));

    ResumeProfileSummaryResponse response =
        service.generateJobProfileSummary(profile("当前人工摘要"), "session-1");

    assertEquals("当前人工摘要", response.getOldSummary());
    assertNotEquals("job_profile_summary 执行成功", response.getNewSummary());
    assertEquals("fallback", response.getProvider());
  }

  @Test
  void uploadReadsCurrentResumeSizeLimit() {
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.setMaxResumeBytes(4);
    ResumeStorageServiceImpl sizeLimitedService =
        new ResumeStorageServiceImpl(
            properties,
            toolClient,
            mock(ResumeRecordRepository.class),
            mock(ResumeObjectStorage.class),
            mock(BossCliService.class),
            jsonCodec);
    MockMultipartFile file =
        new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1, 2, 3, 4, 5});

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> sizeLimitedService.upload(file, "tenant-a", "user-a"));

    assertEquals("简历文件超出大小限制: 4 bytes", error.getMessage());
  }

  @Test
  void uploadPrefersExplicitUtf8OriginalNameOverMultipartHeaderName() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile(
            "file", "????????-Java.pdf", "application/pdf", new byte[] {1, 2, 3, 4});

    ResumeRecord record = service.upload(file, "示例候选人-Java开发-求职简历.pdf", "tenant-a", "user-a");

    assertEquals("示例候选人-Java开发-求职简历.pdf", record.getOriginalName());
    assertEquals("pdf", record.getSuffix());
  }

  @Test
  void uploadRemovesClientPathFromExplicitOriginalName() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1, 2, 3, 4});

    ResumeRecord record = service.upload(file, "C:\\fakepath\\中文简历.pdf", "tenant-a", "user-a");

    assertEquals("中文简历.pdf", record.getOriginalName());
  }

  /**
   * 验证上传完成时直接从请求体生成缩略图缓存，不等待列表首次读取再下载原文件。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void uploadPrewarmsThumbnailCacheFromRequestBody() throws Exception {
    StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redisTemplate.opsForValue()).thenReturn(values);
    ResumeStorageServiceImpl thumbnailService =
        new ResumeStorageServiceImpl(
            new JobBuddyProperties(),
            toolClient,
            mock(ResumeRecordRepository.class),
            mock(ResumeObjectStorage.class),
            mock(BossCliService.class),
            jsonCodec,
            redisTemplate);
    MockMultipartFile file =
        new MockMultipartFile("file", "resume.pdf", "application/pdf", onePagePdf());

    ResumeRecord record = thumbnailService.upload(file, "tenant-a", "user-a");

    org.mockito.Mockito.verify(values)
        .set(
            eq("job-buddy:resume-thumbnail:" + record.getResumeId()),
            anyString(),
            eq(Duration.ofHours(24)));
  }

  @Test
  void uploadResolvesTenantFromUserAndFallsBackWhenLookupFails() throws Exception {
    MockMultipartFile file =
        new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1});
    when(repository.findTenantIdByUserId("user")).thenReturn("resolved-tenant");
    assertEquals("resolved-tenant", service.upload(file, null, "user").getTenantId());
    when(repository.findTenantIdByUserId("user"))
        .thenThrow(new IllegalStateException("database unavailable"));
    org.junit.jupiter.api.Assertions.assertNotNull(
        service.upload(file, null, "user").getTenantId());
    org.mockito.Mockito.verify(repository, org.mockito.Mockito.times(2)).save(any());
  }

  @Test
  void uploadRejectsControlCharactersBeforeWritingObjects() {
    MockMultipartFile file =
        new MockMultipartFile("file", "resume.pdf", "application/pdf", new byte[] {1});
    assertThrows(
        IllegalArgumentException.class,
        () -> service.upload(file, "resume\u0001.pdf", "tenant", "user"));
    org.mockito.Mockito.verifyNoInteractions(objectStorage, repository);
  }

  @Test
  void missingOrTenantlessResumeCannotBeReadOrUpdated() {
    org.junit.jupiter.api.Assertions.assertNull(service.get(""));
    assertThrows(IllegalArgumentException.class, () -> service.get("missing", "tenant", "user"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.updateParsed("missing", null, "tenant", "user"));
    ResumeRecord record = new ResumeRecord();
    record.setResumeId("resume");
    record.setUserId("user");
    when(repository.findById("resume")).thenReturn(record);
    assertThrows(IllegalArgumentException.class, () -> service.get("resume", "tenant", "user"));
  }

  @Test
  void originalFileIsReadOnlyAfterOwnerValidation() {
    ResumeRecord record = new ResumeRecord();
    record.setResumeId("resume");
    record.setUserId("user");
    record.setTenantId("tenant");
    when(repository.findById("resume")).thenReturn(record);
    java.io.InputStream content = new java.io.ByteArrayInputStream(new byte[] {1});
    when(objectStorage.openStream(record)).thenReturn(content);
    org.junit.jupiter.api.Assertions.assertSame(
        content, service.openOriginalFile("resume", "tenant", "user"));
    assertThrows(
        IllegalArgumentException.class,
        () -> service.openOriginalFile("resume", "tenant", "other"));
    org.mockito.Mockito.verify(objectStorage).openStream(record);
  }

  @Test
  void profileCreationUsesResolvedTenantAndIgnoresUnrelatedResumes() throws Exception {
    ResumeRecord existing = new ResumeRecord();
    existing.setResumeId("resume");
    existing.setSuffix("pdf");
    when(repository.findLatestByUserId(null, "user", 50)).thenReturn(java.util.List.of(existing));
    when(repository.findTenantIdByUserId("user")).thenReturn("tenant");
    ResumeRecord created = service.getOrCreateJobProfile("user");
    assertEquals("tenant", created.getTenantId());
    assertEquals("user", created.getUserId());
    assertEquals("success", created.getParseStatus());
    org.mockito.Mockito.verify(repository).save(created);
    when(repository.findLatestByUserId(null, "user", 50)).thenReturn(java.util.List.of(created));
    org.junit.jupiter.api.Assertions.assertSame(created, service.getOrCreateJobProfile("user"));
    org.mockito.Mockito.verify(repository).save(any());
  }

  @Test
  void assetDatabaseFailureRemovesUploadedObjectAndPreservesCleanupFailure() {
    com.jobbuddy.backend.modules.resume.mapper.ResumeAssetMapper mapper =
        mock(com.jobbuddy.backend.modules.resume.mapper.ResumeAssetMapper.class);
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "resumeAssetMapper", mapper);
    IllegalStateException failure = new IllegalStateException("database unavailable");
    org.mockito.Mockito.doThrow(failure).when(mapper).insertAsset(any());
    org.mockito.Mockito.doThrow(new IllegalStateException("storage unavailable"))
        .when(objectStorage)
        .deleteObject(anyString());
    MockMultipartFile file = new MockMultipartFile("file", "photo.png", null, new byte[] {1});
    IllegalStateException actual =
        assertThrows(
            IllegalStateException.class, () -> service.uploadAsset(file, "tenant", "user"));
    org.junit.jupiter.api.Assertions.assertSame(failure, actual);
    assertEquals("storage unavailable", actual.getSuppressed()[0].getMessage());
    org.mockito.Mockito.verify(objectStorage)
        .deleteObject(org.mockito.ArgumentMatchers.startsWith("user/assets/"));
  }

  @Test
  void assetLookupRejectsForeignPathsAndDisallowedExtensions() {
    com.jobbuddy.backend.modules.resume.mapper.ResumeAssetMapper mapper =
        mock(com.jobbuddy.backend.modules.resume.mapper.ResumeAssetMapper.class);
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "resumeAssetMapper", mapper);
    when(mapper.findByAssetIdAndUser(any()))
        .thenReturn(Map.of("storagePath", "other/assets/photo.png"));
    assertThrows(
        IllegalArgumentException.class, () -> service.openAsset("asset_0123456789abcdef", "user"));
    when(mapper.findByAssetIdAndUser(any()))
        .thenReturn(Map.of("storagePath", "user/assets/photo.exe"));
    assertThrows(
        IllegalArgumentException.class, () -> service.openAsset("asset_0123456789abcdef", "user"));
    assertEquals("application/octet-stream", service.assetContentType("invalid", "user"));
    org.mockito.Mockito.verifyNoInteractions(objectStorage);
  }

  @Test
  void assetContentTypesFollowAuthorizedStoredExtension() {
    com.jobbuddy.backend.modules.resume.mapper.ResumeAssetMapper mapper =
        mock(com.jobbuddy.backend.modules.resume.mapper.ResumeAssetMapper.class);
    org.springframework.test.util.ReflectionTestUtils.setField(
        service, "resumeAssetMapper", mapper);
    for (String suffix : java.util.List.of("png", "webp", "jpg")) {
      when(mapper.findByAssetIdAndUser(any()))
          .thenReturn(Map.of("storagePath", "user/assets/photo." + suffix));
      assertEquals(
          suffix.equals("jpg") ? "image/jpeg" : "image/" + suffix,
          service.assetContentType("asset_0123456789abcdef", "user"));
    }
  }

  /**
   * 生成最小单页 PDF 测试内容。
   *
   * @return PDF 字节
   * @throws Exception 生成失败时抛出
   */
  private byte[] onePagePdf() throws Exception {
    try (PDDocument document = new PDDocument();
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      document.addPage(new PDPage());
      document.save(output);
      return output.toByteArray();
    }
  }

  /**
   * 验证运行时工具结果。
   *
   * @param value 待处理值
   * @return runtime 工具 Result
   */
  private RuntimeToolResult runtimeToolResult(Map<String, Object> value) {
    return RuntimeToolResult.fromJson(jsonCodec.toTree(value));
  }

  /**
   * 验证画像。
   *
   * @param summary 摘要
   * @return 测试简历画像
   */
  private com.fasterxml.jackson.databind.JsonNode profile(String summary) {
    Map<String, Object> profile = new LinkedHashMap<String, Object>();
    profile.put("summary", summary);
    profile.put("years_experience", "5年");
    profile.put("current_title", "Go 云原生平台开发");
    profile.put("skills", Arrays.asList("Go", "Kubernetes", "PostgreSQL"));

    Map<String, Object> expectations = new LinkedHashMap<String, Object>();
    expectations.put("position", "云原生平台开发岗");
    expectations.put("city", "杭州");
    expectations.put("salary", "25-35k");
    profile.put("job_expectations", expectations);
    return jsonCodec.toTree(profile);
  }
}
