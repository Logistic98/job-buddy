package com.jobbuddy.backend.modules.resume.service.impl;

import com.jobbuddy.backend.common.util.JsonCodec;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 求职画像的字段归一化：将 Boss 在线简历原始结构映射为统一的 parsed 画像字段， 并提供画像文本渲染、空画像模板与规则版摘要兜底。
 */
class BossProfileNormalizer {

  static final String PROFILE_SOURCE_TYPE = "job_profile";
  static final String BOSS_PROFILE_SOURCE_TYPE = "boss_online_resume";

  private final JsonCodec jsonCodec;

  /**
   * 创建 Boss 画像规范化器实例。
   *
   * @param jsonCodec JSON 编解码器
   */
  BossProfileNormalizer(JsonCodec jsonCodec) {
    this.jsonCodec = jsonCodec;
  }

  /**
   * 规范化 Boss 画像。
   *
   * @param profile 画像
   * @return 规范化后的 Boss 画像
   */
  Map<String, Object> normalizeBossProfile(Map<String, Object> profile) {
    // 先兼容 Boss 不同接口版本的顶层分组结构。
    Map<String, Object> basic = asMap(profile.get("basicInfo"));
    Map<String, Object> expectations = asMap(profile.get("jobExpectations"));
    Map<String, Object> status = asMap(profile.get("jobStatus"));
    Map<String, Object> user = asMap(profile.get("userInfo"));
    Map<String, Object> advantage = asMap(profile.get("personalAdvantage"));
    Map<String, Object> work = asMap(profile.get("workExperiences"));
    Map<String, Object> project = asMap(profile.get("projectExperiences"));
    Map<String, Object> education = asMap(profile.get("educationExperiences"));
    Map<String, Object> intention = asMap(profile.get("jobIntentions"));

    // 核心画像字段按明确优先级从分组和原始载荷中提取。
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put(
        "name",
        firstString(basic, user, profile, "name", "userName", "user_name", "geekName", "nickName"));
    parsed.put("summary", buildBossSummary(basic, expectations, status, user, profile));
    parsed.put(
        "current_title",
        firstString(
            basic,
            expectations,
            profile,
            "currentTitle",
            "current_title",
            "position",
            "jobTitle",
            "positionName",
            "expectPosition"));
    parsed.put(
        "years_experience",
        firstPresent(basic, profile, "years_experience", "workYears", "work_years", "experience"));
    parsed.put(
        "expected_titles",
        firstPresent(
            expectations,
            basic,
            profile,
            "expected_titles",
            "expectPositions",
            "expect_positions",
            "positions",
            "positionName",
            "expectPosition",
            "expectPositionName"));
    parsed.put(
        "skills",
        firstPresent(
            basic,
            profile,
            "skills",
            "skillTags",
            "skill_tags",
            "tags",
            "geekSkillList",
            "skillList"));

    // 经历类字段保留原结构，缺失时递归查找兼容的历史字段名。
    parsed.put("basic_info", basic.isEmpty() ? profile : basic);
    parsed.put(
        "personal_advantage",
        advantage.isEmpty()
            ? firstPresentDeep(
                basic,
                profile,
                "personalAdvantage",
                "advantage",
                "summary",
                "description",
                "intro",
                "geekDesc",
                "selfDescription",
                "personalSummary")
            : advantage);
    parsed.put(
        "job_status",
        status.isEmpty()
            ? firstPresentDeep(
                basic,
                profile,
                "jobStatus",
                "job_status",
                "status",
                "statusDesc",
                "applyStatus",
                "applyStatusDesc")
            : status);
    parsed.put(
        "job_expectations",
        expectations.isEmpty()
            ? firstPresentDeep(
                basic,
                profile,
                "jobExpectations",
                "job_expectations",
                "expectations",
                "expectList",
                "jobExpectList",
                "geekExpectList")
            : expectations);
    parsed.put(
        "work_experiences",
        work.isEmpty()
            ? firstPresentDeep(
                basic,
                profile,
                "workExperiences",
                "work_experiences",
                "workExpList",
                "workExperienceList",
                "geekWorkExpList",
                "workList",
                "workExperience")
            : work);
    parsed.put(
        "project_experiences",
        project.isEmpty()
            ? firstPresentDeep(
                basic,
                profile,
                "projectExperiences",
                "project_experiences",
                "projectExpList",
                "projectExperienceList",
                "geekProjectExpList",
                "projectList",
                "projectExperience")
            : project);
    parsed.put(
        "education_experiences",
        education.isEmpty()
            ? firstPresentDeep(
                basic,
                profile,
                "educationExperiences",
                "education_experiences",
                "education",
                "educations",
                "educationExpList",
                "eduExpList",
                "geekEduExpList",
                "eduList",
                "educationExperience")
            : education);
    parsed.put(
        "job_intentions",
        intention.isEmpty()
            ? firstPresentDeep(
                expectations,
                profile,
                "jobIntentions",
                "job_intentions",
                "expectList",
                "expectPositionList",
                "jobExpectList",
                "geekExpectList",
                "expectations",
                "jobExpectations")
            : intention);

    // 同时输出 Runtime 使用的稳定别名和可追溯来源信息。
    parsed.put("education", parsed.get("education_experiences"));
    parsed.put("experiences", parsed.get("work_experiences"));
    parsed.put("projects", parsed.get("project_experiences"));
    parsed.put("expectations", parsed.get("job_expectations"));
    Map<String, Object> source = new LinkedHashMap<String, Object>();
    source.put("type", PROFILE_SOURCE_TYPE);
    source.put("provider", "Boss 直聘");
    source.put("synced_at", Instant.now().toString());
    source.put("raw", profile);
    parsed.put("source", source);
    return parsed;
  }

