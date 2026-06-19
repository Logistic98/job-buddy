package com.jobbuddy.backend.modules.resume.service;

import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;

public interface ResumeStorageService {
    ResumeRecord upload(MultipartFile file, String userId) throws IOException;
    Map<String, Object> uploadAsset(MultipartFile file, String userId) throws IOException;
    InputStream openAsset(String encodedObjectName);
    String assetContentType(String encodedObjectName);
    ResumeRecord syncBossOnlineResume(String userId) throws IOException;
    Map<String, Object> getJobProfileOrEmpty(String userId);
    ResumeRecord getOrCreateJobProfile(String userId) throws IOException;
    ResumeRecord saveJobProfile(String userId, Map<String, Object> parsed) throws IOException;
    Map<String, Object> generateJobProfileSummary(Map<String, Object> parsed, String sessionId);
    ResumeRecord get(String resumeId);
    InputStream openOriginalFile(String resumeId);
    byte[] thumbnail(String resumeId);
    ResumeRecord updateParsed(String resumeId, Map<String, Object> parsed, String userId);
    void delete(String resumeId, String userId);
    List<Map<String, Object>> list(String userId);
    ResumeRecord analyzeSync(String resumeId, String sessionId);
    ResumeRecord parseSync(String resumeId, String sessionId);
    Map<String, Object> summarize(ResumeRecord record);
}
