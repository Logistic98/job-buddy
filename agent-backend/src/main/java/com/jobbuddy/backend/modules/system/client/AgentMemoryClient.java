package com.jobbuddy.backend.modules.system.client;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.modules.system.dto.request.SystemMemoryRequest;
import com.jobbuddy.backend.modules.system.dto.response.SystemMemoryResponse;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * 受鉴权的 agent-memory 客户端，统一承载设置页和对话上下文使用的长期记忆。
 */
@Component
public class AgentMemoryClient {
  private static final String SERVICE = "agent-memory";
  private static final String LONG_TERM_SCOPE = "long_term";

  private final RestTemplate restTemplate;
  private final AgentServiceProperties properties;
  private final ServiceResilience resilience;

  /**
   * 创建 Agent 记忆客户端实例。
   *
   * @param restTemplate HTTP 请求客户端
   * @param properties 配置属性
   * @param resilience 弹性策略
   */
  public AgentMemoryClient(
      @Qualifier("agentMemoryRestTemplate") RestTemplate restTemplate,
      AgentServiceProperties properties,
      ServiceResilience resilience) {
    this.restTemplate = restTemplate;
    this.properties = properties;
    this.resilience = resilience;
  }

  /**
   * 查询当前用户的记忆列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 当前用户的记忆列表
   */
  public List<SystemMemoryResponse> list(String tenantId, String userId) {
    URI uri =
        UriComponentsBuilder.fromUriString(baseUrl() + "/v1/memories")
            .queryParam("scope", LONG_TERM_SCOPE)
            .queryParam("limit", 1000)
            .build()
            .encode()
            .toUri();
    return memoryList(exchange(uri, HttpMethod.GET, entity(tenantId, userId, null), true));
  }

  /**
   * 检索与查询条件匹配的记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param query 查询条件
   * @param limit 数量上限
   * @return 匹配查询条件的记忆列表
   */
  public List<SystemMemoryResponse> search(
      String tenantId, String userId, String query, int limit) {
    URI uri =
        UriComponentsBuilder.fromUriString(baseUrl() + "/v1/memories/search")
            .queryParam("q", query)
            .queryParam("scope", LONG_TERM_SCOPE)
            .build()
            .encode()
            .toUri();
    List<SystemMemoryResponse> items =
        memoryList(exchange(uri, HttpMethod.GET, entity(tenantId, userId, null), true));
    int bounded = Math.max(0, Math.min(limit, items.size()));
    return new ArrayList<SystemMemoryResponse>(items.subList(0, bounded));
  }

  /**
   * 创建用户记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param request 请求参数
   * @return 创建后的记忆记录
   */
  public SystemMemoryResponse create(String tenantId, String userId, SystemMemoryRequest request) {
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("scope", LONG_TERM_SCOPE);
    payload.put("kind", LONG_TERM_SCOPE);
    payload.put("content", request == null ? "" : request.getContent());
    payload.put(
        "source",
        request == null || request.getSource() == null ? "manual" : request.getSource().trim());
    payload.put(
        "enabled",
        request == null || request.getEnabled() == null || request.getEnabled().booleanValue());
    Map<String, Object> envelope =
        exchange(
            URI.create(baseUrl() + "/v1/memories"),
            HttpMethod.POST,
            entity(tenantId, userId, payload),
            false);
    Object data = requireSuccess(envelope);
    if (!(data instanceof Map)) throw new IllegalStateException("agent-memory 返回的记忆结构不正确");
    @SuppressWarnings("unchecked")
    Map<String, Object> item = (Map<String, Object>) data;
    return memory(item);
  }

