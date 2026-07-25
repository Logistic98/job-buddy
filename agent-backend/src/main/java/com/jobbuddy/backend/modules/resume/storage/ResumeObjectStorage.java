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

/**
 * 封装简历文件的 MinIO 上传、读取、删除与临时文件下载。
 *
 * <p>返回的对象流由调用方关闭；临时文件的生命周期由创建它的业务流程负责。
 */
@Component
public class ResumeObjectStorage {

  private static final Logger LOG = LoggerFactory.getLogger(ResumeObjectStorage.class);

  private final JobBuddyProperties properties;
  private final MinioClient minioClient;

  /**
   * 创建简历对象存储实例。
   *
   * @param properties 配置属性
   * @param minioClient MinIO 客户端
   */
  public ResumeObjectStorage(JobBuddyProperties properties, MinioClient minioClient) {
    this.properties = properties;
    this.minioClient = minioClient;
  }

  /**
   * 初始化对象存储桶。
   *
   * @throws IOException 文件读写失败时抛出
   */
  @PostConstruct
  public void init() throws IOException {
    if (!properties.getMinio().isInitializeBucket()) {
      LOG.info("已跳过启动期简历对象存储检查 - bucket: {}", properties.getMinio().getBucket());
      return;
    }
    ensureBucketExists();
    LOG.info("简历对象存储已就绪 - bucket: {}", properties.getMinio().getBucket());
  }

  /**
   * 上传简历对象存储。
   *
   * @param file 上传文件
   * @param objectName 对象名称
   * @throws IOException 文件读写失败时抛出
   */
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

  /**
   * 上传字节。
   *
   * @param content 内容
   * @param objectName 对象名称
   * @param contentType 内容类型
   * @throws IOException 文件读写失败时抛出
   */
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

  /**
   * 打开流式响应。
   *
   * @param record 记录
   * @return 打开流式响应
   */
  public InputStream openStream(ResumeRecord record) {
    if (record == null) throw new IllegalArgumentException("记录不能为空");
    return openObjectStream(record.getStoragePath());
  }

  /**
   * 打开对象流式响应。
   *
   * @param objectName 对象名称
   * @return 打开对象流式响应
   */
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

  /**
   * 下载到临时文件。
   *
   * @param record 记录
   * @return 下载后的临时文件
   */
  public Path downloadToTempFile(ResumeRecord record) {
    return downloadToTempFile(record, null);
  }

  /**
   * 删除简历对象存储。
   *
   * @param record 记录
   */
  public void delete(ResumeRecord record) {
    if (record == null || record.getStoragePath() == null) return;
    deleteObject(record.getStoragePath());
  }

  /**
   * 删除对象。
   *
   * @param objectName 对象名称
   */
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

  /**
   * 下载到临时文件。
   *
   * @param record 记录
   * @param workspaceDir 工作区目录
   * @return 下载后的临时文件
   */
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

  /**
   * 读取简历对象存储桶名称。
   *
   * @return 存储桶名称
   */
  public String bucket() {
    return properties.getMinio().getBucket();
  }

  /**
   * 确保存储桶存在。
   *
   * @throws IOException 文件读写失败时抛出
   */
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