  /**
   * 获取空值岗位画像。
   *
   * @return 空值岗位画像
   */
  Map<String, Object> emptyJobProfile() {
    Map<String, Object> parsed = new LinkedHashMap<String, Object>();
    parsed.put("name", "");
    parsed.put("summary", "");
    parsed.put("current_title", "");
    parsed.put("years_experience", "");
    parsed.put("expected_titles", "");
    parsed.put("skills", new java.util.ArrayList<Object>());
    parsed.put("basic_info", "");
    parsed.put("personal_advantage", "");
    parsed.put("job_status", "");
    parsed.put("job_expectations", "");
    parsed.put("work_experiences", "");
    parsed.put("project_experiences", "");
    parsed.put("education_experiences", "");
    parsed.put("job_intentions", "");
    ensureProfileSource(parsed, "手动填写", null);
    return parsed;
  }

  /**
   * 确保画像来源。
   *
   * @param parsed 解析结果
   * @param provider 提供方
   * @param raw 原始数据
   */
  void ensureProfileSource(Map<String, Object> parsed, String provider, Map<String, Object> raw) {
    Map<String, Object> source = asMap(parsed.get("source"));
    source.put("type", PROFILE_SOURCE_TYPE);
    source.put("provider", provider == null || provider.isEmpty() ? "手动填写" : provider);
    source.put("synced_at", Instant.now().toString());
    if (raw != null && !raw.isEmpty()) source.put("raw", raw);
    parsed.put("source", source);
  }

  /**
   * 渲染 Boss 画像文本。
   *
   * @param parsed 解析结果
   * @param raw 原始数据
   * @return Boss 画像文本
   */
  String renderBossProfileText(Map<String, Object> parsed, Map<String, Object> raw) {
    StringBuilder builder = new StringBuilder();
    builder.append("# 求职画像\n\n");
    builder.append("姓名: ").append(stringOf(parsed.get("name"))).append('\n');
    builder.append("摘要: ").append(stringOf(parsed.get("summary"))).append("\n\n");
    builder.append("## 结构化信息\n").append(jsonCodec.toJson(parsed)).append("\n\n");
    builder.append("## 原始信息\n").append(jsonCodec.toJson(raw)).append('\n');
    return builder.toString();
  }

