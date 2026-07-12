package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.resume.storage.ResumeObjectStorage;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.IOException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

class ResumeObjectStorageTest {

  @Test
  void initShouldCreateBucketWhenMissing() throws Exception {
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.getMinio().setBucket("test-bucket");
    MinioClient minioClient = mock(MinioClient.class);
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(false);

    ResumeObjectStorage storage = new ResumeObjectStorage(properties, minioClient);
    storage.init();

    verify(minioClient).makeBucket(any(MakeBucketArgs.class));
  }

  @Test
  void initShouldKeepExistingBucket() throws Exception {
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.getMinio().setBucket("test-bucket");
    MinioClient minioClient = mock(MinioClient.class);
    when(minioClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);

    ResumeObjectStorage storage = new ResumeObjectStorage(properties, minioClient);
    storage.init();

    verify(minioClient, never()).makeBucket(any(MakeBucketArgs.class));
  }

  @Test
  void uploadShouldFailWithoutCreatingLocalBusinessCopyWhenMinioFails() throws Exception {
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.getMinio().setBucket("test-bucket");
    MinioClient minioClient = mock(MinioClient.class);
    when(minioClient.putObject(any(PutObjectArgs.class)))
        .thenThrow(new RuntimeException("MinIO unreachable"));

    ResumeObjectStorage storage = new ResumeObjectStorage(properties, minioClient);
    MockMultipartFile file =
        new MockMultipartFile("file", "avatar.png", "image/png", new byte[] {1, 2, 3, 4, 5});

    assertThrows(IOException.class, () -> storage.upload(file, "test-user/assets/asset.png"));
  }
}
