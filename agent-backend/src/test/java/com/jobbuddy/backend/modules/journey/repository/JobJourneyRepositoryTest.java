package com.jobbuddy.backend.modules.journey.repository;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.journey.mapper.JobJourneyMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

class JobJourneyRepositoryTest {
  private final JobJourneyMapper mapper = mock(JobJourneyMapper.class);
  private final JobJourneyRepository repository = new JobJourneyRepository(mapper, new JsonCodec());

  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void writesSerializeTagsAndChooseInsertOrUpdate(boolean exists) {
    Map<String, Object> record =
        new LinkedHashMap<>(Map.of("recordId", "record", "tags", List.of(Map.of("label", "Java"))));
    Map<String, Object> target = new LinkedHashMap<>(Map.of("targetId", "target"));
    when(mapper.countRecord("record")).thenReturn(exists ? 1 : 0);
    when(mapper.countTarget("target")).thenReturn(exists ? 1 : 0);
    repository.saveRecord(record);
    repository.saveTarget(target);
    assertEquals("[{\"label\":\"Java\"}]", record.get("tagsJson"));
    assertEquals(true, record.get("enabled"));
    assertInstanceOf(Timestamp.class, record.get("updatedAt"));
    assertInstanceOf(Timestamp.class, target.get("updatedAt"));
    verify(mapper, times(exists ? 0 : 1)).insertRecord(record);
    verify(mapper, times(exists ? 1 : 0)).updateRecord(record);
    verify(mapper, times(exists ? 0 : 1)).insertTarget(target);
    verify(mapper, times(exists ? 1 : 0)).updateTarget(target);
  }

  @Test
  void filteringTrimsInputsAndRestoresTagsAndTimes() {
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    Map<String, Object> row =
        new LinkedHashMap<>(
            Map.of(
                "recordId",
                "record",
                "tagsJson",
                "[{\"label\":\"Java\"}]",
                "createdAt",
                Timestamp.from(now)));
    when(mapper.listRecords("owner", "%java%", "active", null)).thenReturn(List.of(row));
    var result = repository.listRecords("owner", " Java ", " active ", " ");
    assertEquals(List.of(Map.of("label", "Java")), result.get(0).get("tags"));
    assertFalse(result.get(0).containsKey("tagsJson"));
    assertEquals(now, result.get(0).get("createdAt"));
    repository.listRecords("owner", null, null, null);
    verify(mapper).listRecords("owner", null, null, null);
  }

  @Test
  void missingRecordsAndTargetDatesAreHandled() {
    when(mapper.findRecord("missing")).thenReturn(null);
    assertNull(repository.findRecord("missing"));
    Instant now = Instant.parse("2026-01-01T00:00:00Z");
    when(mapper.findTarget("owner"))
        .thenReturn(new LinkedHashMap<>(Map.of("updatedAt", Timestamp.from(now))));
    assertEquals(now, repository.findTarget("owner").get("updatedAt"));
    repository.deleteRecord("record");
    verify(mapper).deleteRecord(eq("record"), any());
  }
}
