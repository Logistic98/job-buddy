package com.jobbuddy.backend.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class JsonCodec {
  private static final Logger log = LoggerFactory.getLogger(JsonCodec.class);

  // 必须注册 JavaTimeModule：metadata 中混入 Instant（如简历 uploadedAt）时，
  // 裸 ObjectMapper 会整体序列化失败并静默落库 "{}"，导致 reasoning/toolEvents 丢失。
  private final ObjectMapper objectMapper =
      new ObjectMapper()
          .findAndRegisterModules()
          .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

  public String toJson(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (Exception e) {
      log.warn(
          "JsonCodec toJson failed, falling back to empty object. type={}, errorType={}",
          value == null ? "null" : value.getClass().getName(),
          e.getClass().getSimpleName());
      return "{}";
    }
  }

  public Map<String, Object> toMap(String json) {
    try {
      if (json == null || json.isEmpty()) return Collections.emptyMap();
      return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      log.warn(
          "JsonCodec toMap failed. inputLength={}, errorType={}",
          json == null ? 0 : json.length(),
          e.getClass().getSimpleName());
      return Collections.emptyMap();
    }
  }

  public Map<String, Object> toMap(Object value) {
    try {
      if (value == null) return Collections.emptyMap();
      return objectMapper.convertValue(value, new TypeReference<Map<String, Object>>() {});
    } catch (Exception e) {
      log.warn(
          "JsonCodec object conversion failed. type={}, errorType={}",
          value == null ? "null" : value.getClass().getName(),
          e.getClass().getSimpleName());
      return Collections.emptyMap();
    }
  }

  public List<Map<String, Object>> toMapList(String json) {
    try {
      if (json == null || json.isEmpty()) return Collections.emptyList();
      return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
    } catch (Exception e) {
      log.warn(
          "JsonCodec toMapList failed. inputLength={}, errorType={}",
          json == null ? 0 : json.length(),
          e.getClass().getSimpleName());
      return Collections.emptyList();
    }
  }

  public JsonNode toTree(Object value) {
    return value == null ? objectMapper.createObjectNode() : objectMapper.valueToTree(value);
  }

  public JsonNode readTree(String json) {
    try {
      if (json == null || json.trim().isEmpty()) return objectMapper.createObjectNode();
      return objectMapper.readTree(json);
    } catch (Exception e) {
      log.warn(
          "JsonCodec readTree failed. inputLength={}, errorType={}",
          json == null ? 0 : json.length(),
          e.getClass().getSimpleName());
      return objectMapper.createObjectNode();
    }
  }

  public <T> T convert(Object value, Class<T> type) {
    return value == null ? null : objectMapper.convertValue(value, type);
  }

  public <T> List<T> convertList(List<?> values, Class<T> type) {
    if (values == null || values.isEmpty()) return Collections.emptyList();
    return values.stream().map(value -> convert(value, type)).toList();
  }
}