  /**
   * 获取降级结果画像摘要。
   *
   * @param parsed 解析结果
   * @return 降级结果画像摘要
   */
  String fallbackProfileSummary(Map<String, Object> parsed) {
    Map<String, Object> basic = asMap(parsed.get("basic_info"));
    Map<String, Object> expectation =
        asMap(firstNonEmpty(parsed.get("job_expectations"), parsed.get("expectations")));
    Map<String, Object> status = asMap(parsed.get("job_status"));
    String name = firstText(parsed.get("name"), basic.get("name"));
    String years =
        firstText(parsed.get("years_experience"), basic.get("workYears"), basic.get("work_years"));
    String title =
        firstText(
            parsed.get("current_title"), basic.get("currentTitle"), basic.get("current_title"));
    String expectedTitle = firstText(parsed.get("expected_titles"), expectation.get("position"));
    String city = firstText(expectation.get("city"), basic.get("city"));
    String salary = firstText(expectation.get("salary"));
    String skills = shortText(parsed.get("skills"), 90);
    String advantage = firstSentence(firstText(parsed.get("personal_advantage")), 90);
    String rejects =
        firstText(
            expectation.get("rejectExcludes"),
            expectation.get("reject_excludes"),
            expectation.get("hard_excludes"),
            expectation.get("excludes"));
    String jobStatus = firstText(status.get("status"), status.get("statusDesc"));

    List<String> sentences = new ArrayList<String>();
    StringBuilder lead = new StringBuilder();
    if (!name.isEmpty()) lead.append(name).append("，");
    if (!years.isEmpty()) lead.append(years).append("经验");
    if (!title.isEmpty())
      lead.append(lead.length() > 0 && lead.charAt(lead.length() - 1) != '，' ? "，" : "")
          .append("当前方向为")
          .append(title);
    if (lead.length() > 0) sentences.add(trimSentence(lead.toString()));

    StringBuilder target = new StringBuilder();
    if (!expectedTitle.isEmpty()) target.append("目标岗位聚焦").append(shortText(expectedTitle, 50));
    if (!city.isEmpty())
      target.append(target.length() > 0 ? "，" : "").append("期望城市").append(shortText(city, 40));
    if (!salary.isEmpty())
      target.append(target.length() > 0 ? "，" : "").append("薪资").append(shortText(salary, 30));
    if (!jobStatus.isEmpty())
      target.append(target.length() > 0 ? "，" : "").append(shortText(jobStatus, 30));
    if (target.length() > 0) sentences.add(trimSentence(target.toString()));

    if (!skills.isEmpty()) sentences.add(trimSentence("核心技术栈包括" + skills));
    if (!advantage.isEmpty()) sentences.add(trimSentence(advantage));
    if (!rejects.isEmpty()) sentences.add(trimSentence("硬性排除" + shortText(rejects, 50)));
    String result = String.join("。", sentences).replaceAll("。+", "。").trim();
    if (!result.endsWith("。")) result = result + "。";
    return result.length() > 220
        ? result.substring(0, 220)
        : (result.length() <= 1
            ? "具备软件研发相关经验，关注岗位匹配度、技术栈契合度和长期发展空间，可结合岗位要求进一步补充项目亮点与技能证明。"
            : result);
  }

  /**
   * 转换为映射。
   *
   * @param value 输入值
   * @return 转换后的键值映射
   */
  Map<String, Object> asMap(Object value) {
    if (value instanceof Map) return new LinkedHashMap<String, Object>((Map<String, Object>) value);
    return new LinkedHashMap<String, Object>();
  }

  /**
   * 构建 Boss 摘要。
   *
   * @param basic 基础信息
   * @param expectations 求职期望
   * @param status 状态
   * @param user 用户
   * @param profile 画像
   * @return Boss 摘要
   */
  private String buildBossSummary(
      Map<String, Object> basic,
      Map<String, Object> expectations,
      Map<String, Object> status,
      Map<String, Object> user,
      Map<String, Object> profile) {
    String direct =
        firstString(
            basic,
            profile,
            "summary",
            "advantage",
            "personalAdvantage",
            "description",
            "intro",
            "geekDesc");
    if (!direct.isEmpty()) return direct;
    StringBuilder builder = new StringBuilder();
    String name =
        firstString(basic, user, profile, "name", "userName", "user_name", "geekName", "nickName");
    String title =
        firstString(
            basic,
            expectations,
            profile,
            "currentTitle",
            "current_title",
            "position",
            "jobTitle",
            "positionName",
            "expectPosition");
    Object exp =
        firstPresent(basic, profile, "years_experience", "workYears", "work_years", "experience");
    Object expects =
        firstPresent(
            expectations,
            profile,
            "expected_titles",
            "expectPositions",
            "expect_positions",
            "positions",
            "expectList",
            "jobExpectList");
    Object state =
        firstPresent(
            status,
            profile,
            "status",
            "statusDesc",
            "jobStatus",
            "jobStatusDesc",
            "applyStatusDesc");
    if (!name.isEmpty()) builder.append(name);
    if (!title.isEmpty()) appendPart(builder, title);
    if (exp != null) appendPart(builder, "经验 " + exp);
    if (state != null) appendPart(builder, "求职状态 " + state);
    if (expects != null) appendPart(builder, "期望 " + expects);
    return builder.length() == 0 ? "已从 Boss 直聘在线资料同步，可作为问答画像上下文。" : builder.toString();
  }

  /**
   * 获取首个非空文本。
   *
   * @param values 输入值列表
   * @return 首个非空文本
   */
  private Object firstNonEmpty(Object... values) {
    for (Object value : values)
      if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
    return null;
  }

  /**
   * 获取首个文本。
   *
   * @param values 输入值列表
   * @return 首个文本
   */
  private String firstText(Object... values) {
    Object value = firstNonEmpty(values);
    return value == null ? "" : shortText(value, 200).trim();
  }

  /**
   * 获取首个完整句子。
   *
   * @param value 输入值
   * @param maxLength 最大长度
   * @return 首个完整句子
   */
  private String firstSentence(String value, int maxLength) {
    String text = value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
    String[] parts = text.split("[。！？；;]");
    return shortText(parts.length == 0 ? text : parts[0], maxLength);
  }

