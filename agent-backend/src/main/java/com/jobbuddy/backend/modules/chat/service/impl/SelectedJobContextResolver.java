package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.firstPresent;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.stringValue;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 选中岗位上下文解析器：把当前卡片与会话推荐快照合并成稳定的紧凑结构。
 *
 * <p>岗位分析与换简历复评只消费检索阶段已经取得的岗位证据，不在交互入口访问 Boss。缺少完整 JD 时使用岗位列表结构化信息执行低置信度分析，加载职位描述保持为独立的用户动作。
 */
final class SelectedJobContextResolver {
  private static final int MIN_JOB_DESCRIPTION_CHARS = 30;
  private static final int MAX_JOB_DESCRIPTION_CHARS = 2400;

  /**
   * 解析已选岗位上下文。
   *
   * @param selectedJob 已选岗位
   * @return 解析结果
   */
  Resolution resolve(Map<String, Object> selectedJob) {
    return resolve(selectedJob, Collections.<Map<String, Object>>emptyList());
  }

  /**
   * 解析已选岗位上下文。
   *
   * @param selectedJob 已选岗位
   * @param currentJobs 当前岗位列表
   * @return 解析结果
   */
  Resolution resolve(Map<String, Object> selectedJob, List<Map<String, Object>> currentJobs) {
    Map<String, Object> merged =
        selectedJob == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(selectedJob);
    mergeMatchingCurrentJob(merged, currentJobs);
    Map<String, Object> compact = compact(merged);
    if (hasSufficientDescription(compact)) {
      return new Resolution(compact, false, "");
    }
    return new Resolution(compact, false, "当前未加载完整 JD，本次将基于岗位列表结构化信息分析。");
  }

  /**
   * 压缩文本内容。
   *
   * @param job 岗位
   * @return 压缩结果
   */
  Map<String, Object> compact(Map<String, Object> job) {
    Map<String, Object> result = new LinkedHashMap<String, Object>();
    if (job == null || job.isEmpty()) return result;

    putText(result, "securityId", job, "securityId", "security_id", "id", "jobId", "encryptJobId");
    putText(result, "jobName", job, "jobName", "job_name", "title", "name");
    putText(result, "company", job, "brandName", "companyName", "company");
    putText(result, "salary", job, "salaryDesc", "salary", "salaryText", "jobSalary");
    putText(result, "city", job, "cityName", "city", "location", "areaDistrict");
    putText(result, "experience", job, "jobExperience", "experience", "experienceName");
    putText(result, "degree", job, "jobDegree", "education", "degree", "degreeName");
    putText(
        result, "industry", job, "brandIndustry", "companyIndustry", "industry", "industryName");
    putText(
        result,
        "originalUrl",
        job,
        "originalUrl",
        "jobUrl",
        "url",
        "href",
        "link",
        "detailUrl",
        "jobDetailUrl");
    putText(
        result,
        "description",
        job,
        "jobDescription",
        "description",
        "postDescription",
        "jobDesc",
        "jobSecText",
        "detailText",
        "jobRequire",
        "jobContent");
    putList(result, "skills", job, "skills", "skillList", "skillLabels");
    putList(result, "jobLabels", job, "jobLabels", "labels", "welfareList");

    // 同时保留 resume_match 工具使用的规范字段，避免紧凑槽位在执行阶段再次丢失公司、薪资和 JD。
    alias(result, "brandName", "company");
    alias(result, "salaryDesc", "salary");
    alias(result, "cityName", "city");
    alias(result, "jobExperience", "experience");
    alias(result, "jobDegree", "degree");
    alias(result, "jobDescription", "description");
    return result;
  }

  /**
   * 判断岗位描述是否充分。
   *
   * @param job 岗位
   * @return 岗位描述是否充分是否成立
   */
  boolean hasSufficientDescription(Map<String, Object> job) {
    return descriptionText(job).length() >= MIN_JOB_DESCRIPTION_CHARS;
  }

  /**
   * 合并当前岗位匹配结果。
   *
   * @param selectedJob 已选岗位
   * @param currentJobs 当前岗位列表
   */
  private void mergeMatchingCurrentJob(
      Map<String, Object> selectedJob, List<Map<String, Object>> currentJobs) {
    if (selectedJob.isEmpty() || currentJobs == null || currentJobs.isEmpty()) return;
    String selectedId = identity(selectedJob);
    for (Map<String, Object> candidate : currentJobs) {
      if (candidate == null || candidate.isEmpty()) continue;
      String candidateId = identity(candidate);
      boolean sameIdentity = !selectedId.isEmpty() && selectedId.equals(candidateId);
      boolean sameLabel =
          selectedId.isEmpty()
              && label(selectedJob).equals(label(candidate))
              && !label(selectedJob).isEmpty();
      if (!sameIdentity && !sameLabel) continue;
      mergeMissing(selectedJob, candidate);
      preferSufficientDescription(selectedJob, candidate);
      return;
    }
  }

  /**
   * 当前卡片只有摘要时，优先采用同一推荐快照中的完整职位描述。
   *
   * @param target 当前卡片
   * @param source 推荐快照
   */
  private void preferSufficientDescription(Map<String, Object> target, Map<String, Object> source) {
    String current = descriptionText(target);
    if (current.length() >= MIN_JOB_DESCRIPTION_CHARS) return;
    String candidate = descriptionText(source);
    if (candidate.length() >= MIN_JOB_DESCRIPTION_CHARS) {
      target.put("jobDescription", candidate);
    }
  }

