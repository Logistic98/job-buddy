package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Applies deterministic client-side exclusions, role compatibility, salary rules, and ranking. */
class JobCandidateFilter {
  private final SystemSettingsService settingsService;

  JobCandidateFilter(SystemSettingsService settingsService) {
    this.settingsService = settingsService;
  }

  List<Map<String, Object>> apply(List<Map<String, Object>> rawJobs, Map<String, Object> slots) {
    List<Map<String, Object>> jobs =
        rawJobs == null ? new ArrayList<Map<String, Object>>() : rawJobs;
    jobs = clientFilter(jobs, slots);
    jobs = settingsService.filterBlacklistedJobs(jobs);
    jobs = filterByRoleCompatibility(jobs, slots);
    jobs = filterBySalary(jobs, slots);
    return sortByUserRequirement(jobs, slots);
  }

  List<Map<String, Object>> clientFilter(
      List<Map<String, Object>> jobs, Map<String, Object> slots) {
    Object excludes = slots.get("reject_keywords");
    if (!(excludes instanceof List)) excludes = slots.get("hard_excludes");
    if (!(excludes instanceof List)) excludes = slots.get("exclude_keywords");
    if (!(excludes instanceof List) || jobs.isEmpty()) return jobs;
    List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> job : jobs) {
      String text = String.valueOf(job).toLowerCase();
      boolean hit = false;
      for (Object exclude : (List) excludes) {
        if (exclude != null && text.contains(String.valueOf(exclude).toLowerCase())) {
          hit = true;
          break;
        }
      }
      if (!hit) filtered.add(job);
    }
    return filtered;
  }

  /**
   * 岗位族与专项能力硬过滤。大模型应用方向允许常规 Java/RAG/Agent 岗位，但岗位明确要求多模态、
   * 视觉、语音等专项能力而画像/简历没有对应证据时直接剔除，避免只因标题含“大模型”就进入推荐。
   */
  private List<Map<String, Object>> filterByRoleCompatibility(
      List<Map<String, Object>> jobs, Map<String, Object> slots) {
    if (jobs == null || jobs.isEmpty())
      return jobs == null ? new ArrayList<Map<String, Object>>() : jobs;
    String role = stringValue(slots.get("role")).toLowerCase();
    String capabilityText = (role + " " + stringValue(slots.get("include_keywords"))).toLowerCase();
    Integer candidateYears = toInteger(slots.get("candidate_years_experience"));
    List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> job : jobs) {
      if (job == null) continue;
      String title = stringValue(firstPresent(job, "jobName", "title", "name")).toLowerCase();
      String text =
          (title
                  + " "
                  + stringValue(firstPresent(job, "skills", "jobLabels"))
                  + " "
                  + stringValue(firstPresent(job, "brandIndustry", "industry")))
              .toLowerCase();
      if (containsAny(role, "大模型", "llm", "agent", "智能体", "rag")
          && !containsAny(text, "大模型", "llm", "agent", "智能体", "rag", "ai")) continue;
      if (requiresUnsupportedSpecialty(text, capabilityText)) continue;
      if (candidateYears != null) {
        Integer minimumYears = minimumRequiredYears(job);
        if (minimumYears != null && minimumYears > candidateYears + 1) continue;
      }
      filtered.add(job);
    }
    return filtered;
  }

  private boolean requiresUnsupportedSpecialty(String jobText, String capabilityText) {
    String[][] groups = {
      {"多模态", "视觉", "图像", "语音", "音频", "视频", "cv", "clip", "blip", "stable diffusion"},
      {"推荐算法", "搜索算法", "广告算法", "排序算法"},
      {"嵌入式", "机器人", "自动驾驶", "slam", "控制算法"},
      {"前端", "javascript", "vue", "react", "ios", "android", "客户端"}
    };
    for (String[] group : groups) {
      if (containsAny(jobText, group) && !containsAny(capabilityText, group)) return true;
    }
    return false;
  }

  private Integer minimumRequiredYears(Map<String, Object> job) {
    String text = stringValue(firstPresent(job, "jobExperience", "experience", "experienceName"));
    if (text.isEmpty() || text.contains("不限") || text.contains("应届")) return null;
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("(\\d+)\\s*(?:-|~|至|到|年以上|年)").matcher(text);
    return matcher.find() ? Integer.valueOf(matcher.group(1)) : null;
  }

  private boolean containsAny(String text, String... values) {
    String source = text == null ? "" : text.toLowerCase();
    for (String value : values) {
      if (value != null && !value.isEmpty() && source.contains(value.toLowerCase())) return true;
    }
    return false;
  }

  List<Map<String, Object>> sortByUserRequirement(
      List<Map<String, Object>> jobs, final Map<String, Object> slots) {
    if (jobs == null || jobs.isEmpty())
      return jobs == null ? new ArrayList<Map<String, Object>>() : jobs;
    List<Map<String, Object>> sorted = new ArrayList<Map<String, Object>>(jobs);
    sorted.sort(
        new java.util.Comparator<Map<String, Object>>() {
          @Override
          public int compare(Map<String, Object> a, Map<String, Object> b) {
            return Integer.compare(requirementScore(b, slots), requirementScore(a, slots));
          }
        });
    return sorted;
  }

  private int requirementScore(Map<String, Object> job, Map<String, Object> slots) {
    String text = String.valueOf(job).toLowerCase();
    String title = stringValue(firstPresent(job, "jobName", "title", "name")).toLowerCase();
    int score = 0;
    Object role = slots.get("role");
    if (role != null) {
      for (String token : splitTokens(String.valueOf(role))) {
        if (title.contains(token)) score += 12;
        else if (text.contains(token)) score += 5;
      }
    }
    Object includes = slots.get("include_keywords");
    if (includes instanceof List) {
      for (Object item : (List) includes) {
        if (item == null) continue;
        String keyword = String.valueOf(item).toLowerCase();
        if (keyword.isEmpty()) continue;
        if (title.contains(keyword)) score += 18;
        else if (text.contains(keyword)) score += 8;
      }
    }
    Object negatives = slots.get("negative_keywords");
    if (!(negatives instanceof List)) negatives = slots.get("soft_excludes");
    if (negatives instanceof List) {
      for (Object item : (List) negatives) {
        if (item == null) continue;
        String keyword = String.valueOf(item).toLowerCase();
        if (!keyword.isEmpty() && text.contains(keyword)) score -= 30;
      }
    }
    Integer minSalary = toInteger(slots.get("salary_min_k"));
    Integer maxSalary = toInteger(slots.get("salary_max_k"));
    if (salaryOverlap(job, minSalary, maxSalary)) score += 15;
    return score;
  }

  private List<String> splitTokens(String value) {
    List<String> tokens = new ArrayList<String>();
    String lower = value == null ? "" : value.toLowerCase().trim();
    if (lower.isEmpty()) return tokens;
    for (String token : lower.split("[\\s,，;；/|]+")) {
      if (token != null && !token.trim().isEmpty()) tokens.add(token.trim());
    }
    if (tokens.isEmpty()) tokens.add(lower);
    return tokens;
  }

  private boolean salaryOverlap(Map<String, Object> job, Integer minSalary, Integer maxSalary) {
    if (minSalary == null && maxSalary == null) return false;
    int[] range = monthlySalaryRangeK(job);
    if (range == null) return false;
    int expectedMin = minSalary == null ? 0 : minSalary.intValue();
    int expectedMax = maxSalary == null ? 999 : maxSalary.intValue();
    return range[1] >= expectedMin && range[0] <= expectedMax;
  }

  /**
   * 薪资硬过滤：仅在用户显式给出 salary_min_k 或 salary_max_k 时启用。丢弃可解析为月薪区间但与期望
   * 区间完全不重叠的岗位、以"元/天/日/时"计价的日结时薪岗与标题或薪资命中"实习"的实习岗；对"面议"
   * 或真正无法解析薪资的岗位保留，交由排序按其它信号决定位置，避免把信息缺失误判为不符合条件。
   */
  private List<Map<String, Object>> filterBySalary(
      List<Map<String, Object>> jobs, Map<String, Object> slots) {
    if (jobs == null || jobs.isEmpty())
      return jobs == null ? new ArrayList<Map<String, Object>>() : jobs;
    Integer minSalary = toInteger(slots.get("salary_min_k"));
    Integer maxSalary = toInteger(slots.get("salary_max_k"));
    if (minSalary == null && maxSalary == null) return jobs;
    int expectedMin = minSalary == null ? 0 : minSalary.intValue();
    int expectedMax = maxSalary == null ? Integer.MAX_VALUE : maxSalary.intValue();
    boolean strict =
        minSalary != null || maxSalary != null || Boolean.TRUE.equals(slots.get("salary_strict"));
    List<Map<String, Object>> filtered = new ArrayList<Map<String, Object>>();
    for (Map<String, Object> job : jobs) {
      if (job == null) continue;
      String salary = salaryText(job);
      String title = stringValue(firstPresent(job, "jobName", "title", "name"));
      // 日结/日薪/时薪岗位与月薪区间检索语义冲突，直接丢弃。
      if (salary.contains("天") || salary.contains("日") || salary.contains("时")) continue;
      // 实习岗与正式月薪检索语义冲突，按标题或薪资文案识别后丢弃。
      if (title.contains("实习") || salary.contains("实习")) continue;
      int[] range = monthlySalaryRangeK(job);
      // 用户明确薪资时失败关闭：面议、缺失和无法解析的薪资不能作为合格推荐。
      if (range == null) {
        if (!strict) filtered.add(job);
        continue;
      }
      if (salaryRangeMatches(range, minSalary, maxSalary)) filtered.add(job);
    }
    return filtered;
  }

  private boolean salaryRangeMatches(int[] range, Integer minSalary, Integer maxSalary) {
    if (range == null) return false;
    if (minSalary != null && maxSalary != null) {
      int expectedMin = Math.min(minSalary, maxSalary);
      int expectedMax = Math.max(minSalary, maxSalary);
      if (range[0] == range[1]) return range[0] >= expectedMin && range[0] <= expectedMax;
      int overlap = Math.max(0, Math.min(range[1], expectedMax) - Math.max(range[0], expectedMin));
      int targetWidth = expectedMax - expectedMin;
      if (targetWidth <= 0) return range[0] <= expectedMin && range[1] >= expectedMin;
      return overlap * 2 >= targetWidth;
    }
    if (minSalary != null) return range[1] >= minSalary;
    return maxSalary == null || range[0] <= maxSalary;
  }

  private String salaryText(Map<String, Object> job) {
    return stringValue(
        firstPresent(
            job,
            "salaryDesc",
            "salary_desc",
            "salary",
            "salaryText",
            "salaryName",
            "salaryRange",
            "jobSalary",
            "pay",
            "wage",
            "compensation"));
  }

  /** 优先解析展示薪资，字段缺失时回退到 Boss 结构化最低/最高薪资。返回值统一为月薪 K 区间。 */
  private int[] monthlySalaryRangeK(Map<String, Object> job) {
    int[] textRange = parseMonthlyRangeK(salaryText(job));
    if (textRange != null) return textRange;
    Double low =
        salaryNumberK(
            firstPresent(
                job,
                "lowSalary",
                "low_salary",
                "salaryLow",
                "salary_low",
                "minSalary",
                "min_salary",
                "salaryMin",
                "salary_min"));
    Double high =
        salaryNumberK(
            firstPresent(
                job,
                "highSalary",
                "high_salary",
                "salaryHigh",
                "salary_high",
                "maxSalary",
                "max_salary",
                "salaryMax",
                "salary_max"));
    if (low == null || high == null) return null;
    return normalizedRangeK(low.doubleValue(), high.doubleValue());
  }

  /**
   * 解析月薪区间并统一为 K。支持 15-20K、15K-20K、8000-12000元/月及 Boss 返回的无单位四位数元制区间；
   * 不把三位及以下的无单位数字当薪资，避免将其它编号误识别为月薪。
   */
  private int[] parseMonthlyRangeK(String salary) {
    if (salary == null || salary.trim().isEmpty()) return null;
    String normalized =
        salary
            .replace(",", "")
            .replace("，", "")
            .replace('—', '-')
            .replace('–', '-')
            .replace('－', '-')
            .replace('至', '-')
            .replace('到', '-');
    java.util.regex.Matcher kRange =
        java.util.regex.Pattern.compile(
                "(\\d{1,3}(?:\\.\\d+)?)\\s*[kK千]?\\s*[-~]\\s*(\\d{1,3}(?:\\.\\d+)?)\\s*[kK千]")
            .matcher(normalized);
    if (kRange.find()) {
      return normalizedRangeK(
          Double.parseDouble(kRange.group(1)), Double.parseDouble(kRange.group(2)));
    }
    java.util.regex.Matcher yuanRange =
        java.util.regex.Pattern.compile(
                "(\\d{4,6}(?:\\.\\d+)?)\\s*[-~]\\s*(\\d{4,6}(?:\\.\\d+)?)\\s*(?:元)?(?:/月|每月|月)?")
            .matcher(normalized);
    if (yuanRange.find()) {
      return normalizedRangeK(
          Double.parseDouble(yuanRange.group(1)) / 1000.0,
          Double.parseDouble(yuanRange.group(2)) / 1000.0);
    }
    java.util.regex.Matcher singleK =
        java.util.regex.Pattern.compile("(\\d{1,3}(?:\\.\\d+)?)\\s*[kK千]").matcher(normalized);
    if (singleK.find()) {
      double value = Double.parseDouble(singleK.group(1));
      return normalizedRangeK(value, value);
    }
    java.util.regex.Matcher singleYuan =
        java.util.regex.Pattern.compile("(\\d{4,6}(?:\\.\\d+)?)\\s*元(?:/月|每月|月)?")
            .matcher(normalized);
    if (singleYuan.find()) {
      double value = Double.parseDouble(singleYuan.group(1)) / 1000.0;
      return normalizedRangeK(value, value);
    }
    return null;
  }

  private Double salaryNumberK(Object value) {
    if (value == null) return null;
    java.util.regex.Matcher matcher =
        java.util.regex.Pattern.compile("\\d+(?:\\.\\d+)?")
            .matcher(String.valueOf(value).replace(",", ""));
    if (!matcher.find()) return null;
    double number = Double.parseDouble(matcher.group());
    if (number <= 0) return null;
    return number >= 1000 ? Double.valueOf(number / 1000.0) : Double.valueOf(number);
  }

  private int[] normalizedRangeK(double first, double second) {
    double low = Math.min(first, second);
    double high = Math.max(first, second);
    return new int[] {(int) Math.floor(low), (int) Math.ceil(high)};
  }

  private Integer toInteger(Object value) {
    if (value == null) return null;
    try {
      return Integer.valueOf(String.valueOf(value));
    } catch (Exception e) {
      return null;
    }
  }

  private Object firstPresent(Map<String, Object> map, String... keys) {
    if (map == null) return null;
    for (String key : keys) {
      Object value = map.get(key);
      if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
    }
    return null;
  }

  private String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
