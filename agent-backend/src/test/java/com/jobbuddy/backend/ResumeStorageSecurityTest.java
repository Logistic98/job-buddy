package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeAssetUploadResponse;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeSummaryResponse;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.mapper.ResumeAssetMapper;
import com.jobbuddy.backend.modules.resume.repository.ResumeRecordRepository;
import com.jobbuddy.backend.modules.resume.service.impl.ResumeStorageServiceImpl;
import com.jobbuddy.backend.modules.resume.storage.ResumeObjectStorage;
import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.util.ReflectionTestUtils;

class ResumeStorageSecurityTest {
  @Test
  void getWithUserRejectsOtherOwnersResume() {
    ResumeRecordRepository repository = mock(ResumeRecordRepository.class);
    ResumeRecord record = record("resume_1", "owner-a");
    when(repository.findById("resume_1")).thenReturn(record);
    ResumeStorageServiceImpl service = newService(repository, mock(ResumeObjectStorage.class));

    assertThrows(IllegalArgumentException.class, () -> service.get("resume_1", "owner-b"));
  }

  @Test
  void getWithTenantRejectsCrossTenantResume() {
    ResumeRecordRepository repository = mock(ResumeRecordRepository.class);
    ResumeRecord record = record("resume_1", "owner-a");
    record.setTenantId("tenant-a");
    when(repository.findById("resume_1")).thenReturn(record);
    ResumeStorageServiceImpl service = newService(repository, mock(ResumeObjectStorage.class));

    assertThrows(
        IllegalArgumentException.class, () -> service.get("resume_1", "tenant-b", "owner-a"));
  }

  @Test
  void getWithMatchingTenantAndOwnerSucceeds() {
    ResumeRecordRepository repository = mock(ResumeRecordRepository.class);
    ResumeRecord record = record("resume_1", "owner-a");
    record.setTenantId("tenant-a");
    when(repository.findById("resume_1")).thenReturn(record);
    ResumeStorageServiceImpl service = newService(repository, mock(ResumeObjectStorage.class));

    assertEquals("resume_1", service.get("resume_1", "tenant-a", "owner-a").getResumeId());
  }

  @Test
  void getWithUserRejectsLocalDefaultUserResume() {
    ResumeRecordRepository repository = mock(ResumeRecordRepository.class);
    ResumeRecord record = record("resume_1", "default-user");
    when(repository.findById("resume_1")).thenReturn(record);
    ResumeStorageServiceImpl service = newService(repository, mock(ResumeObjectStorage.class));

    assertThrows(IllegalArgumentException.class, () -> service.get("resume_1", "user-auth-1"));
  }

  @Test
  void listDoesNotIncludeLocalDefaultUserResumesForAuthenticatedUser() {
    ResumeRecordRepository repository = mock(ResumeRecordRepository.class);
    Map<String, Object> localResume = new java.util.LinkedHashMap<String, Object>();
    localResume.put("resumeId", "resume_local");
    localResume.put("userId", "default-user");
    when(repository.findLatestSummariesByUserId(null, "user-auth-1", 50))
        .thenReturn(Collections.<Map<String, Object>>emptyList());
    when(repository.findLatestSummariesByUserId(null, "default-user", 50))
        .thenReturn(Arrays.asList(localResume));
    ResumeStorageServiceImpl service = newService(repository, mock(ResumeObjectStorage.class));

    List<ResumeSummaryResponse> rows = service.list("user-auth-1");

    assertEquals(0, rows.size());
    verify(repository, never()).findLatestSummariesByUserId(null, "default-user", 50);
  }

  @Test
  void listPreservesResumeManagementMetadata() {
    ResumeRecordRepository repository = mock(ResumeRecordRepository.class);
    Map<String, Object> parsed = new java.util.LinkedHashMap<String, Object>();
    parsed.put("folder", "后端");
    parsed.put("resumeFolder", "后端");
    Map<String, Object> summary = new java.util.LinkedHashMap<String, Object>();
    summary.put("resumeId", "resume_1");
    summary.put("parsed", parsed);
    when(repository.findLatestSummariesByUserId("tenant-a", "owner-a", 50))
        .thenReturn(Collections.singletonList(summary));
    ResumeStorageServiceImpl service = newService(repository, mock(ResumeObjectStorage.class));

    List<ResumeSummaryResponse> rows = service.list("tenant-a", "owner-a");

    assertEquals("后端", rows.get(0).getParsed().get("folder").asText());
  }

  @Test
  void analyzeRejectsNonPdfResume() {
    ResumeRecordRepository repository = mock(ResumeRecordRepository.class);
    ResumeRecord record = record("resume_md", "owner-a");
    record.setTenantId("tenant-a");
    record.setSuffix("md");
    when(repository.findById("resume_md")).thenReturn(record);
    ResumeStorageServiceImpl service = newService(repository, mock(ResumeObjectStorage.class));

    IllegalArgumentException error =
        assertThrows(
            IllegalArgumentException.class,
            () -> service.analyzeSync("resume_md", "session-1", "tenant-a", "owner-a"));

    assertEquals("简历分析仅支持 PDF 格式", error.getMessage());
  }

