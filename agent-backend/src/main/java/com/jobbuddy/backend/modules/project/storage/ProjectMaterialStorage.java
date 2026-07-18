package com.jobbuddy.backend.modules.project.storage;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/** MinIO storage adapter for project material files. */
@Component
public class ProjectMaterialStorage {
  private final JobBuddyProperties properties;
  private final MinioClient minioClient;

  public ProjectMaterialStorage(JobBuddyProperties properties, MinioClient minioClient) {
    this.properties = properties;
    this.minioClient = minioClient;
  }

  public void upload(MultipartFile file, String objectName, String contentType) throws IOException {
    try {
      ensureBucketExists();
      try (InputStream input = file.getInputStream()) {
        minioClient.putObject(
            PutObjectArgs.builder()
                .bucket(properties.getMinio().getBucket())
                .object(objectName)
                .contentType(contentType)
                .stream(input, file.getSize(), -1)
                .build());
      }
    } catch (Exception e) {
      throw new IOException("上传项目文件到 MinIO 失败: " + objectName, e);
    }
  }

  public InputStream open(String objectName) {
    try {
      return minioClient.getObject(
          GetObjectArgs.builder()
              .bucket(properties.getMinio().getBucket())
              .object(objectName)
              .build());
    } catch (Exception e) {
      throw new IllegalStateException("项目文件不存在或不可读: " + objectName, e);
    }
  }

  public void delete(String objectName) {
    if (objectName == null || objectName.isBlank()) return;
    try {
      minioClient.removeObject(
          RemoveObjectArgs.builder()
              .bucket(properties.getMinio().getBucket())
              .object(objectName)
              .build());
    } catch (Exception e) {
      throw new IllegalStateException("删除 MinIO 项目文件失败: " + objectName, e);
    }
  }

  private void ensureBucketExists() throws IOException {
    try {
      String bucket = properties.getMinio().getBucket();
      if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
        minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
      }
    } catch (Exception e) {
      throw new IOException("确认 MinIO bucket 失败", e);
    }
  }
}