  /**
   * 更新用户记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param memoryId 记忆标识
   * @param request 请求参数
   * @return 更新后的记忆记录
   */
  public SystemMemoryResponse update(
      String tenantId, String userId, String memoryId, SystemMemoryRequest request) {
    String normalizedId = requireMemoryId(memoryId);
    Map<String, Object> payload = new LinkedHashMap<String, Object>();
    payload.put("content", request == null ? "" : request.getContent());
    Map<String, Object> envelope =
        exchange(
            URI.create(baseUrl() + "/v1/memories/" + normalizedId),
            HttpMethod.PUT,
            entity(tenantId, userId, payload),
            false);
    Object data = requireSuccess(envelope);
    if (!(data instanceof Map)) throw new IllegalStateException("agent-memory 返回的记忆结构不正确");
    @SuppressWarnings("unchecked")
    Map<String, Object> item = (Map<String, Object>) data;
    return memory(item);
  }

  /**
   * 删除用户记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param memoryId 记忆标识
   */
  public void delete(String tenantId, String userId, String memoryId) {
    String normalizedId = requireMemoryId(memoryId);
    Map<String, Object> envelope =
        exchange(
            URI.create(baseUrl() + "/v1/memories/" + normalizedId),
            HttpMethod.DELETE,
            entity(tenantId, userId, null),
            false);
    requireSuccess(envelope);
  }

  /**
   * 校验记忆标识。
   *
   * @param memoryId 记忆标识
   * @return 规范化后的记忆标识
   */
  private String requireMemoryId(String memoryId) {
    String normalizedId = memoryId == null ? "" : memoryId.trim();
    if (!normalizedId.matches("mem_[a-zA-Z0-9]{1,64}")) {
      throw new IllegalArgumentException("记忆标识格式不正确");
    }
    return normalizedId;
  }

  /**
   * 清空当前用户的记忆。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 删除的记忆数量
   */
  public int clear(String tenantId, String userId) {
    URI uri =
        UriComponentsBuilder.fromUriString(baseUrl() + "/v1/memories")
            .queryParam("scope", LONG_TERM_SCOPE)
            .build()
            .encode()
            .toUri();
    Object data =
        requireSuccess(exchange(uri, HttpMethod.DELETE, entity(tenantId, userId, null), false));
    if (!(data instanceof Map)) return 0;
    Object deleted = ((Map<?, ?>) data).get("deleted");
    return deleted instanceof Number ? ((Number) deleted).intValue() : 0;
  }

  /**
   * 解析响应实体。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param body 请求体
   * @return 响应实体
   */
  private HttpEntity<?> entity(String tenantId, String userId, Object body) {
    requireOwner(tenantId, userId);
    HttpHeaders headers = new HttpHeaders();
    headers.set("X-Tenant-Id", tenantId.trim());
    headers.set("X-Operator-Id", userId.trim());
    if (body != null) headers.setContentType(MediaType.APPLICATION_JSON);
    return body == null ? new HttpEntity<Void>(headers) : new HttpEntity<Object>(body, headers);
  }

  /**
   * 调用记忆服务并解析响应。
   *
   * @param uri 请求地址
   * @param method HTTP 方法
   * @param entity 请求实体
   * @param retryable 是否允许重试
   * @return 记忆服务响应
   */
  @SuppressWarnings({"rawtypes", "unchecked"})
  private Map<String, Object> exchange(
      URI uri, HttpMethod method, HttpEntity<?> entity, boolean retryable) {
    AtomicReference<RuntimeException> lastFailure = new AtomicReference<RuntimeException>();
    Map<String, Object> result =
        resilience.call(
            SERVICE,
            () -> {
              try {
                ResponseEntity<Map> response =
                    restTemplate.exchange(uri, method, entity, Map.class);
                Map body = response.getBody();
                return body == null
                    ? new LinkedHashMap<String, Object>()
                    : new LinkedHashMap<String, Object>(body);
              } catch (RuntimeException error) {
                lastFailure.set(error);
                throw error;
              }
            },
            null,
            retryable,
            AgentMemoryClient::isTransientReadFailure);
    if (result != null) return result;
    throw new IllegalStateException("agent-memory 服务暂不可用，请稍后重试", lastFailure.get());
  }

  /**
   * 判断是否瞬时读取失败。
   *
   * @param error 错误
   * @return 是否属于可重试读取故障
   */
  private static boolean isTransientReadFailure(RuntimeException error) {
    if (error instanceof ResourceAccessException) return true;
    if (!(error instanceof HttpStatusCodeException)) return false;
    int status = ((HttpStatusCodeException) error).getStatusCode().value();
    return status == 408 || status == 429 || status >= 500;
  }

