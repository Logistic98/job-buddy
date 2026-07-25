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

/**
 * 项目材料文件的 MinIO 存储适配器。
 */
@Component
public class ProjectMaterialStorage {
  private final JobBuddyProperties properties;
  private final MinioClient minioClient;

  /**
   * 创建项目材料存储实例。
   *
   * @param properties 配置属性
   * @param minioClient MinIO 客户端
   */
  public ProjectMaterialStorage(JobBuddyProperties properties, MinioClient minioClient) {
    this.properties = properties;
    this.minioClient = minioClient;
  }

  /**
   * 上传项目材料存储。
   *
   * @param file 上传文件
   * @param objectName 对象名称
   * @param contentType 内容类型
   * @throws IOException 文件读写失败时抛出
   */
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

  /**
   * 打开项目材料存储。
   *
   * @param objectName 对象名称
   * @return 打开
   */
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

  /**
   * 删除项目材料存储。
   *
   * @param objectName 对象名称
   */
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

  /**
   * 确保存储桶存在。
   *
   * @throws IOException 文件读写失败时抛出
   */
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
