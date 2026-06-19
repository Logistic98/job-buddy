package com.jobbuddy.backend.modules.journey.repository;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.journey.mapper.JobJourneyMapper;
import org.springframework.stereotype.Repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Repository
public class JobJourneyRepository {
    private final JobJourneyMapper mapper;
    private final JsonCodec jsonCodec;

    public JobJourneyRepository(JobJourneyMapper mapper, JsonCodec jsonCodec) {
        this.mapper = mapper;
        this.jsonCodec = jsonCodec;
    }

    public Map<String, Object> findTarget(String userId) {
        Map<String, Object> row = mapper.findTarget(userId);
        normalizeTime(row, "updatedAt");
        return row;
    }

    public void saveTarget(Map<String, Object> target) {
        target.put("updatedAt", Timestamp.from(Instant.now()));
        if (mapper.countTarget(target.get("targetId")) > 0) mapper.updateTarget(target); else mapper.insertTarget(target);
    }

    public List<Map<String, Object>> listRecords(String userId, String keyword, String status, String result) {
        String q = keyword == null || keyword.trim().isEmpty() ? null : "%" + keyword.trim().toLowerCase() + "%";
        List<Map<String, Object>> rows = mapper.listRecords(userId, q, trim(status), trim(result));
        for (Map<String, Object> row : rows) hydrateRecord(row);
        return rows;
    }

    public Map<String, Object> findRecord(String recordId) { return hydrateRecord(mapper.findRecord(recordId)); }

    public void saveRecord(Map<String, Object> record) {
        record.put("tagsJson", jsonCodec.toJson(record.get("tags")));
        record.put("enabled", Boolean.valueOf(!Boolean.FALSE.equals(record.get("enabled"))));
        record.put("updatedAt", Timestamp.from(Instant.now()));
        if (mapper.countRecord(record.get("recordId")) > 0) mapper.updateRecord(record); else mapper.insertRecord(record);
    }

    public void deleteRecord(String recordId) { mapper.deleteRecord(recordId, Timestamp.from(Instant.now())); }

    private Map<String, Object> hydrateRecord(Map<String, Object> item) {
        if (item == null) return null;
        item.put("tags", jsonCodec.toMapList(string(item.get("tagsJson"))));
        item.remove("tagsJson");
        normalizeTime(item, "createdAt");
        normalizeTime(item, "updatedAt");
        return item;
    }

    private String trim(String value) { return value == null || value.trim().isEmpty() ? null : value.trim(); }
    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private void normalizeTime(Map<String, Object> item, String key) {
        if (item == null) return;
        Object value = item.get(key);
        if (value instanceof Timestamp) item.put(key, ((Timestamp) value).toInstant());
    }
}
