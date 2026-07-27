package com.jobbuddy.backend.modules.chat.storage;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

/**
 * 将聊天附件原文件隔离存入 MinIO。
 */
@Component
public class ChatAttachmentStorage {
  private final JobBuddyProperties properties;
  private final MinioClient minioClient;

  public ChatAttachmentStorage(JobBuddyProperties properties, MinioClient minioClient) {
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
    } catch (Exception error) {
      throw new IOException("上传聊天附件失败", error);
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
    } catch (Exception error) {
      throw new IllegalStateException("删除聊天附件失败", error);
    }
  }

  private void ensureBucketExists() throws Exception {
    String bucket = properties.getMinio().getBucket();
    if (!minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
      minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
    }
  }
}
