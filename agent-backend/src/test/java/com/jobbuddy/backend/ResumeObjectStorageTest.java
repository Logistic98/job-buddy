package com.jobbuddy.backend;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.resume.storage.ResumeObjectStorage;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ResumeObjectStorageTest {

    @Test
    void uploadShouldFallBackToLocalCopyWhenMinioFails() throws Exception {
        JobBuddyProperties properties = new JobBuddyProperties();
        properties.getMinio().setBucket("test-bucket");
        MinioClient minioClient = mock(MinioClient.class);
        when(minioClient.putObject(any(PutObjectArgs.class)))
                .thenThrow(new RuntimeException("MinIO unreachable"));

        ResumeObjectStorage storage = new ResumeObjectStorage(properties, minioClient);

        byte[] payload = new byte[]{1, 2, 3, 4, 5};
        MockMultipartFile file = new MockMultipartFile("file", "avatar.png", "image/png", payload);
        String objectName = "test-user/assets/asset_unittest_" + System.nanoTime() + ".png";

        assertDoesNotThrow(() -> storage.upload(file, objectName));

        Path local = localPath(objectName);
        try {
            assertTrue(Files.exists(local), "MinIO 写入失败后应保留本地备份");
            assertArrayEquals(payload, Files.readAllBytes(local));
        } finally {
            Files.deleteIfExists(local);
        }
    }

    private static Path localPath(String objectName) {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path root = cwd.getFileName() != null && "agent-backend".equals(cwd.getFileName().toString())
                ? cwd.getParent()
                : cwd;
        return root.resolve(Paths.get(".run", "resume-originals")).resolve(objectName).normalize();
    }
}
