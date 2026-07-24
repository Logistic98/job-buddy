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

@Configuration
public class HttpClientConfig {

  @Bean
  @Primary
  public RestTemplate restTemplate(AgentServiceProperties properties) {
    return createRestTemplate(
        properties, properties.getConnectTimeout(), properties.getReadTimeout(), false);
  }

  /**
   * Memory management is a synchronous settings-path dependency, so it uses a short dedicated
   * timeout. Automatic Apache retries are disabled because ServiceResilience owns the explicit
   * retry budget and error classification.
   */
  @Bean("agentMemoryRestTemplate")
  public RestTemplate agentMemoryRestTemplate(AgentServiceProperties properties) {
    return createRestTemplate(
        properties, properties.getMemoryConnectTimeout(), properties.getMemoryReadTimeout(), true);
  }

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