  @Test
  void assetUploadsUseUniqueNamesWithinOwnerNamespace() throws Exception {
    ResumeObjectStorage objectStorage = mock(ResumeObjectStorage.class);
    ResumeAssetMapper assetMapper = mock(ResumeAssetMapper.class);
    ResumeStorageServiceImpl service =
        newService(mock(ResumeRecordRepository.class), objectStorage);
    ReflectionTestUtils.setField(service, "resumeAssetMapper", assetMapper);
    MockMultipartFile file =
        new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1, 2, 3});

    ResumeAssetUploadResponse first = service.uploadAsset(file, "tenant-a", "owner-a");
    ResumeAssetUploadResponse second = service.uploadAsset(file, "tenant-a", "owner-a");
    ResumeAssetUploadResponse otherOwner = service.uploadAsset(file, "tenant-a", "owner-b");

    assertNotEquals(first.getAssetId(), second.getAssetId());
    assertNotEquals(first.getAssetId(), otherOwner.getAssetId());
    ArgumentCaptor<String> objectNameCaptor = ArgumentCaptor.forClass(String.class);
    verify(objectStorage, times(3))
        .uploadBytes(any(byte[].class), objectNameCaptor.capture(), anyString());
    List<String> objectNames = objectNameCaptor.getAllValues();
    assertTrue(objectNames.get(0).matches("owner-a/assets/asset_[a-f0-9]{16}\\.png"));
    assertTrue(objectNames.get(1).matches("owner-a/assets/asset_[a-f0-9]{16}\\.png"));
    assertTrue(objectNames.get(2).matches("owner-b/assets/asset_[a-f0-9]{16}\\.png"));
    assertNotEquals(objectNames.get(0), objectNames.get(1));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<Map<String, Object>> assetCaptor = ArgumentCaptor.forClass(Map.class);
    verify(assetMapper, times(3)).insertAsset(assetCaptor.capture());
    assertEquals("photo.png", assetCaptor.getAllValues().get(0).get("fileName"));
    assertEquals("owner-a", assetCaptor.getAllValues().get(0).get("userId"));
    assertEquals("owner-b", assetCaptor.getAllValues().get(2).get("userId"));
  }

  @Test
  void assetIdUrlDoesNotExpireAndRequiresOwner() throws Exception {
    ResumeObjectStorage objectStorage = mock(ResumeObjectStorage.class);
    ResumeAssetMapper assetMapper = mock(ResumeAssetMapper.class);
    when(objectStorage.openObjectStream(anyString()))
        .thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
    ResumeStorageServiceImpl service =
        newService(mock(ResumeRecordRepository.class), objectStorage);
    ReflectionTestUtils.setField(service, "resumeAssetMapper", assetMapper);
    MockMultipartFile file =
        new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1, 2, 3});

    ResumeAssetUploadResponse upload = service.uploadAsset(file, "tenant-a", "owner-a");
    String url = upload.getUrl();
    String assetId = upload.getAssetId();
    Map<String, Object> storedAsset = new LinkedHashMap<String, Object>();
    storedAsset.put("assetId", assetId);
    storedAsset.put("storagePath", "owner-a/assets/" + assetId + ".png");
    storedAsset.put("contentType", "image/png");
    storedAsset.put("sizeBytes", Long.valueOf(3L));
    when(assetMapper.findByAssetIdAndUser(
            org.mockito.ArgumentMatchers.argThat(query -> "owner-a".equals(query.get("userId")))))
        .thenReturn(storedAsset);

    assertFalse(url.contains("owner-a/assets"));
    assertEquals("/api/resume/assets/" + assetId, url);
    service.openAsset(assetId, "owner-a").close();
    assertThrows(IllegalArgumentException.class, () -> service.openAsset(assetId, "owner-b"));
    verify(objectStorage).openObjectStream("owner-a/assets/" + assetId + ".png");
  }

  @Test
  void rejectedAssetTokenDoesNotOpenObjectStorage() throws Exception {
    ResumeObjectStorage objectStorage = mock(ResumeObjectStorage.class);
    ResumeStorageServiceImpl service =
        newService(mock(ResumeRecordRepository.class), objectStorage);

    assertThrows(IllegalArgumentException.class, () -> service.openAsset("bad-token", "owner-a"));
    verify(objectStorage, never()).openObjectStream(anyString());
  }

  private ResumeStorageServiceImpl newService(
      ResumeRecordRepository repository, ResumeObjectStorage objectStorage) {
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.getAuth().setAssetUrlSigningKey("unit-test-signing-key");
    return new ResumeStorageServiceImpl(
        properties,
        mock(RuntimeToolClient.class),
        repository,
        objectStorage,
        mock(BossCliService.class),
        new JsonCodec());
  }

  private ResumeRecord record(String resumeId, String userId) {
    ResumeRecord record = new ResumeRecord();
    record.setResumeId(resumeId);
    record.setUserId(userId);
    record.setStoragePath(userId + "/" + resumeId + ".pdf");
    record.setOriginalName("resume.pdf");
    record.setSuffix("pdf");
    return record;
  }
}
