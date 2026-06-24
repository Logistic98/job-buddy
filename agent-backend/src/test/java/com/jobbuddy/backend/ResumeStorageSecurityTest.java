package com.jobbuddy.backend;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.repository.ResumeRecordRepository;
import com.jobbuddy.backend.modules.resume.service.impl.ResumeStorageServiceImpl;
import com.jobbuddy.backend.modules.resume.storage.ResumeObjectStorage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
    void getWithUserAllowsLegacyDefaultUserResume() {
        ResumeRecordRepository repository = mock(ResumeRecordRepository.class);
        ResumeRecord record = record("resume_1", "default-user");
        when(repository.findById("resume_1")).thenReturn(record);
        ResumeStorageServiceImpl service = newService(repository, mock(ResumeObjectStorage.class));

        assertSame(record, service.get("resume_1", "user-auth-1"));
    }

    @Test
    void listIncludesLegacyDefaultUserResumesForAuthenticatedUser() {
        ResumeRecordRepository repository = mock(ResumeRecordRepository.class);
        ResumeRecord legacy = record("resume_legacy", "default-user");
        when(repository.findLatestByUserId("user-auth-1", 50)).thenReturn(Collections.<ResumeRecord>emptyList());
        when(repository.findLatestByUserId("default-user", 50)).thenReturn(Arrays.asList(legacy));
        ResumeStorageServiceImpl service = newService(repository, mock(ResumeObjectStorage.class));

        List<Map<String, Object>> rows = service.list("user-auth-1");

        assertEquals(1, rows.size());
        assertEquals("resume_legacy", rows.get(0).get("resumeId"));
    }

    @Test
    void assetTokenDoesNotExposeObjectNameAndRequiresOwner() throws Exception {
        ResumeObjectStorage objectStorage = mock(ResumeObjectStorage.class);
        when(objectStorage.openObjectStream(anyString())).thenReturn(new ByteArrayInputStream(new byte[] {1, 2, 3}));
        ResumeStorageServiceImpl service = newService(mock(ResumeRecordRepository.class), objectStorage);
        MockMultipartFile file = new MockMultipartFile("file", "photo.png", "image/png", new byte[] {1, 2, 3});

        Map<String, Object> upload = service.uploadAsset(file, "owner-a");
        String url = String.valueOf(upload.get("url"));

        assertFalse(upload.containsKey("objectName"));
        assertFalse(url.contains("owner-a/assets"));
        service.openAsset(url.substring(url.lastIndexOf('/') + 1), "owner-a").close();
        assertThrows(IllegalArgumentException.class, () -> service.openAsset(url.substring(url.lastIndexOf('/') + 1), "owner-b"));
        verify(objectStorage).openObjectStream(anyString());
    }

    @Test
    void rejectedAssetTokenDoesNotOpenObjectStorage() throws Exception {
        ResumeObjectStorage objectStorage = mock(ResumeObjectStorage.class);
        ResumeStorageServiceImpl service = newService(mock(ResumeRecordRepository.class), objectStorage);

        assertThrows(IllegalArgumentException.class, () -> service.openAsset("bad-token", "owner-a"));
        verify(objectStorage, never()).openObjectStream(anyString());
    }

    private ResumeStorageServiceImpl newService(ResumeRecordRepository repository, ResumeObjectStorage objectStorage) {
        JobBuddyProperties properties = new JobBuddyProperties();
        properties.getAuth().setAssetUrlSigningKey("unit-test-signing-key");
        return new ResumeStorageServiceImpl(
                properties,
                mock(RuntimeToolClient.class),
                repository,
                objectStorage,
                mock(BossCliService.class),
                new JsonCodec()
        );
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
