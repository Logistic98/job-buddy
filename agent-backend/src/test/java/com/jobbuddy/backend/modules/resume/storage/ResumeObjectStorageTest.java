package com.jobbuddy.backend.modules.resume.storage;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
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

  /**
   * 验证 MinIO 流式读取瞬时中断时会清理半成品并做一次有界重试。
   *
   * @param tempDir 临时目录
   * @throws Exception 读取失败时抛出
   */
  @Test
  void downloadShouldRetryAnInterruptedStreamAndReturnCompleteFile(@TempDir Path tempDir)
      throws Exception {
    byte[] expected = new byte[] {1, 2, 3, 4, 5};
    MinioClient minioClient = mock(MinioClient.class);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenReturn(response(failingStream()), response(new ByteArrayInputStream(expected)));
    ResumeObjectStorage storage = storage(minioClient);

    Path downloaded = storage.downloadToTempFile(record(), tempDir.toString());

    assertArrayEquals(expected, Files.readAllBytes(downloaded));
    assertEquals(1L, fileCount(tempDir));
    verify(minioClient, times(2)).getObject(any(GetObjectArgs.class));
  }

  /**
   * 验证两次流式读取都失败时不会在 Runtime 工作区遗留零字节文件。
   *
   * @param tempDir 临时目录
   * @throws Exception 读取失败时抛出
   */
  @Test
  void downloadShouldDeletePartialFileAfterBoundedRetryFails(@TempDir Path tempDir)
      throws Exception {
    MinioClient minioClient = mock(MinioClient.class);
    when(minioClient.getObject(any(GetObjectArgs.class)))
        .thenReturn(response(failingStream()), response(failingStream()));
    ResumeObjectStorage storage = storage(minioClient);

    assertThrows(
        RuntimeException.class, () -> storage.downloadToTempFile(record(), tempDir.toString()));

    assertEquals(0L, fileCount(tempDir));
    verify(minioClient, times(2)).getObject(any(GetObjectArgs.class));
  }

  @Test
  void disabledStartupCheckShouldNotContactMinio() throws Exception {
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.getMinio().setInitializeBucket(false);
    MinioClient client = mock(MinioClient.class);
    new ResumeObjectStorage(properties, client).init();
    verifyNoInteractions(client);
  }

  @Test
  void uploadBytesShouldUseSafeContentTypeAndPreserveObjectIdentity() throws Exception {
    MinioClient client = mock(MinioClient.class);
    when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    ResumeObjectStorage storage = storage(client);
    storage.uploadBytes(null, "empty.txt", "");
    storage.uploadBytes(new byte[] {1, 2}, "image.png", "image/png");
    ArgumentCaptor<PutObjectArgs> uploads = ArgumentCaptor.forClass(PutObjectArgs.class);
    verify(client, times(2)).putObject(uploads.capture());
    assertEquals("test-bucket", uploads.getAllValues().get(0).bucket());
    assertEquals("empty.txt", uploads.getAllValues().get(0).object());
    assertEquals("text/plain", uploads.getAllValues().get(0).contentType());
    assertEquals("image/png", uploads.getAllValues().get(1).contentType());
    assertEquals("test-bucket", storage.bucket());
  }

  @Test
  void uploadShouldDefaultUnknownMimeType() throws Exception {
    MinioClient client = mock(MinioClient.class);
    when(client.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
    storage(client)
        .upload(new MockMultipartFile("file", "resume.bin", null, new byte[] {1}), "resume.bin");
    ArgumentCaptor<PutObjectArgs> args = ArgumentCaptor.forClass(PutObjectArgs.class);
    verify(client).putObject(args.capture());
    assertEquals("application/octet-stream", args.getValue().contentType());
    assertEquals("resume.bin", args.getValue().object());
  }

  @Test
  void storageFailuresShouldPreserveTheirCause() throws Exception {
    MinioClient client = mock(MinioClient.class);
    IOException failure = new IOException("unavailable");
    when(client.bucketExists(any(BucketExistsArgs.class))).thenThrow(failure);
    IOException initError = assertThrows(IOException.class, () -> storage(client).init());
    assertEquals(failure, initError.getCause());
    assertTrue(
        assertThrows(
                IOException.class,
                () -> storage(client).uploadBytes(new byte[] {1}, "file.txt", null))
            .getMessage()
            .contains("上传生成内容"));
    when(client.getObject(any(GetObjectArgs.class))).thenThrow(failure);
    RuntimeException readError =
        assertThrows(RuntimeException.class, () -> storage(client).openObjectStream("file.txt"));
    assertEquals(failure, readError.getCause());
    doThrow(failure).when(client).removeObject(any(RemoveObjectArgs.class));
    RuntimeException deleteError =
        assertThrows(RuntimeException.class, () -> storage(client).deleteObject("file.txt"));
    assertEquals(failure, deleteError.getCause());
  }

  @Test
  void missingObjectIdentityShouldNotCallDelete() {
    MinioClient client = mock(MinioClient.class);
    ResumeObjectStorage storage = storage(client);
    storage.delete(null);
    storage.delete(new ResumeRecord());
    storage.deleteObject(" ");
    assertThrows(IllegalArgumentException.class, () -> storage.openStream(null));
    verifyNoInteractions(client);
  }

  @Test
  void deleteRecordShouldUseStoredObjectPath() throws Exception {
    MinioClient client = mock(MinioClient.class);
    storage(client).delete(record());
    ArgumentCaptor<RemoveObjectArgs> args = ArgumentCaptor.forClass(RemoveObjectArgs.class);
    verify(client).removeObject(args.capture());
    assertEquals("user-1/resume-1.pdf", args.getValue().object());
  }

  @Test
  void downloadWithoutWorkspaceShouldReturnCompleteTemporaryFile() throws Exception {
    MinioClient client = mock(MinioClient.class);
    when(client.getObject(any(GetObjectArgs.class)))
        .thenReturn(response(new ByteArrayInputStream(new byte[] {3, 4})));
    Path path = storage(client).downloadToTempFile(record());
    try {
      assertArrayEquals(new byte[] {3, 4}, Files.readAllBytes(path));
    } finally {
      Files.deleteIfExists(path);
    }
  }

  @Test
  void deterministicDownloadFailureShouldNotRetry(@TempDir Path directory) throws Exception {
    MinioClient client = mock(MinioClient.class);
    when(client.getObject(any(GetObjectArgs.class)))
        .thenThrow(new IllegalArgumentException("invalid object"));
    assertThrows(
        RuntimeException.class,
        () -> storage(client).downloadToTempFile(record(), directory.toString()));
    verify(client).getObject(any(GetObjectArgs.class));
    assertEquals(0, fileCount(directory));
  }

  @Test
  void interruptedDownloadShouldNotRetry(@TempDir Path directory) throws Exception {
    MinioClient client = mock(MinioClient.class);
    when(client.getObject(any(GetObjectArgs.class))).thenThrow(new IOException("interrupted"));
    Thread.currentThread().interrupt();
    try {
      assertThrows(
          RuntimeException.class,
          () -> storage(client).downloadToTempFile(record(), directory.toString()));
      verify(client).getObject(any(GetObjectArgs.class));
    } finally {
      Thread.interrupted();
    }
    assertEquals(0, fileCount(directory));
  }

  private ResumeObjectStorage storage(MinioClient minioClient) {
    JobBuddyProperties properties = new JobBuddyProperties();
    properties.getMinio().setBucket("test-bucket");
    return new ResumeObjectStorage(properties, minioClient);
  }

  private ResumeRecord record() {
    ResumeRecord record = new ResumeRecord();
    record.setResumeId("resume-1");
    record.setStoragePath("user-1/resume-1.pdf");
    record.setSuffix("pdf");
    return record;
  }

  private GetObjectResponse response(InputStream input) {
    return new GetObjectResponse(Headers.of(), "test-bucket", "", "user-1/resume-1.pdf", input);
  }

  private InputStream failingStream() {
    return new InputStream() {
      @Override
      public int read() throws IOException {
        throw new IOException("connection reset");
      }
    };
  }

  private long fileCount(Path directory) throws IOException {
    try (java.util.stream.Stream<Path> files = Files.list(directory)) {
      return files.filter(Files::isRegularFile).count();
    }
  }
}
