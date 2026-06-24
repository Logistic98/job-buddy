package com.jobbuddy.backend.modules.prompt.service.impl;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.job.service.JobFavoriteService;
import com.jobbuddy.backend.modules.journey.service.JobJourneyService;
import com.jobbuddy.backend.modules.prompt.model.PersonalContext;
import com.jobbuddy.backend.modules.prompt.model.UserProfileContext;
import com.jobbuddy.backend.modules.prompt.service.PersonalContextBuilder;
import com.jobbuddy.backend.modules.prompt.service.ProfileContextService;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PersonalContextBuilderImpl implements PersonalContextBuilder {
    private final ProfileContextService profileContextService;
    private final ResumeStorageService resumeStorageService;
    private final JobFavoriteService favoriteService;
    private final JobJourneyService journeyService;
    private final SystemSettingsService settingsService;
    private final JobBuddyProperties properties;

    public PersonalContextBuilderImpl(ProfileContextService profileContextService,
                                      ResumeStorageService resumeStorageService,
                                      JobFavoriteService favoriteService,
                                      JobJourneyService journeyService,
                                      SystemSettingsService settingsService,
                                      JobBuddyProperties properties) {
        this.profileContextService = profileContextService;
        this.resumeStorageService = resumeStorageService;
        this.favoriteService = favoriteService;
        this.journeyService = journeyService;
        this.settingsService = settingsService;
        this.properties = properties;
    }

    public PersonalContext build(String userId, String message, IntentResult intent, ChatSessionState state) {
        String effectiveUser = userId == null || userId.trim().isEmpty() ? properties.getDefaultUserId() : userId.trim();
        String taskType = intent == null ? "general" : intent.getIntent();
        boolean contextHelpful = needsPersonalContext(message, intent);
        UserProfileContext profileContext = contextHelpful
                ? safeProfile(effectiveUser, state == null ? null : state.resumeId)
                : new UserProfileContext(Collections.<String, Object>emptyMap(), "");
        Map<String, Object> resume = compactResume(effectiveUser, state == null ? null : state.resumeId, contextHelpful);
        List<Map<String, Object>> currentJobs = limit(state == null || state.jobs == null ? Collections.<Map<String, Object>>emptyList() : state.jobs, 8);
        List<Map<String, Object>> favorites = shouldLoadFavorites(intent) ? safeFavorites() : Collections.<Map<String, Object>>emptyList();
        List<Map<String, Object>> journey = shouldLoadJourney(intent) ? safeJourney(effectiveUser) : Collections.<Map<String, Object>>emptyList();
        List<Map<String, Object>> blacklist = shouldLoadBlacklist(intent) ? limit(safeBlacklist(), 12) : Collections.<Map<String, Object>>emptyList();
        List<Map<String, Object>> longTermMemory = contextHelpful ? safeLongTermMemory(message) : Collections.<Map<String, Object>>emptyList();
        String summary = summarize(taskType, profileContext.getSummary(), resume, currentJobs, favorites, journey, blacklist, longTermMemory);
        return new PersonalContext(taskType, profileContext.getProfile(), resume, currentJobs, favorites, journey, blacklist, longTermMemory, summary);
    }

    private boolean needsPersonalContext(String message, IntentResult intent) {
        String name = intent == null ? "" : intent.getIntent();
        String domain = intent == null ? "" : intent.getDomain();
        if ("job".equals(domain)) return true;
        String text = message == null ? "" : message.toLowerCase();
        return containsAny(text, "我", "我的", "简历", "画像", "项目", "面试", "岗位", "投递", "求职", "这些", "当前")
                || "runtime".equals(domain)
                || "complex_engineering_qa".equals(name);
    }

    private boolean shouldLoadFavorites(IntentResult intent) {
        String name = intent == null ? "" : intent.getIntent();
        return "job.favorite.plan".equals(name) || "job.compare".equals(name) || "interview.prepare".equals(name) || "application.material".equals(name);
    }

    private boolean shouldLoadJourney(IntentResult intent) {
        String name = intent == null ? "" : intent.getIntent();
        return "journey.record".equals(name) || "interview.prepare".equals(name) || "application.material".equals(name);
    }

    private boolean shouldLoadBlacklist(IntentResult intent) {
        String name = intent == null ? "" : intent.getIntent();
        return "job.recommend".equals(name) || "job.compare".equals(name) || "job.favorite.plan".equals(name);
    }

    private UserProfileContext safeProfile(String userId, String resumeId) {
        try { return profileContextService.current(userId, resumeId); } catch (Exception ignored) { return new UserProfileContext(Collections.<String, Object>emptyMap(), ""); }
    }

    private Map<String, Object> compactResume(String userId, String resumeId, boolean enabled) {
        if (!enabled || resumeId == null || resumeId.trim().isEmpty()) return Collections.emptyMap();
        try {
            ResumeRecord record = resumeStorageService.get(resumeId, userId);
            if (record == null || record.getParsed() == null) return Collections.emptyMap();
            Map<String, Object> parsed = record.getParsed();
            Map<String, Object> resume = new LinkedHashMap<String, Object>();
            copy(parsed, resume, "name", "targetRole", "summary", "skills", "projects", "experiences", "education", "advantages", "risks");
            return resume;
        } catch (Exception ignored) {
            return Collections.emptyMap();
        }
    }

    private List<Map<String, Object>> safeFavorites() {
        try { return limit(favoriteService.listFavorites(), 8); } catch (Exception ignored) { return Collections.emptyList(); }
    }

    private List<Map<String, Object>> safeJourney(String userId) {
        try { return limit(journeyService.listRecords(userId, null, null, null), 8); } catch (Exception ignored) { return Collections.emptyList(); }
    }

    private List<Map<String, Object>> safeBlacklist() {
        try { return settingsService.listBlacklistItems(); } catch (Exception ignored) { return Collections.emptyList(); }
    }

    /** 仅按当前问题召回高信号长期记忆（偏好/约束/目标），命中数量由设置层限制，避免噪声污染上下文。 */
    private List<Map<String, Object>> safeLongTermMemory(String message) {
        if (message == null || message.trim().isEmpty()) return Collections.emptyList();
        try { return settingsService.searchLocalMemories(message, 2); } catch (Exception ignored) { return Collections.emptyList(); }
    }

    private String summarize(String taskType, String profileSummary, Map<String, Object> resume,
                             List<Map<String, Object>> jobs, List<Map<String, Object>> favorites,
                             List<Map<String, Object>> journey, List<Map<String, Object>> blacklist,
                             List<Map<String, Object>> longTermMemory) {
        StringBuilder builder = new StringBuilder();
        builder.append("任务：").append(taskType == null ? "general" : taskType).append("。");
        if (profileSummary != null && !profileSummary.trim().isEmpty()) builder.append("画像：").append(profileSummary).append("。");
        if (!resume.isEmpty()) builder.append("已读取当前简历摘要。");
        if (!jobs.isEmpty()) builder.append("当前会话岗位 ").append(jobs.size()).append(" 个。");
        if (!favorites.isEmpty()) builder.append("收藏岗位 ").append(favorites.size()).append(" 个。");
        if (!journey.isEmpty()) builder.append("求职进展记录 ").append(journey.size()).append(" 条。");
        if (!blacklist.isEmpty()) builder.append("黑名单/偏好约束 ").append(blacklist.size()).append(" 条。");
        if (!longTermMemory.isEmpty()) builder.append("命中长期记忆 ").append(longTermMemory.size()).append(" 条。");
        return builder.toString();
    }

    private void copy(Map<String, Object> source, Map<String, Object> target, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            if (value != null && !String.valueOf(value).trim().isEmpty()) target.put(key, value);
        }
    }

    private List<Map<String, Object>> limit(List<Map<String, Object>> rows, int limit) {
        if (rows == null || rows.isEmpty()) return Collections.emptyList();
        int end = Math.min(rows.size(), Math.max(0, limit));
        return new java.util.ArrayList<Map<String, Object>>(rows.subList(0, end));
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null) return false;
        for (String needle : needles) if (needle != null && text.contains(needle.toLowerCase())) return true;
        return false;
    }
}
