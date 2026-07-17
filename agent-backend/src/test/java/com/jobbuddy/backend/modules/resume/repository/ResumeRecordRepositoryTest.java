package com.jobbuddy.backend.modules.resume.repository;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeSummaryResponse;
import com.jobbuddy.backend.modules.resume.mapper.ResumeRecordMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ResumeRecordRepositoryTest {
  @Test
  void listSummariesRestoresManagementMetadataWithoutExposingResumeContent() {
    ResumeRecordMapper mapper = mock(ResumeRecordMapper.class);
    Map<String, Object> row = new LinkedHashMap<String, Object>();
    row.put("resumeId", "resume-1");
    row.put("tenantId", "tenant-1");
    row.put(
        "parsedJson",
        "{\"folder\":\"大模型\",\"resumeFolder\":\"大模型\","
            + "\"version\":\"20260721_001\",\"labels\":[\"Java后端\"],"
            + "\"name\":\"测试用户\",\"skills\":[\"Java\"],\"analysis\":{\"overall_score\":90}}");
    when(mapper.findLatestSummariesByUserId("tenant-1", "user-1", 50))
        .thenReturn(Collections.singletonList(row));
    JsonCodec jsonCodec = new JsonCodec();
    ResumeRecordRepository repository = new ResumeRecordRepository(mapper, jsonCodec);

    List<Map<String, Object>> summaries =
        repository.findLatestSummariesByUserId("tenant-1", "user-1", 50);

    assertEquals(1, summaries.size());
    Map<String, Object> summary = summaries.get(0);
    Map<String, Object> parsed = castMap(summary.get("parsed"));
    assertEquals("大模型", parsed.get("folder"));
    assertEquals("大模型", parsed.get("resumeFolder"));
    assertEquals("20260721_001", parsed.get("version"));
    assertEquals(Arrays.asList("Java后端"), parsed.get("labels"));
    assertFalse(parsed.containsKey("name"));
    assertFalse(parsed.containsKey("skills"));
    assertFalse(parsed.containsKey("analysis"));
    assertFalse(summary.containsKey("parsedJson"));
    assertFalse(summary.containsKey("tenantId"));
    ResumeSummaryResponse response = jsonCodec.convert(summary, ResumeSummaryResponse.class);
    assertEquals("resume-1", response.getResumeId());
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> castMap(Object value) {
    return (Map<String, Object>) value;
  }
}
