package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.firstPresent;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.stringValue;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 选中岗位上下文解析器：把列表卡片、历史槽位和按需岗位详情统一成稳定的紧凑结构。
 *
 * <p>岗位列表默认不批量抓取 JD；只有用户明确点击分析或基于该岗位追问时，才允许按 securityId/原岗位链接加载一次详情。 这样既为后续指代提供完整证据，也保持 Boss
 * 访问的低频边界。
 */
final class SelectedJobContextResolver {
  private static final Logger log = LoggerFactory.getLogger(SelectedJobContextResolver.class);
  private static final JsonCodec JSON = new JsonCodec();
  private static final int MIN_JOB_DESCRIPTION_CHARS = 30;
  private static final int MAX_JOB_DESCRIPTION_CHARS = 2400;
  private final BossCliService bossCliService;

  SelectedJobContextResolver(BossCliService bossCliService) {
    this.bossCliService = bossCliService;
  }

  Resolution resolve(Map<String, Object> selectedJob) {
    return resolve(selectedJob, Collections.<Map<String, Object>>emptyList());
  }

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

    String securityId =
        stringValue(
            firstPresent(merged, "securityId", "security_id", "encryptJobId", "encrypt_job_id"));
    String url =
        stringValue(
            firstPresent(
                merged,
                "originalUrl",
                "jobUrl",
                "url",
                "href",
                "link",
                "detailUrl",
                "jobDetailUrl"));
    if (securityId.isEmpty() && url.isEmpty()) {
      return new Resolution(compact, false, "缺少岗位 securityId 或原岗位链接，无法加载完整 JD。");
    }

    try {
      Map<String, Object> detail = JSON.toMap(bossCliService.jobDetail(securityId, url));
      mergeDetail(merged, detail);
      compact = compact(merged);
      if (hasSufficientDescription(compact)) {
        return new Resolution(compact, true, "");
      }
      return new Resolution(compact, true, "岗位详情已加载，但没有返回可用于匹配的完整 JD。");
    } catch (BossAuthRequiredException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      String message = conciseMessage(exception);
      log.warn("按需加载选中岗位详情失败: {}", message);
      return new Resolution(compact, false, "岗位详情加载失败：" + message);
    }
  }

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

  boolean hasSufficientDescription(Map<String, Object> job) {
    String description =
        stringValue(
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
    return description.length() >= MIN_JOB_DESCRIPTION_CHARS;
  }

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
      return;
    }
  }

  @SuppressWarnings("unchecked")
  private void mergeDetail(Map<String, Object> target, Map<String, Object> detail) {
    if (detail == null || detail.isEmpty()) return;
    mergeMissing(target, detail);
    Object nested = firstPresent(detail, "job", "jobInfo", "jobDetail", "detail");
    if (nested instanceof Map) {
      mergeMissing(target, (Map<String, Object>) nested);
    }
  }

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

  private String identity(Map<String, Object> job) {
    return stringValue(
        firstPresent(
            job, "securityId", "security_id", "encryptJobId", "encrypt_job_id", "jobId", "id"));
  }

  private String label(Map<String, Object> job) {
    String name = stringValue(firstPresent(job, "jobName", "job_name", "title", "name"));
    String company = stringValue(firstPresent(job, "brandName", "companyName", "company"));
    return (company + "/" + name).trim();
  }

  private void putText(
      Map<String, Object> target, String field, Map<String, Object> source, String... keys) {
    Object value = firstPresent(source, keys);
    String text = normalizeText(value);
    if (text.isEmpty()) return;
    int limit = "description".equals(field) ? MAX_JOB_DESCRIPTION_CHARS : 1200;
    target.put(field, text.length() > limit ? text.substring(0, limit) : text);
  }

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

  private void alias(Map<String, Object> target, String alias, String source) {
    Object value = target.get(source);
    if (value != null) target.put(alias, value);
  }

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

  private String conciseMessage(Throwable error) {
    Throwable cause = error;
    while (cause != null && cause.getCause() != null && cause.getCause() != cause) {
      cause = cause.getCause();
    }
    String message = cause == null ? "" : stringValue(cause.getMessage()).trim();
    if (message.isEmpty()) message = cause == null ? "未知错误" : cause.getClass().getSimpleName();
    while (message.startsWith("岗位详情获取失败：")) {
      message = message.substring("岗位详情获取失败：".length()).trim();
    }
    return message.length() <= 180 ? message : message.substring(0, 180) + "...";
  }

  static final class Resolution {
    private final Map<String, Object> job;
    private final boolean detailLoaded;
    private final String warning;

    Resolution(Map<String, Object> job, boolean detailLoaded, String warning) {
      this.job =
          job == null
              ? Collections.<String, Object>emptyMap()
              : new LinkedHashMap<String, Object>(job);
      this.detailLoaded = detailLoaded;
      this.warning = warning == null ? "" : warning;
    }

    Map<String, Object> getJob() {
      return new LinkedHashMap<String, Object>(job);
    }

    boolean isDetailLoaded() {
      return detailLoaded;
    }

    String getWarning() {
      return warning;
    }
  }
}
