package com.jobbuddy.backend.modules.resume.entity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class ResumeRecord {
    private String resumeId;
    private String userId;
    private String originalName;
    private String storagePath;
    private long sizeBytes;
    private String suffix;
    private Instant uploadedAt;
    private String parseStatus;
    private Map<String, Object> parsed;
    private String parsedJson;
    private String parseError;

    public ResumeRecord() {
        this.parsed = new LinkedHashMap<String, Object>();
    }

    public String getResumeId() { return resumeId; }
    public void setResumeId(String resumeId) { this.resumeId = resumeId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getOriginalName() { return originalName; }
    public void setOriginalName(String originalName) { this.originalName = originalName; }
    public String getStoragePath() { return storagePath; }
    public void setStoragePath(String storagePath) { this.storagePath = storagePath; }
    public long getSizeBytes() { return sizeBytes; }
    public void setSizeBytes(long sizeBytes) { this.sizeBytes = sizeBytes; }
    public String getSuffix() { return suffix; }
    public void setSuffix(String suffix) { this.suffix = suffix; }
    public Instant getUploadedAt() { return uploadedAt; }
    public void setUploadedAt(Instant uploadedAt) { this.uploadedAt = uploadedAt; }
    public String getParseStatus() { return parseStatus; }
    public void setParseStatus(String parseStatus) { this.parseStatus = parseStatus; }
    public Map<String, Object> getParsed() { return parsed; }
    public void setParsed(Map<String, Object> parsed) { this.parsed = parsed; }
    public String getParsedJson() { return parsedJson; }
    public void setParsedJson(String parsedJson) { this.parsedJson = parsedJson; }
    public String getParseError() { return parseError; }
    public void setParseError(String parseError) { this.parseError = parseError; }
}
