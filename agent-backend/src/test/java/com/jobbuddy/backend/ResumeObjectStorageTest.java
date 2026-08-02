package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.storage.ResumeObjectStorage;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import okhttp3.Headers;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.web.MockMultipartFile;

/**
 * 验证 ResumeObjectStorage 的核心行为、异常路径与边界条件。
 */
class ResumeObjectStorageTest {

  /**
   * 验证 ResumeObjectStorage 的输入校验与拒绝边界。
   *
   * @throws Exception 处理失败时抛出
   */
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

  /**
   * 验证 ResumeObjectStorage 的核心业务契约。
   *
   * @throws Exception 处理失败时抛出
   */
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

  /**
   * 验证 ResumeObjectStorage 的输入校验与拒绝边界。
   *
   * @throws Exception 处理失败时抛出
   */
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
