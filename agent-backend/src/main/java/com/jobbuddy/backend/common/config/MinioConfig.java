package com.jobbuddy.backend.common.config;

import io.minio.MinioClient;
import okhttp3.OkHttpClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/**
 * 配置 MinIO。
 */
@Configuration
public class MinioConfig {

  /**
   * 创建 MinIO 客户端。
   *
   * @param properties 配置属性
   * @return MinIO 配置 客户端
   */
  @Bean
  public MinioClient minioClient(JobBuddyProperties properties) {
    JobBuddyProperties.Minio minio = properties.getMinio();
    requireText(minio.getEndpoint(), "JOB_BUDDY_MINIO_ENDPOINT");
    requireText(minio.getAccessKey(), "JOB_BUDDY_MINIO_ACCESS_KEY");
    requireText(minio.getSecretKey(), "JOB_BUDDY_MINIO_SECRET_KEY");
    requireText(minio.getBucket(), "JOB_BUDDY_MINIO_BUCKET");
    MinioClient.Builder builder =
        MinioClient.builder()
            .endpoint(minio.getEndpoint())
            .credentials(minio.getAccessKey(), minio.getSecretKey())
            .region(StringUtils.hasText(minio.getRegion()) ? minio.getRegion() : null);
    if ("v2".equalsIgnoreCase(minio.getSignatureVersion())
        || "s3v2".equalsIgnoreCase(minio.getSignatureVersion())) {
      OkHttpClient httpClient =
          new OkHttpClient.Builder()
              .addNetworkInterceptor(
                  new S3V2SigningInterceptor(minio.getAccessKey(), minio.getSecretKey()))
              .build();
      builder.httpClient(httpClient);
    }
    return builder.build();
  }

  /**
   * 校验并获取文本。
   *
   * @param value 待处理值
   * @param envName 环境名称
   */
  private static void requireText(String value, String envName) {
    if (!StringUtils.hasText(value)) {
      throw new IllegalStateException("缺少环境变量: " + envName);
    }
  }
}
