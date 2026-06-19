package com.jobbuddy.backend.common.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;
import java.util.Map;

@Component
public class JsonCodec {
    private static final Logger log = LoggerFactory.getLogger(JsonCodec.class);

    // 必须注册 JavaTimeModule：metadata 中混入 Instant（如简历 uploadedAt）时，
    // 裸 ObjectMapper 会整体序列化失败并静默落库 "{}"，导致 reasoning/toolEvents 丢失。
    private final ObjectMapper objectMapper = new ObjectMapper()
            .findAndRegisterModules()
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    public String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("JsonCodec toJson failed, falling back to empty object. type={}, error={}",
                    value == null ? "null" : value.getClass().getName(), e.getMessage());
            return "{}";
        }
    }

    public Map<String, Object> toMap(String json) {
        try {
            if (json == null || json.isEmpty()) return Collections.emptyMap();
            return objectMapper.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }

    public List<Map<String, Object>> toMapList(String json) {
        try {
            if (json == null || json.isEmpty()) return Collections.emptyList();
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
