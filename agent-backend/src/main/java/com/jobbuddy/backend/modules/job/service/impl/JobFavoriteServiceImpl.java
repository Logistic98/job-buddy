package com.jobbuddy.backend.modules.job.service.impl;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteAnalysisCommand;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteSaveCommand;
import com.jobbuddy.backend.modules.job.mapper.JobFavoriteMapper;
import com.jobbuddy.backend.modules.job.service.JobFavoriteService;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;

/**
 * Manages persisted favorite-job snapshots for the current default user.
 */
@Service
public class JobFavoriteServiceImpl implements JobFavoriteService {
    private final JobFavoriteMapper mapper;
    private final JsonCodec jsonCodec;
    private final JobBuddyProperties properties;
    private final JobRuntimeService jobRuntimeService;
    private final ResumeStorageService resumeStorageService;

    public JobFavoriteServiceImpl(
            JobFavoriteMapper mapper,
            JsonCodec jsonCodec,
            JobBuddyProperties properties,
            JobRuntimeService jobRuntimeService,
            ResumeStorageService resumeStorageService) {
        this.mapper = mapper;
        this.jsonCodec = jsonCodec;
        this.properties = properties;
        this.jobRuntimeService = jobRuntimeService;
        this.resumeStorageService = resumeStorageService;
    }

    public List<Map<String, Object>> listFavorites() {
        List<Map<String, Object>> result = new ArrayList<Map<String, Object>>();
        for (Map<String, Object> row : mapper.listFavorites(userId())) {
            result.add(toJob(row));
        }
        return result;
    }

    public void saveFavorite(JobFavoriteSaveCommand command) {
        if (command == null || command.isEmpty()) {
            return;
        }

        Map<String, Object> job = command.toSnapshot();
        String key = jobKey(job);
        Map<String, Object> payload = new LinkedHashMap<String, Object>(job);
        payload.put("favoriteKey", key);
        if (!payload.containsKey("favoritedAt")
                || String.valueOf(payload.get("favoritedAt")).trim().isEmpty()) {
            payload.put("favoritedAt", Instant.now().toString());
        }
        payload.put("updatedAt", Instant.now().toString());

        mapper.upsertFavorite(
                "fav_" + UUID.randomUUID().toString().replace("-", ""),
                userId(),
                key,
                jsonCodec.toJson(payload));
    }

    public void removeFavorite(String jobKey) {
        if (jobKey == null || jobKey.trim().isEmpty()) {
            return;
        }
        mapper.removeFavorite(userId(), jobKey);
    }

    public Map<String, Object> analyzeFavorite(JobFavoriteAnalysisCommand command) {
        String jobKey = command == null ? null : command.getJobKey();
        String resumeId = command == null ? null : command.getResumeId();
        if (jobKey == null || jobKey.trim().isEmpty()) {
            throw new IllegalArgumentException("缺少收藏岗位标识");
        }
        Map<String, Object> row = mapper.findFavorite(userId(), jobKey.trim());
        if (row == null) {
            throw new IllegalArgumentException("收藏岗位不存在: " + jobKey);
        }
        Map<String, Object> job = toJob(row);
        ResumeRecord resume = resolveResumeForAnalysis(resumeId);
        Map<String, Object> match = jobRuntimeService.matchResume(
                resume, java.util.Collections.singletonList(job), null);

        Map<String, Object> analysis = new LinkedHashMap<String, Object>();
        analysis.put("resumeId", resume.getResumeId());
        analysis.put("resumeName", resume.getOriginalName());
        analysis.put("analyzedAt", Instant.now().toString());
        analysis.put("schema", match.get("schema"));
        Object matches = match.get("matches");
        if (matches instanceof List && !((List) matches).isEmpty()) {
            analysis.put("match", ((List) matches).get(0));
        }
        job.put("analysis", analysis);
        job.put("analyzedAt", analysis.get("analyzedAt"));
        mapper.updateAnalysis(userId(), jobKey.trim(), jsonCodec.toJson(job));
        return job;
    }

    private ResumeRecord resolveResumeForAnalysis(String resumeId) {
        if (resumeId != null && !resumeId.trim().isEmpty()) {
            ResumeRecord record = resumeStorageService.get(resumeId.trim());
            if (record == null) throw new IllegalArgumentException("简历不存在: " + resumeId);
            return record;
        }
        for (Map<String, Object> summary : resumeStorageService.list(userId())) {
            Object parsed = summary.get("parsed");
            if ("success".equals(summary.get("parseStatus")) && parsed instanceof Map && !((Map) parsed).isEmpty()) {
                ResumeRecord record = resumeStorageService.get(String.valueOf(summary.get("resumeId")));
                if (record != null) return record;
            }
        }
        throw new IllegalArgumentException("未找到已解析的简历，请先上传并解析简历后再分析岗位");
    }

    private Map<String, Object> toJob(Map<String, Object> row) {
        Map<String, Object> job = jsonCodec.toMap(string(row.get("jobJson")));
        if (job == null) {
            job = new LinkedHashMap<String, Object>();
        }

        job.put("favoriteKey", row.get("jobKey"));
        if (!job.containsKey("favoritedAt") && row.get("favoritedAt") != null) {
            job.put("favoritedAt", toInstant(row.get("favoritedAt")).toString());
        }
        if (row.get("updatedAt") != null) {
            job.put("updatedAt", toInstant(row.get("updatedAt")).toString());
        }
        String analysisJson = string(row.get("analysisJson"));
        if (!analysisJson.trim().isEmpty()) {
            Map<String, Object> analysis = jsonCodec.toMap(analysisJson);
            if (analysis != null && !analysis.isEmpty()) {
                job.put("analysis", analysis);
            }
        }
        if (row.get("analyzedAt") != null) {
            job.put("analyzedAt", toInstant(row.get("analyzedAt")).toString());
        }
        return job;
    }

    private String userId() {
        String value = properties.getDefaultUserId();
        return value == null || value.trim().isEmpty() ? "default-user" : value.trim();
    }

    private String jobKey(Map<String, Object> job) {
        Object key = firstPresent(job, "favoriteKey", "securityId", "id", "jobId", "encryptJobId");
        if (key != null && !String.valueOf(key).trim().isEmpty()) {
            return String.valueOf(key).trim();
        }

        String title = string(firstPresent(job, "jobName", "job_name", "title"));
        String company = string(firstPresent(job, "brandName", "companyName", "company"));
        return (title + "_" + company).trim();
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private String string(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    private Instant toInstant(Object value) {
        if (value instanceof Instant) {
            return (Instant) value;
        }
        if (value instanceof java.sql.Timestamp) {
            return ((java.sql.Timestamp) value).toInstant();
        }
        if (value instanceof java.util.Date) {
            return ((java.util.Date) value).toInstant();
        }
        return Instant.parse(String.valueOf(value));
    }
}