  /**
   * 按句子边界裁剪文本。
   *
   * @param value 输入值
   * @return 限长后的句子
   */
  private String trimSentence(String value) {
    return value == null ? "" : value.replaceAll("[。；;]+$", "").trim();
  }

  /**
   * 生成短文本。
   *
   * @param value 输入值
   * @param maxLength 最大长度
   * @return 短文本
   */
  private String shortText(Object value, int maxLength) {
    String text =
        String.valueOf(value == null ? "" : value)
            .replaceAll("[\\[\\]{}]", "")
            .replaceAll("\\s+", " ")
            .trim();
    return text.length() > maxLength ? text.substring(0, maxLength) : text;
  }

  /**
   * 获取首个非空值。
   *
   * @param primary 主值
   * @param secondary 备用值
   * @param keys 键列表
   * @return 首个有效值
   */
  private Object firstPresent(
      Map<String, Object> primary, Map<String, Object> secondary, String... keys) {
    Object value = firstPresent(primary, keys);
    return value != null ? value : firstPresent(secondary, keys);
  }

  /**
   * 获取首个非空值。
   *
   * @param first 第一个候选值
   * @param second 第二个候选值
   * @param third 第三个候选值
   * @param keys 键列表
   * @return 首个有效值
   */
  private Object firstPresent(
      Map<String, Object> first,
      Map<String, Object> second,
      Map<String, Object> third,
      String... keys) {
    Object value = firstPresent(first, keys);
    if (value != null) return value;
    value = firstPresent(second, keys);
    return value != null ? value : firstPresent(third, keys);
  }

  /**
   * 获取首个非空值。
   *
   * @param map 数据映射
   * @param keys 键列表
   * @return 首个有效值
   */
  private Object firstPresent(Map<String, Object> map, String... keys) {
    if (map == null) return null;
    for (String key : keys) {
      Object value = map.get(key);
      if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
    }
    return null;
  }

  /**
   * 获取首个非空值深挖。
   *
   * @param primary 主值
   * @param secondary 备用值
   * @param keys 键列表
   * @return 首个非空值深挖
   */
  private Object firstPresentDeep(
      Map<String, Object> primary, Map<String, Object> secondary, String... keys) {
    Object value = firstPresent(primary, keys);
    if (value != null) return value;
    value = deepFind(primary, keys, 0);
    if (value != null) return value;
    value = firstPresent(secondary, keys);
    return value != null ? value : deepFind(secondary, keys, 0);
  }

  /**
   * 递归查找嵌套字段。
   *
   * @param node JSON 节点
   * @param keys 键列表
   * @param depth 递归深度
   * @return 递归查找结果
   */
  private Object deepFind(Object node, String[] keys, int depth) {
    if (node == null || depth > 5) return null;
    if (node instanceof Map) {
      Map map = (Map) node;
      for (String key : keys) {
        Object value = map.get(key);
        if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
      }
      for (Object value : map.values()) {
        Object found = deepFind(value, keys, depth + 1);
        if (found != null) return found;
      }
    } else if (node instanceof List) {
      for (Object item : (List) node) {
        Object found = deepFind(item, keys, depth + 1);
        if (found != null) return found;
      }
    }
    return null;
  }

  /**
   * 获取首个字符串。
   *
   * @param first 第一个候选值
   * @param second 第二个候选值
   * @param third 第三个候选值
   * @param keys 键列表
   * @return 首个字符串
   */
  private String firstString(
      Map<String, Object> first,
      Map<String, Object> second,
      Map<String, Object> third,
      String... keys) {
    Object value = firstPresent(first, second, third, keys);
    return value == null ? "" : String.valueOf(value);
  }

  /**
   * 获取首个字符串。
   *
   * @param primary 主值
   * @param secondary 备用值
   * @param keys 键列表
   * @return 首个字符串
   */
  private String firstString(
      Map<String, Object> primary, Map<String, Object> secondary, String... keys) {
    Object value = firstPresent(primary, secondary, keys);
    return value == null ? "" : String.valueOf(value);
  }

  /**
   * 追加非空文本片段。
   *
   * @param builder 文本构建器
   * @param value 输入值
   */
  private void appendPart(StringBuilder builder, String value) {
    if (value == null || value.trim().isEmpty()) return;
    if (builder.length() > 0) builder.append("，");
    builder.append(value.trim());
  }

  /**
   * 将输入值转换为字符串。
   *
   * @param value 输入值
   * @return 转换后的字符串
   */
  private String stringOf(Object value) {
    return value == null ? "" : String.valueOf(value);
  }
}
