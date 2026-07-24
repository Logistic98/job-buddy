package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.prompt.model.PersonalContext;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** 将本轮显式条件、求职画像和当前简历合并为可复用的岗位推荐槽位。 */
public final class JobRecommendationCriteriaBuilder {
  private static final Pattern K_RANGE =
      Pattern.compile(
          "(\\d{1,3}(?:\\.\\d+)?)\\s*(?:k|千)?\\s*[-~至到]\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*(?:k|千)",
          Pattern.CASE_INSENSITIVE);
  private static final Pattern WAN_RANGE =
      Pattern.compile("(\\d{1,2}(?:\\.\\d+)?)\\s*[-~至到]\\s*(\\d{1,2}(?:\\.\\d+)?)\\s*万");
  private static final Pattern YUAN_RANGE =
      Pattern.compile("(\\d{4,6})\\s*[-~至到]\\s*(\\d{4,6})\\s*(?:元)?(?:/月|每月|月薪)?");
  private static final Pattern K_MIN =
      Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)\\s*(?:k|千)\\s*(?:以上|起)", Pattern.CASE_INSENSITIVE);

  private JobRecommendationCriteriaBuilder() {}

  public static IntentResult enrich(
      IntentResult intent, PersonalContext context, String rawMessage) {
    Map<String, Object> slots =
        intent == null || intent.getSlots() == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(intent.getSlots());
    Map<String, Object> profile = context == null ? Collections.emptyMap() : context.getProfile();
    Map<String, Object> resume = context == null ? Collections.emptyMap() : context.getResume();
    Map<String, Object> currentResume = firstMap(profile, "current_resume", "currentResume");
    Map<String, Object> expectations =
        firstMap(profile, "job_expectations", "expectations", "jobIntentions", "job_intentions");

    String role = stringValue(slots.get("role"));
    if (role.isEmpty()) {
      role = firstString(expectations, "position", "positionName", "target_roles");
      if (role.isEmpty()) role = firstString(resume, "targetRole", "current_title");
      if (role.isEmpty()) role = firstString(profile, "current_title", "currentTitle");
      if (!role.isEmpty()) slots.put("role", role);
    }
    if (stringValue(slots.get("city")).isEmpty()) {
      String city = firstString(expectations, "city", "location");
      if (city.isEmpty()) city = firstString(currentResume, "city", "location");
      if (!city.isEmpty()) slots.put("city", city);
    }

    Set<String> includes = values(slots.get("include_keywords"));
    includes.addAll(values(profile.get("skills")));
    includes.addAll(values(resume.get("skills")));
    includes.addAll(values(currentResume.get("skills")));
    includes.addAll(values(profile.get("expected_titles")));
    includes.addAll(values(currentResume.get("expected_titles")));
    if (!role.isEmpty()) includes.add(role);
    putList(slots, "include_keywords", includes, 40);

    Set<String> hardExcludes = values(slots.get("hard_excludes"));
    hardExcludes.addAll(values(slots.get("reject_keywords")));
    hardExcludes.addAll(
        values(firstPresent(expectations, "rejectExcludes", "reject_excludes", "hard_excludes")));
    putList(slots, "hard_excludes", hardExcludes, 30);

    Set<String> softExcludes = values(slots.get("soft_excludes"));
    softExcludes.addAll(values(slots.get("negative_keywords")));
    softExcludes.addAll(
        values(
            firstPresent(expectations, "negativeExcludes", "negative_excludes", "soft_excludes")));
    putList(slots, "soft_excludes", softExcludes, 30);

    Integer years = firstInteger(profile, "years_experience", "yearsExperience");
    if (years == null) years = firstInteger(currentResume, "years_experience", "yearsExperience");
    if (years == null) years = firstInteger(resume, "years_experience", "yearsExperience");
    if (years != null && years >= 0) slots.put("candidate_years_experience", years);

    boolean existingSalary = slots.get("salary_min_k") != null || slots.get("salary_max_k") != null;
    int[] parsedSalary = parseSalaryRangeK(rawMessage);
    if (parsedSalary == null && !existingSalary) {
      parsedSalary = parseSalaryRangeK(stringValue(slots.get("salary")));
    }
    if (parsedSalary == null && !existingSalary) {
      parsedSalary =
          parseSalaryRangeK(
              stringValue(firstPresent(expectations, "salary", "salaryRange", "salary_range")));
    }
    if (parsedSalary != null) {
      slots.put("salary_min_k", parsedSalary[0]);
      if (parsedSalary[1] > 0) slots.put("salary_max_k", parsedSalary[1]);
      slots.put("salary_strict", true);
    } else if (existingSalary || Boolean.TRUE.equals(slots.get("salary_strict"))) {
      slots.put("salary_strict", true);
    }
    slots.put("profile_context_used", context != null && !profile.isEmpty());
    slots.put("resume_context_used", context != null && !resume.isEmpty());

    if (intent == null) {
      return new IntentResult(
          "job",
          "job.recommend",
          1.0,
          Collections.<String>emptyList(),
          "low",
          false,
          "call_get_recommend_jobs",
          slots);
    }
    IntentResult enriched =
        new IntentResult(
            intent.getDomain(),
            intent.getIntent(),
            intent.getConfidence(),
            intent.getSecondary(),
            intent.getRisk(),
            intent.isNeedsClarification(),
            intent.getNextAction(),
            slots,
            intent.getTraceId());
    enriched.setRouter(intent.getRouter());
    return enriched;
  }

  public static int[] parseSalaryRangeK(String value) {
    String text = stringValue(value).toLowerCase().replace("，", "").replace(",", "");
    if (text.isEmpty()) return null;
    Matcher kRange = K_RANGE.matcher(text);
    if (kRange.find()) return rangeK(kRange.group(1), kRange.group(2), 1.0);
    Matcher wanRange = WAN_RANGE.matcher(text);
    if (wanRange.find()) return rangeK(wanRange.group(1), wanRange.group(2), 10.0);
    Matcher yuanRange = YUAN_RANGE.matcher(text);
    if (yuanRange.find()) return rangeK(yuanRange.group(1), yuanRange.group(2), 0.001);
    Matcher minMatcher = K_MIN.matcher(text);
    if (minMatcher.find()) {
      int min = (int) Math.floor(Double.parseDouble(minMatcher.group(1)));
      return new int[] {min, 0};
    }
    return null;
  }

  private static int[] rangeK(String first, String second, double multiplier) {
    double left = Double.parseDouble(first) * multiplier;
    double right = Double.parseDouble(second) * multiplier;
    return new int[] {
      (int) Math.floor(Math.min(left, right)), (int) Math.ceil(Math.max(left, right))
    };
  }

  private static Map<String, Object> firstMap(Map<String, Object> source, String... keys) {
    if (source == null) return Collections.emptyMap();
    for (String key : keys) {
      Object value = source.get(key);
      if (value instanceof Map) return (Map<String, Object>) value;
      if (value instanceof List
          && !((List<?>) value).isEmpty()
          && ((List<?>) value).get(0) instanceof Map)
        return (Map<String, Object>) ((List<?>) value).get(0);
    }
    return Collections.emptyMap();
  }

  private static Object firstPresent(Map<String, Object> source, String... keys) {
    if (source == null) return null;
    for (String key : keys) {
      Object value = source.get(key);
      if (value != null && !stringValue(value).isEmpty()) return value;
    }
    return null;
  }

  private static String firstString(Map<String, Object> source, String... keys) {
    return stringValue(firstPresent(source, keys));
  }

  private static Integer firstInteger(Map<String, Object> source, String... keys) {
    Object value = firstPresent(source, keys);
    if (value instanceof Number) return ((Number) value).intValue();
    Matcher matcher = Pattern.compile("\\d+").matcher(stringValue(value));
    return matcher.find() ? Integer.valueOf(matcher.group()) : null;
  }

  private static Set<String> values(Object value) {
    Set<String> result = new LinkedHashSet<String>();
    if (value instanceof Collection) {
      for (Object item : (Collection<?>) value) addValue(result, item);
    } else {
      addValue(result, value);
    }
    return result;
  }

  private static void addValue(Set<String> result, Object value) {
    String text = stringValue(value);
    if (text.isEmpty() || "[]".equals(text) || "{}".equals(text)) return;
    for (String item : text.split("[,，;；/|\\n]+")) {
      String normalized = item.trim();
      if (!normalized.isEmpty() && normalized.length() <= 80) result.add(normalized);
    }
  }

  private static void putList(
      Map<String, Object> slots, String key, Set<String> values, int limit) {
    if (values.isEmpty()) return;
    List<String> result = new ArrayList<String>(values);
    slots.put(key, result.subList(0, Math.min(result.size(), limit)));
  }

  private static String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }
}
