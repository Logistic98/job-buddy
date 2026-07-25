package com.jobbuddy.backend.common.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import okhttp3.Interceptor;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 为会改写 AWS S3v4 请求的代理后 MinIO 部署提供 S3v2 签名。
 *
 * <p>启用后桶与对象操作必须统一签名版本，否则可能上传成功但读取时报 SignatureDoesNotMatch。
 */
final class S3V2SigningInterceptor implements Interceptor {
  private static final Set<String> SIGNED_SUBRESOURCES =
      new HashSet<String>(
          Arrays.asList(
              "acl",
              "cors",
              "delete",
              "lifecycle",
              "location",
              "logging",
              "notification",
              "partNumber",
              "policy",
              "requestPayment",
              "restore",
              "tagging",
              "torrent",
              "uploadId",
              "uploads",
              "versionId",
              "versioning",
              "versions",
              "website",
              "response-cache-control",
              "response-content-disposition",
              "response-content-encoding",
              "response-content-language",
              "response-content-type",
              "response-expires"));

  private final String accessKey;
  private final String secretKey;

  /**
   * 创建 S3V2 签名拦截器实例。
   *
   * @param accessKey 访问键
   * @param secretKey 密钥键
   */
  S3V2SigningInterceptor(String accessKey, String secretKey) {
    this.accessKey = accessKey;
    this.secretKey = secretKey;
  }

  /**
   * 拦截并签名 S3 请求。
   *
   * @param chain 过滤链
   * @return 下游响应
   * @throws IOException 文件读写失败时抛出
   */
  @Override
  public Response intercept(Chain chain) throws IOException {
    return chain.proceed(signRequest(chain.request()));
  }

  /**
   * 为 S3 请求生成 V2 签名头。
   *
   * @param request 请求参数
   * @return 已签名请求
   * @throws IOException 文件读写失败时抛出
   */
  Request signRequest(Request request) throws IOException {
    String date =
        DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(java.time.ZoneOffset.UTC));
    String contentMd5 = request.header("Content-MD5");
    if (contentMd5 == null) contentMd5 = "";
    String contentType = request.header("Content-Type");
    if (contentType == null) contentType = "";
    String canonicalResource = canonicalResource(request);
    String stringToSign =
        request.method()
            + "\n"
            + contentMd5
            + "\n"
            + contentType
            + "\n"
            + date
            + "\n"
            + canonicalResource;

    Request.Builder builder = request.newBuilder();
    builder.removeHeader("Authorization");
    List<String> names = new ArrayList<String>(request.headers().names());
    for (String name : names) {
      if (name.toLowerCase(Locale.ROOT).startsWith("x-amz-")) builder.removeHeader(name);
    }
    builder.header("Date", date);
    builder.header("Authorization", "AWS " + accessKey + ":" + sign(stringToSign));
    return builder.build();
  }

  /**
   * 构造 S3 V2 签名的规范资源。
   *
   * @param request 请求参数
   * @return 规范资源
   */
  String canonicalResource(Request request) {
    StringBuilder resource = new StringBuilder(request.url().encodedPath());
    List<String> names = new ArrayList<String>();
    for (String name : request.url().queryParameterNames()) {
      if (SIGNED_SUBRESOURCES.contains(name)) names.add(name);
    }
    Collections.sort(names);
    for (int index = 0; index < names.size(); index++) {
      String name = names.get(index);
      resource.append(index == 0 ? '?' : '&').append(name);
      String value = request.url().queryParameter(name);
      if (value != null && !value.isEmpty()) resource.append('=').append(value);
    }
    return resource.toString();
  }

  /**
   * 计算 HMAC-SHA1 签名。
   *
   * @param value 待处理值
   * @return Base64 签名
   * @throws IOException 文件读写失败时抛出
   */
  private String sign(String value) throws IOException {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(secretKey.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
      return Base64.getEncoder()
          .encodeToString(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
    } catch (Exception e) {
      throw new IOException("S3v2 request signing failed", e);
    }
  }
}
