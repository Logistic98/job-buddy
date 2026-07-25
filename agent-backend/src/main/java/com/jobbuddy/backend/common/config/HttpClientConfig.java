package com.jobbuddy.backend.common.config;

import com.jobbuddy.backend.common.security.AuthenticationScope;
import org.apache.hc.client5.http.config.ConnectionConfig;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClientBuilder;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManager;
import org.apache.hc.client5.http.impl.io.PoolingHttpClientConnectionManagerBuilder;
import org.apache.hc.core5.util.Timeout;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import org.springframework.http.client.HttpComponentsClientHttpRequestFactory;
import org.springframework.web.client.RestTemplate;

/**
 * 构建内部服务 HTTP 连接池并透传服务令牌与请求身份。
 *
 * <p>重试由调用方的弹性策略统一管理，避免传输层重复非幂等操作。
 */
@Configuration
public class HttpClientConfig {

  /**
   * 创建统一配置的 HTTP 客户端。
   *
   * @param properties 配置属性
   * @return HTTP 客户端
   */
  @Bean
  @Primary
  public RestTemplate restTemplate(AgentServiceProperties properties) {
    return createRestTemplate(
        properties, properties.getConnectTimeout(), properties.getReadTimeout(), false);
  }

  /**
   * 记忆管理属于同步设置链路，使用独立短超时。自动重试关闭，由 ServiceResilience 统一管理重试预算和错误分类。
   *
   * @param properties 配置属性
   * @return 记忆服务 HTTP 客户端
   */
  @Bean("agentMemoryRestTemplate")
  public RestTemplate agentMemoryRestTemplate(AgentServiceProperties properties) {
    return createRestTemplate(
        properties, properties.getMemoryConnectTimeout(), properties.getMemoryReadTimeout(), true);
  }

  /**
   * 创建 REST 请求客户端。
   *
   * @param properties 配置属性
   * @param connectDuration 连接时长
   * @param readDuration 读取时长
   * @param disableAutomaticRetries 是否禁用自动重试
   * @return HTTP 客户端
   */
  private RestTemplate createRestTemplate(
      AgentServiceProperties properties,
      java.time.Duration connectDuration,
      java.time.Duration readDuration,
      boolean disableAutomaticRetries) {
    Timeout connectTimeout = Timeout.of(connectDuration);
    Timeout readTimeout = Timeout.of(readDuration);
    ConnectionConfig connectionConfig =
        ConnectionConfig.custom()
            .setConnectTimeout(connectTimeout)
            .setSocketTimeout(readTimeout)
            .build();
    PoolingHttpClientConnectionManager connectionManager =
        PoolingHttpClientConnectionManagerBuilder.create()
            .setMaxConnTotal(64)
            .setMaxConnPerRoute(32)
            .setDefaultConnectionConfig(connectionConfig)
            .build();

    RequestConfig requestConfig =
        RequestConfig.custom()
            .setResponseTimeout(readTimeout)
            .setConnectionRequestTimeout(connectTimeout)
            .build();

    HttpClientBuilder httpClientBuilder =
        HttpClients.custom()
            .setConnectionManager(connectionManager)
            .setDefaultRequestConfig(requestConfig)
            .evictExpiredConnections();
    if (disableAutomaticRetries) httpClientBuilder.disableAutomaticRetries();
    CloseableHttpClient httpClient = httpClientBuilder.build();

    RestTemplate restTemplate =
        new RestTemplate(new HttpComponentsClientHttpRequestFactory(httpClient));
    String internalServiceToken = properties.resolvedInternalServiceToken();
    restTemplate
        .getInterceptors()
        .add(
            (request, body, execution) -> {
              if (!internalServiceToken.isEmpty()) {
                request.getHeaders().set("X-Internal-Service-Token", internalServiceToken);
              }
              if (AuthenticationScope.isBound()) {
                request.getHeaders().set("X-Tenant-Id", AuthenticationScope.tenantId());
                request.getHeaders().set("X-Operator-Id", AuthenticationScope.userId());
              }
              return execution.execute(request, body);
            });
    return restTemplate;
  }
}
