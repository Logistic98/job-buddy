package com.jobbuddy.backend.modules.prompt.service.impl;

import com.jobbuddy.backend.modules.prompt.model.UserProfileContext;
import com.jobbuddy.backend.modules.prompt.service.ProfileContextService;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ProfileContextServiceImpl implements ProfileContextService {
    private static final Logger log = LoggerFactory.getLogger(ProfileContextServiceImpl.class);
    private final ResumeStorageService resumeStorageService;

    public ProfileContextServiceImpl(ResumeStorageService resumeStorageService) {
        this.resumeStorageService = resumeStorageService;
    }

    @Override
    public UserProfileContext current(String userId, String resumeId) {
        Map<String, Object> profile = new LinkedHashMap<String, Object>();
        try {
            Map<String, Object> jobProfile = resumeStorageService.getJobProfileOrEmpty(userId);
            Map<String, Object> parsed = asMap(jobProfile == null ? null : jobProfile.get("parsed"));
            if (!parsed.isEmpty()) profile.putAll(parsed);
        } catch (RuntimeException e) {
            // 动态画像读取失败时不阻断主问答，但需留痕以便定位画像缺失。
            log.warn("读取求职画像失败 userId={}: {}", userId, e.getMessage());
        }
        if (resumeId != null && !resumeId.trim().isEmpty()) {
            try {
                ResumeRecord record = resumeStorageService.get(resumeId, userId);
                if (record != null && record.getParsed() != null) profile.put("current_resume", record.getParsed());
            } catch (RuntimeException e) {
                // 当前简历读取失败时降级为不带简历的画像，留痕便于排查。
                log.warn("读取当前简历失败 resumeId={}: {}", resumeId, e.getMessage());
            }
        }
        return new UserProfileContext(profile, summarize(profile));
    }

    private String summarize(Map<String, Object> profile) {
        if (profile == null || profile.isEmpty()) return "";
        List<String> parts = new ArrayList<String>();
        add(parts, "姓名", firstPresent(profile, "name"));
        add(parts, "当前方向", firstPresent(profile, "current_title", "currentTitle"));
        add(parts, "经验年限", firstPresent(profile, "years_experience", "yearsExperience"));
        add(parts, "目标方向", firstPresent(profile, "expected_titles", "expectedTitles"));
        add(parts, "技能", firstPresent(profile, "skills"));
        Map<String, Object> expectation = firstMap(profile, "job_expectations", "expectations", "jobIntentions", "job_intentions");
        add(parts, "城市", firstPresent(expectation, "city", "location"));
        add(parts, "岗位", firstPresent(expectation, "position", "positionName", "target_roles"));
        add(parts, "薪资", firstPresent(expectation, "salary", "salaryRange", "salary_range"));
        add(parts, "行业", firstPresent(expectation, "industry", "domains"));
        add(parts, "强减分项", firstPresent(expectation, "negativeExcludes", "negative_excludes", "soft_excludes"));
        add(parts, "硬性拒绝项", firstPresent(expectation, "rejectExcludes", "reject_excludes", "hard_excludes"));
        Object summary = firstPresent(profile, "summary", "personal_advantage", "personalAdvantage");
        if (summary != null && !String.valueOf(summary).trim().isEmpty()) parts.add("摘要：" + truncate(String.valueOf(summary), 180));
        return join(parts);
    }

    private Map<String, Object> asMap(Object value) {
        return value instanceof Map ? (Map<String, Object>) value : Collections.<String, Object>emptyMap();
    }

    private Map<String, Object> firstMap(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map == null ? null : map.get(key);
            if (value instanceof Map) return (Map<String, Object>) value;
            if (value instanceof List && !((List) value).isEmpty() && ((List) value).get(0) instanceof Map) {
                return (Map<String, Object>) ((List) value).get(0);
            }
        }
        return Collections.emptyMap();
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
        }
        return null;
    }

    private void add(List<String> parts, String label, Object value) {
        if (value == null) return;
        String text = String.valueOf(value).trim();
        if (!text.isEmpty() && !"[]".equals(text) && !"{}".equals(text)) parts.add(label + "：" + truncate(text, 120));
    }

    private String truncate(String value, int limit) {
        if (value == null || value.length() <= limit) return value;
        return value.substring(0, limit) + "...";
    }

    private String join(List<String> parts) {
        return String.join("；", parts);
    }
}