  /**
   * 读取并规范化职位描述。
   *
   * @param job 岗位
   * @return 职位描述
   */
  private String descriptionText(Map<String, Object> job) {
    return normalizeText(
        firstPresent(
            job,
            "jobDescription",
            "description",
            "postDescription",
            "jobDesc",
            "jobSecText",
            "detailText",
            "jobRequire",
            "jobContent"));
  }

  /**
   * 补充缺失字段。
   *
   * @param target 待补全的岗位上下文
   * @param source 源数据
   */
  private void mergeMissing(Map<String, Object> target, Map<String, Object> source) {
    for (Map.Entry<String, Object> entry : source.entrySet()) {
      Object value = entry.getValue();
      if (value == null || stringValue(value).trim().isEmpty()) continue;
      Object existing = target.get(entry.getKey());
      if (existing == null || stringValue(existing).trim().isEmpty()) {
        target.put(entry.getKey(), value);
      }
    }
  }

  /**
   * 获取身份。
   *
   * @param job 岗位
   * @return 身份
   */
  private String identity(Map<String, Object> job) {
    return stringValue(
        firstPresent(
            job, "securityId", "security_id", "encryptJobId", "encrypt_job_id", "jobId", "id"));
  }

  /**
   * 获取展示标签。
   *
   * @param job 岗位
   * @return 展示标签
   */
  private String label(Map<String, Object> job) {
    String name = stringValue(firstPresent(job, "jobName", "job_name", "title", "name"));
    String company = stringValue(firstPresent(job, "brandName", "companyName", "company"));
    return (company + "/" + name).trim();
  }

  /**
   * 写入文本。
   *
   * @param target 待写入的岗位上下文
   * @param field 字段名称
   * @param source 源数据
   * @param keys 键列表
   */
  private void putText(
      Map<String, Object> target, String field, Map<String, Object> source, String... keys) {
    Object value = firstPresent(source, keys);
    String text = normalizeText(value);
    if (text.isEmpty()) return;
    int limit = "description".equals(field) ? MAX_JOB_DESCRIPTION_CHARS : 1200;
    target.put(field, text.length() > limit ? text.substring(0, limit) : text);
  }

  /**
   * 写入列表。
   *
   * @param target 待写入的岗位上下文
   * @param field 字段名称
   * @param source 源数据
   * @param keys 键列表
   */
  private void putList(
      Map<String, Object> target, String field, Map<String, Object> source, String... keys) {
    Object value = firstPresent(source, keys);
    if (value == null) return;
    List<String> values = new ArrayList<String>();
    if (value instanceof List) {
      for (Object item : (List<?>) value) {
        String text = normalizeText(item);
        if (!text.isEmpty() && !values.contains(text)) values.add(text);
        if (values.size() >= 15) break;
      }
    } else {
      String text = normalizeText(value);
      if (!text.isEmpty()) values.add(text);
    }
    if (!values.isEmpty()) target.put(field, values);
  }

  /**
   * 解析岗位字段别名。
   *
   * @param target 待补充别名的岗位上下文
   * @param alias 字段别名
   * @param source 源数据
   */
  private void alias(Map<String, Object> target, String alias, String source) {
    Object value = target.get(source);
    if (value != null) target.put(alias, value);
  }

  /**
   * 规范化文本。
   *
   * @param value 输入值
   * @return 规范化后的文本
   */
  private String normalizeText(Object value) {
    if (value == null) return "";
    String raw = String.valueOf(value).replace("\r\n", "\n").replace('\r', '\n');
    StringBuilder builder = new StringBuilder();
    for (String line : raw.split("\\n+")) {
      String text = line == null ? "" : line.replace('\t', ' ').trim().replaceAll(" {2,}", " ");
      if (text.isEmpty() || "null".equalsIgnoreCase(text)) continue;
      if (builder.length() > 0) builder.append('\n');
      builder.append(text);
    }
    return builder.toString();
  }

  /**
   * 定义解析结果。
   */
  static final class Resolution {
    private final Map<String, Object> job;
    private final boolean detailLoaded;
    private final String warning;

    /**
     * 创建解析结果实例。
     *
     * @param job 岗位
     * @param detailLoaded 详情是否已加载
     * @param warning 警告
     */
    Resolution(Map<String, Object> job, boolean detailLoaded, String warning) {
      this.job =
          job == null
              ? Collections.<String, Object>emptyMap()
              : new LinkedHashMap<String, Object>(job);
      this.detailLoaded = detailLoaded;
      this.warning = warning == null ? "" : warning;
    }

    /**
     * 获取岗位。
     *
     * @return 岗位
     */
    Map<String, Object> getJob() {
      return new LinkedHashMap<String, Object>(job);
    }

    /**
     * 判断岗位详情是否已加载。
     *
     * @return 岗位详情是否已加载是否成立
     */
    boolean isDetailLoaded() {
      return detailLoaded;
    }

    /**
     * 获取警告信息。
     *
     * @return 警告信息
     */
    String getWarning() {
      return warning;
    }
  }
}