  /**
   * 校验并获取成功。
   *
   * @param envelope 响应封装
   * @return 校验并获取成功
   */
  private Object requireSuccess(Map<String, Object> envelope) {
    Object code = envelope == null ? null : envelope.get("code");
    if (!(code instanceof Number) || ((Number) code).intValue() != 200) {
      Object message = envelope == null ? null : envelope.get("message");
      throw new IllegalStateException(
          message == null ? "agent-memory 调用失败" : String.valueOf(message));
    }
    return envelope.get("data");
  }

  /**
   * 解析记忆列表响应。
   *
   * @param envelope 响应封装
   * @return 记忆列表
   */
  private List<SystemMemoryResponse> memoryList(Map<String, Object> envelope) {
    Object data = requireSuccess(envelope);
    if (!(data instanceof List)) throw new IllegalStateException("agent-memory 返回的记忆列表不正确");
    List<SystemMemoryResponse> result = new ArrayList<SystemMemoryResponse>();
    for (Object item : (List<?>) data) {
      if (item instanceof Map) {
        @SuppressWarnings("unchecked")
        Map<String, Object> map = (Map<String, Object>) item;
        result.add(memory(map));
      }
    }
    return result;
  }

  /**
   * 解析单条记忆响应。
   *
   * @param item 数据项
   * @return 记忆数据
   */
  private SystemMemoryResponse memory(Map<String, Object> item) {
    SystemMemoryResponse response = new SystemMemoryResponse();
    response.setId(text(item, "id"));
    response.setContent(text(item, "content"));
    String source = text(item, "source");
    response.setSource(source == null || source.trim().isEmpty() ? "agent-memory" : source);
    response.setEnabled(booleanValue(item.get("enabled"), true));
    response.setCreatedAt(firstText(item, "created_at", "createdAt"));
    response.setUpdatedAt(firstText(item, "updated_at", "updatedAt"));
    String scope = text(item, "scope");
    response.setScope(scope == null || scope.trim().isEmpty() ? LONG_TERM_SCOPE : scope);
    Object score = item.get("score");
    if (score instanceof Number) response.setScore(((Number) score).doubleValue());
    return response;
  }

  /**
   * 构造服务基础地址。
   *
   * @return 服务基础地址
   */
  private String baseUrl() {
    String value = properties.resolvedMemoryUrl();
    if (value.isEmpty()) throw new IllegalStateException("未配置 agent-memory 服务地址");
    return value;
  }

  /**
   * 校验并获取属主。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   */
  private void requireOwner(String tenantId, String userId) {
    if (tenantId == null
        || tenantId.trim().isEmpty()
        || userId == null
        || userId.trim().isEmpty()) {
      throw new IllegalArgumentException("长期记忆读写必须提供 tenantId 和 userId");
    }
  }

  /**
   * 读取文本内容。
   *
   * @param item 数据项
   * @param key 键
   * @return 文本内容
   */
  private String text(Map<String, Object> item, String key) {
    Object value = item == null ? null : item.get(key);
    return value == null ? null : String.valueOf(value);
  }

  /**
   * 读取首个非空文本。
   *
   * @param item 数据项
   * @param firstKey 首个键
   * @param secondKey 备用键
   * @return 首个非空文本
   */
  private String firstText(Map<String, Object> item, String firstKey, String secondKey) {
    String first = text(item, firstKey);
    return first == null || first.trim().isEmpty() ? text(item, secondKey) : first;
  }

  /**
   * 读取布尔值。
   *
   * @param value 待处理值
   * @param fallback 降级
   * @return 转换后的布尔值
   */
  private boolean booleanValue(Object value, boolean fallback) {
    if (value instanceof Boolean) return ((Boolean) value).booleanValue();
    return value == null ? fallback : Boolean.parseBoolean(String.valueOf(value));
  }
}
