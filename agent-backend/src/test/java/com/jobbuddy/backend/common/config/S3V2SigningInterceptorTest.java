package com.jobbuddy.backend.common.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import org.junit.jupiter.api.Test;

class S3V2SigningInterceptorTest {

  private final S3V2SigningInterceptor interceptor =
      new S3V2SigningInterceptor("access-key", "secret-key");

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

  @Test
  void includesSignedSubresourcesInCanonicalResource() {
    Request request =
        new Request.Builder()
            .url("https://image.example.com/job-buddy?prefix=ignored&location=")
            .get()
            .build();

    assertEquals("/job-buddy?location", interceptor.canonicalResource(request));
  }

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
}
