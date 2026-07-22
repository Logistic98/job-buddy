package com.jobbuddy.backend.modules.resume.storage;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class ResumeObjectStorage {

  private static final Logger LOG = LoggerFactory.getLogger(ResumeObjectStorage.class);

  private final JobBuddyProperties properties;
  private final MinioClient minioClient;

  public ResumeObjectStorage(JobBuddyProperties properties, MinioClient minioClient) {
    this.properties = properties;
    this.minioClient = minioClient;
  }

  @PostConstruct
  public void init() throws IOException {
    if (!properties.getMinio().isInitializeBucket()) {
      LOG.info("已跳过启动期简历对象存储检查 - bucket: {}", properties.getMinio().getBucket());
      return;
    }
    ensureBucketExists();
    LOG.info("简历对象存储已就绪 - bucket: {}", properties.getMinio().getBucket());
  }

  public void upload(MultipartFile file, String objectName) throws IOException {
    try {
      ensureBucketExists();
      try (InputStream input = file.getInputStream()) {
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(properties.getMinio().getBucket())
                .object(objectName)
                .contentType(
                    file.getContentType() == null
                        ? "application/octet-stream"
                        : file.getContentType())
                .stream(input, file.getSize(), -1)
                .build());
      }
    } catch (Exception e) {
      throw new IOException("上传对象到 MinIO 失败: " + objectName, e);
    }
  }

  public void uploadBytes(byte[] content, String objectName, String contentType)
      throws IOException {
    byte[] safeContent = content == null ? new byte[0] : content;
    try (InputStream input = new java.io.ByteArrayInputStream(safeContent)) {
      ensureBucketExists();
      minioClient.putObject(
          PutObjectArgs.builder()
              .bucket(properties.getMinio().getBucket())
              .object(objectName)
              .contentType(
                  contentType == null || contentType.isEmpty() ? "text/plain" : contentType)
              .stream(input, safeContent.length, -1)
              .build());
    } catch (Exception e) {
      throw new IOException("上传生成内容到 MinIO 失败: " + objectName, e);
    }
  }

  public InputStream openStream(ResumeRecord record) {
    if (record == null) throw new IllegalArgumentException("记录不能为空");
    return openObjectStream(record.getStoragePath());
  }

  public InputStream openObjectStream(String objectName) {
    try {
      return minioClient.getObject(
          GetObjectArgs.builder()
              .bucket(properties.getMinio().getBucket())
              .object(objectName)
              .build());
    } catch (Exception e) {
      throw new RuntimeException("MinIO 对象不存在或不可读: " + objectName, e);
    }
  }

  public Path downloadToTempFile(ResumeRecord record) {
    return downloadToTempFile(record, null);
  }

  public void delete(ResumeRecord record) {
    if (record == null || record.getStoragePath() == null) return;
    deleteObject(record.getStoragePath());
  }

  public void deleteObject(String objectName) {
    if (objectName == null || objectName.trim().isEmpty()) return;
    try {
      minioClient.removeObject(
          RemoveObjectArgs.builder()
              .bucket(properties.getMinio().getBucket())
              .object(objectName)
              .build());
    } catch (Exception e) {
      throw new RuntimeException("删除 MinIO 对象失败: " + objectName, e);
    }
  }

  public Path downloadToTempFile(ResumeRecord record, String workspaceDir) {
    try {
      Path dir = workspaceDir == null || workspaceDir.isEmpty() ? null : Paths.get(workspaceDir);
      if (dir != null) Files.createDirectories(dir);
      Path tempFile =
          dir == null
              ? Files.createTempFile("job-buddy-resume-", "." + record.getSuffix())
              : Files.createTempFile(dir, "job-buddy-resume-", "." + record.getSuffix());
      try (InputStream input = openStream(record)) {
        Files.copy(input, tempFile, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
      }
      return tempFile;
    } catch (Exception e) {
      throw new RuntimeException("从 MinIO 下载简历失败: " + record.getStoragePath(), e);
    }
  }

  public String bucket() {
    return properties.getMinio().getBucket();
  }

  private void ensureBucketExists() throws IOException {
    try {
      String bucket = properties.getMinio().getBucket();
      boolean exists = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());
      if (!exists) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      }
    } catch (Exception e) {
      throw new IOException("确认 MinIO bucket 失败", e);
    }
  }
}
