package com.jobbuddy.backend.modules.job.service;

import java.util.List;
import java.util.Map;

public interface JobFavoriteService {
    List<Map<String, Object>> listFavorites();
    void saveFavorite(Map<String, Object> job);
    void removeFavorite(String jobKey);
    Map<String, Object> analyzeFavorite(String jobKey, String resumeId);
}
