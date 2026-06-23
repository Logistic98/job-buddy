package com.jobbuddy.backend;

import com.jobbuddy.backend.common.util.JsonCodec;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsonCodecTest {

    private final JsonCodec jsonCodec = new JsonCodec();

    @Test
    void toJsonShouldSerializeMetadataContainingInstant() {
        // 回归用例：简历摘要中的 uploadedAt(Instant) 曾导致整个 metadata 序列化失败落库 "{}"，
        // 造成会话切换后推理过程与工具事件丢失。
        Map<String, Object> resumeSummary = new LinkedHashMap<String, Object>();
        resumeSummary.put("resumeId", "resume_1");
        resumeSummary.put("uploadedAt", Instant.parse("2026-06-12T01:06:00Z"));
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("reasoning", "推理过程");
        metadata.put("resumeMatch", resumeSummary);

        String json = jsonCodec.toJson(metadata);

        assertNotEquals("{}", json);
        assertTrue(json.contains("推理过程"));
        assertTrue(json.contains("2026-06-12T01:06:00Z"));
        Map<String, Object> roundTrip = jsonCodec.toMap(json);
        assertEquals("推理过程", roundTrip.get("reasoning"));
    }
}
