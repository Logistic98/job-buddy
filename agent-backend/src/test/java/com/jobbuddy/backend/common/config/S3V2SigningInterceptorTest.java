package com.jobbuddy.backend.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;

/**
 * 验证 S3V2SigningInterceptor 的核心行为、异常路径与边界条件。
 */
class S3V2SigningInterceptorTest {

  private final S3V2SigningInterceptor interceptor =
      new S3V2SigningInterceptor("access-key", "secret-key");

  /**
   * 验证 S3V2SigningInterceptor 的持久化与状态变更规则。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void rewritesGetRequestFromV4ToV2() throws Exception {
    Request request =
        new Request.Builder()
            .url("https://image.example.com/job-buddy/admin/assets/photo.png")
            .header("Authorization", "AWS4-HMAC-SHA256 stale")
            .header("x-amz-date", "20260721T020000Z")
            .get()
            .build();

    Request signed = interceptor.signRequest(request);

    assertTrue(signed.header("Authorization").startsWith("AWS access-key:"));
    assertNotNull(signed.header("Date"));
    assertNull(signed.header("x-amz-date"));
    assertNotEquals(request.header("Authorization"), signed.header("Authorization"));
  }

  /**
   * 验证 S3V2SigningInterceptor 的核心业务契约。
   */
  @Test
  void includesSignedSubresourcesInCanonicalResource() {
    Request request =
        new Request.Builder()
            .url("https://image.example.com/job-buddy?prefix=ignored&location=")
            .get()
            .build();

    assertEquals("/job-buddy?location", interceptor.canonicalResource(request));
  }

  /**
   * 验证 S3V2SigningInterceptor 的核心业务契约。
   *
   * @throws Exception 处理失败时抛出
   */
  @Test
  void preservesPutContentHeadersWhenCreatingV2Signature() throws Exception {
    Request request =
        new Request.Builder()
            .url("https://image.example.com/job-buddy/admin/assets/photo.png")
            .header("Content-MD5", "kAFQmDzST7DWlj99KOF/cg==")
            .header("Content-Type", "image/png")
            .put(RequestBody.create(new byte[] {1, 2, 3}, MediaType.get("image/png")))
            .build();

    Request signed = interceptor.signRequest(request);

    assertTrue(signed.header("Authorization").startsWith("AWS access-key:"));
    assertNotNull(signed.header("Content-MD5"));
    assertTrue(String.valueOf(signed.header("Content-Type")).startsWith("image/png"));
  }

  /**
   * 验证每月前九天仍使用两位日期，避免 MinIO 返回 MalformedDate。
   *
   * @throws Exception 签名失败时抛出
   */
  @Test
  void formatsSingleDigitDayAsTwoDigits() throws Exception {
    S3V2SigningInterceptor fixedClockInterceptor =
        new S3V2SigningInterceptor(
            "access-key",
            "secret-key",
            Clock.fixed(Instant.parse("2026-08-01T19:22:54Z"), ZoneOffset.UTC));
    Request request =
        new Request.Builder().url("https://image.example.com/job-buddy?location=").get().build();

    Request signed = fixedClockInterceptor.signRequest(request);

    assertEquals("Sat, 01 Aug 2026 19:22:54 GMT", signed.header("Date"));
  }

  /**
   * 验证代理兼容配置使用 V2，而标准对象存储仍可显式使用 V4。
   */
  @Test
  void selectsCompatibilitySignatureForAutoConfiguration() {
    assertTrue(MinioConfig.usesV2Signature("auto"));
    assertTrue(MinioConfig.usesV2Signature("s3v2"));
    assertFalse(MinioConfig.usesV2Signature("v4"));
  }
}
