package com.jobbuddy.backend.modules.journey.service;

import java.util.List;
import java.util.Map;

public interface JobJourneyService {
    Map<String, Object> getTarget(String userId);
    Map<String, Object> saveTarget(String userId, Map<String, Object> payload);
    List<Map<String, Object>> listRecords(String userId, String keyword, String status, String result);
    Map<String, Object> getRecord(String recordId);
    Map<String, Object> saveRecord(String userId, Map<String, Object> payload, String recordId);
    void deleteRecord(String recordId);
    Map<String, Object> analyzeProgress(String userId, Map<String, Object> payload);
}
