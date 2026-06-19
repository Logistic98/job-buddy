package com.jobbuddy.backend.modules.resume.repository;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.mapper.ResumeRecordMapper;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;

@Repository
public class ResumeRecordRepository {
    private final ResumeRecordMapper mapper;
    private final JsonCodec jsonCodec;

    public ResumeRecordRepository(ResumeRecordMapper mapper, JsonCodec jsonCodec) {
        this.mapper = mapper;
        this.jsonCodec = jsonCodec;
    }

    public ResumeRecord findById(String resumeId) {
        return toRecord(mapper.findById(resumeId));
    }

    public List<ResumeRecord> findLatestByUserId(String userId, int limit) {
        List<Map<String, Object>> rows = mapper.findLatestByUserId(userId, limit);
        List<ResumeRecord> result = new ArrayList<ResumeRecord>();
        for (Map<String, Object> row : rows) result.add(toRecord(row));
        return result;
    }

    public void deleteById(String resumeId) { mapper.deleteById(resumeId); }

    public void save(ResumeRecord record) {
        Map<String, Object> row = new HashMap<String, Object>();
        row.put("resumeId", record.getResumeId());
        row.put("userId", record.getUserId());
        row.put("originalName", record.getOriginalName());
        row.put("storagePath", record.getStoragePath());
        row.put("sizeBytes", Long.valueOf(record.getSizeBytes()));
        row.put("suffix", record.getSuffix());
        row.put("uploadedAt", record.getUploadedAt());
        row.put("parseStatus", record.getParseStatus());
        row.put("parseError", record.getParseError());
        row.put("parsedJson", jsonCodec.toJson(record.getParsed()));
        if (mapper.countById(record.getResumeId()) > 0) mapper.updateRecord(row); else mapper.insertRecord(row);
    }

    private ResumeRecord toRecord(Map<String, Object> row) {
        if (row == null) return null;
        ResumeRecord record = new ResumeRecord();
        record.setResumeId(string(row.get("resumeId")));
        record.setUserId(string(row.get("userId")));
        record.setOriginalName(string(row.get("originalName")));
        record.setStoragePath(string(row.get("storagePath")));
        Object size = row.get("sizeBytes");
        record.setSizeBytes(size instanceof Number ? ((Number) size).longValue() : 0L);
        record.setSuffix(string(row.get("suffix")));
        record.setUploadedAt(instant(row.get("uploadedAt")));
        record.setParseStatus(string(row.get("parseStatus")));
        record.setParseError(string(row.get("parseError")));
        record.setParsed(jsonCodec.toMap(string(row.get("parsedJson"))));
        record.setParsedJson(string(row.get("parsedJson")));
        return record;
    }

    private String string(Object value) { return value == null ? null : String.valueOf(value); }
    private Instant instant(Object value) {
        if (value instanceof Instant) return (Instant) value;
        if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toInstant();
        if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant();
        return value == null ? null : Instant.parse(String.valueOf(value));
    }
}
