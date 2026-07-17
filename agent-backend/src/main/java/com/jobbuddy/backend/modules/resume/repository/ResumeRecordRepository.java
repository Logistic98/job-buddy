package com.jobbuddy.backend.modules.resume.repository;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.mapper.ResumeRecordMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Repository;

@Repository
public class ResumeRecordRepository {
  private static final String[] MANAGEMENT_METADATA_KEYS = {
    "folder", "resumeFolder", "version", "resumeVersion", "labels", "manageTags"
  };

  private final ResumeRecordMapper mapper;
  private final JsonCodec jsonCodec;

  public ResumeRecordRepository(ResumeRecordMapper mapper, JsonCodec jsonCodec) {
    this.mapper = mapper;
    this.jsonCodec = jsonCodec;
  }

  public ResumeRecord findById(String resumeId) {
    return toRecord(mapper.findById(resumeId));
  }

  public List<ResumeRecord> findLatestByUserId(String tenantId, String userId, int limit) {
    List<Map<String, Object>> rows = mapper.findLatestByUserId(tenantId, userId, limit);
    List<ResumeRecord> result = new ArrayList<ResumeRecord>();
    for (Map<String, Object> row : rows) result.add(toRecord(row));
    return result;
  }

  public List<Map<String, Object>> findLatestSummariesByUserId(
      String tenantId, String userId, int limit) {
    List<Map<String, Object>> rows = mapper.findLatestSummariesByUserId(tenantId, userId, limit);
    List<Map<String, Object>> summaries = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> row : rows) {
      Map<String, Object> summary = new LinkedHashMap<String, Object>(row);
      summary.remove("tenantId");
      Map<String, Object> parsed = jsonCodec.toMap(string(summary.remove("parsedJson")));
      Map<String, Object> managementMetadata = new LinkedHashMap<String, Object>();
      for (String key : MANAGEMENT_METADATA_KEYS) {
        if (parsed.containsKey(key)) managementMetadata.put(key, parsed.get(key));
      }
      summary.put("parsed", managementMetadata);
      summaries.add(summary);
    }
    return summaries;
  }

  public String findTenantIdByUserId(String userId) {
    return mapper.findTenantIdByUserId(userId);
  }

  public void deleteById(String resumeId) {
    mapper.deleteById(resumeId);
  }

  public void save(ResumeRecord record) {
    Map<String, Object> row = new HashMap<String, Object>();
    row.put("resumeId", record.getResumeId());
    row.put("tenantId", record.getTenantId());
    row.put("userId", record.getUserId());
    row.put("originalName", record.getOriginalName());
    row.put("storagePath", record.getStoragePath());
    row.put("sizeBytes", Long.valueOf(record.getSizeBytes()));
    row.put("suffix", record.getSuffix());
    row.put("uploadedAt", record.getUploadedAt());
    row.put("parseStatus", record.getParseStatus());
    row.put("parseError", record.getParseError());
    row.put("parsedJson", jsonCodec.toJson(record.getParsed()));
    if (mapper.countById(record.getResumeId()) > 0) mapper.updateRecord(row);
    else mapper.insertRecord(row);
  }

  private ResumeRecord toRecord(Map<String, Object> row) {
    if (row == null) return null;
    ResumeRecord record = new ResumeRecord();
    record.setResumeId(string(row.get("resumeId")));
    record.setTenantId(string(row.get("tenantId")));
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

  private String string(Object value) {
    return value == null ? null : String.valueOf(value);
  }

  private Instant instant(Object value) {
    if (value instanceof Instant) return (Instant) value;
    if (value instanceof java.sql.Timestamp) return ((java.sql.Timestamp) value).toInstant();
    if (value instanceof java.util.Date) return ((java.util.Date) value).toInstant();
    return value == null ? null : Instant.parse(String.valueOf(value));
  }
}
