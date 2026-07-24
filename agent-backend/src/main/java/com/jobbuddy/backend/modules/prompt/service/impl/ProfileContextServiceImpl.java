package com.jobbuddy.backend.modules.prompt.service.impl;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.prompt.model.UserProfileContext;
import com.jobbuddy.backend.modules.prompt.service.ProfileContextService;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeSummaryResponse;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public class ProfileContextServiceImpl implements ProfileContextService {
  private static final Logger log = LoggerFactory.getLogger(ProfileContextServiceImpl.class);
  private final ResumeStorageService resumeStorageService;
  private final JsonCodec jsonCodec = new JsonCodec();

  public ProfileContextServiceImpl(ResumeStorageService resumeStorageService) {
    this.resumeStorageService = resumeStorageService;
  }

  @Override
  public UserProfileContext current(String userId, String resumeId) {
    Map<String, Object> profile = new LinkedHashMap<String, Object>();
    try {
      ResumeSummaryResponse jobProfile = resumeStorageService.getJobProfileOrEmpty(userId);
      Map<String, Object> parsed =
          jobProfile == null ? Collections.emptyMap() : jsonCodec.toMap(jobProfile.getParsed());
      if (!parsed.isEmpty()) profile.putAll(parsed);
    } catch (RuntimeException e) {
      // 动态画像读取失败时不阻断主问答，但需留痕以便定位画像缺失。
      log.warn("读取求职画像失败 userId={}", userId, e);
    }
    if (resumeId != null && !resumeId.trim().isEmpty()) {
      try {
        ResumeRecord record = resumeStorageService.get(resumeId, userId);
        if (record != null && record.getParsed() != null)
          profile.put("current_resume", record.getParsed());
      } catch (IllegalArgumentException e) {
        // 会话可能引用已删除或属于其他用户的简历。这是可预期的降级路径：
        // 保留求职画像并跳过当前简历，仅在 debug 留痕，避免每次聊天都产生误导性告警。
        log.debug(
            "忽略失效的当前简历引用 userId={}, resumeId={}, reason={}", userId, resumeId, conciseMessage(e));
      } catch (RuntimeException e) {
        // 数据库、对象存储等未知故障仍保留堆栈，避免真实基础设施问题被静默隐藏。
        log.warn("读取当前简历失败 userId={}, resumeId={}", userId, resumeId, e);
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
    Map<String, Object> expectation =
        firstMap(profile, "job_expectations", "expectations", "jobIntentions", "job_intentions");
    add(parts, "城市", firstPresent(expectation, "city", "location"));
    add(parts, "岗位", firstPresent(expectation, "position", "positionName", "target_roles"));
    add(parts, "薪资", firstPresent(expectation, "salary", "salaryRange", "salary_range"));
    add(parts, "行业", firstPresent(expectation, "industry", "domains"));
    add(
        parts,
        "强减分项",
        firstPresent(expectation, "negativeExcludes", "negative_excludes", "soft_excludes"));
    add(
        parts,
        "硬性拒绝项",
        firstPresent(expectation, "rejectExcludes", "reject_excludes", "hard_excludes"));
    Object summary = firstPresent(profile, "summary", "personal_advantage", "personalAdvantage");
    if (summary != null && !String.valueOf(summary).trim().isEmpty())
      parts.add("摘要：" + truncate(String.valueOf(summary), 180));
    return join(parts);
  }

  private Map<String, Object> asMap(Object value) {
    return value instanceof Map
        ? (Map<String, Object>) value
        : Collections.<String, Object>emptyMap();
  }

  private Map<String, Object> firstMap(Map<String, Object> map, String... keys) {
    for (String key : keys) {
      Object value = map == null ? null : map.get(key);
      if (value instanceof Map) return (Map<String, Object>) value;
      if (value instanceof List
          && !((List) value).isEmpty()
          && ((List) value).get(0) instanceof Map) {
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
    if (!text.isEmpty() && !"[]".equals(text) && !"{}".equals(text))
      parts.add(label + "：" + truncate(text, 120));
  }

  private String truncate(String value, int limit) {
    if (value == null || value.length() <= limit) return value;
    return value.substring(0, limit) + "...";
  }

  private String conciseMessage(Throwable error) {
    if (error == null || error.getMessage() == null || error.getMessage().trim().isEmpty())
      return "unknown";
    return truncate(error.getMessage().trim().replace('\n', ' ').replace('\r', ' '), 160);
  }

  private String join(List<String> parts) {
    return String.join("；", parts);
  }
}
