package com.jobbuddy.backend.modules.job.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.analysis.dto.AnalysisPartialResult;
import com.jobbuddy.backend.modules.auth.dto.internal.BossFavoriteListResult;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteAnalysisCommand;
import com.jobbuddy.backend.modules.job.dto.command.JobFavoriteSaveCommand;
import com.jobbuddy.backend.modules.job.dto.request.BossFavoriteImportRequest;
import com.jobbuddy.backend.modules.job.dto.response.BossFavoriteImportItemResponse;
import com.jobbuddy.backend.modules.job.dto.response.BossFavoriteImportResponse;
import com.jobbuddy.backend.modules.job.dto.response.BossFavoritePreviewResponse;
import com.jobbuddy.backend.modules.job.dto.response.JobFavoriteResponse;
import com.jobbuddy.backend.modules.job.exception.JobAnalysisException;
import com.jobbuddy.backend.modules.job.mapper.JobFavoriteMapper;
import com.jobbuddy.backend.modules.job.service.JobFavoriteService;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeSummaryResponse;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.function.Consumer;
import org.springframework.stereotype.Service;

/** Manages persisted favorite-job snapshots for the current default user. */
@Service
public class JobFavoriteServiceImpl implements JobFavoriteService {
  private final JobFavoriteMapper mapper;
  private final JsonCodec jsonCodec;
  private final JobBuddyProperties properties;
  private final JobRuntimeService jobRuntimeService;
  private final ResumeStorageService resumeStorageService;
  private final BossCliService bossCliService;

  public JobFavoriteServiceImpl(
      JobFavoriteMapper mapper,
      JsonCodec jsonCodec,
      JobBuddyProperties properties,
      JobRuntimeService jobRuntimeService,
      ResumeStorageService resumeStorageService,
      BossCliService bossCliService) {
    this.mapper = mapper;
    this.jsonCodec = jsonCodec;
    this.properties = properties;
    this.jobRuntimeService = jobRuntimeService;
    this.resumeStorageService = resumeStorageService;
    this.bossCliService = bossCliService;
  }

  public List<JobFavoriteResponse> listFavorites(String userId) {
    List<JobFavoriteResponse> result = new ArrayList<JobFavoriteResponse>();
    for (Map<String, Object> row : mapper.listFavorites(userId)) {
      result.add(jobResponse(toJob(row)));
    }
    return result;
  }

  public void saveFavorite(String userId, JobFavoriteSaveCommand command) {
    if (command == null || command.isEmpty()) {
      return;
    }

    persistFavoriteSnapshot(userId, ensureJobDescription(jsonCodec.toMap(command.snapshot())));
  }

  public BossFavoritePreviewResponse previewBossFavorites(
      String userId, int page, boolean forceRefresh) {
    BossFavoriteListResult source = bossCliService.favoriteJobs(Math.max(1, page), forceRefresh);
    Set<String> importedKeys = importedIdentityKeys(userId);
    Set<String> pageKeys = new LinkedHashSet<String>();
    List<JsonNode> jobs = new ArrayList<JsonNode>();
    for (JsonNode sourceJob : source.getJobs()) {
      if (sourceJob == null || !sourceJob.isObject()) continue;
      ObjectNode job = ((ObjectNode) sourceJob.deepCopy());
      String key = externalJobKey(jsonCodec.toMap(job));
      if (key.isEmpty() || importedKeys.contains(key) || !pageKeys.add(key)) continue;
      job.put("favoriteKey", key);
      jobs.add(job);
    }
    return new BossFavoritePreviewResponse(
        jobs,
        source.getPage(),
        source.isHasMore() && source.getPage() < source.getTotalPages(),
        source.getTotalCount(),
        source.getTotalPages(),
        source.getRate());
  }

  public BossFavoriteImportResponse importBossFavorites(
      String userId, BossFavoriteImportRequest request) {
    List<JsonNode> requested = request == null ? new ArrayList<JsonNode>() : request.getJobs();
    if (requested.isEmpty()) throw new IllegalArgumentException("请至少选择一个 Boss 岗位");

    List<BossFavoriteImportItemResponse> items = new ArrayList<BossFavoriteImportItemResponse>();
    Set<String> seen = new LinkedHashSet<String>();
    Set<String> importedKeys = importedIdentityKeys(userId);
    int imported = 0;
    int existing = 0;
    int failed = 0;
    int unprocessed = 0;
    boolean stopped = false;
    boolean authRequired = false;
    String stoppedReason = null;
    JsonNode authData = null;

    for (int index = 0; index < requested.size(); index++) {
      JsonNode node = requested.get(index);
      Map<String, Object> job = jsonCodec.toMap(node);
      String key = externalJobKey(job);
      if (key.isEmpty()) {
        failed++;
        items.add(
            new BossFavoriteImportItemResponse("", "failed", "缺少 Boss 岗位 securityId，未发起详情请求"));
        continue;
      }
      if (!seen.add(key) || importedKeys.contains(key)) {
        existing++;
        items.add(new BossFavoriteImportItemResponse(key, "already_imported", "本地收藏中已存在"));
        continue;
      }
      try {
        job.put("favoriteKey", key);
        job = ensureJobDescription(job);
        persistFavoriteSnapshot(userId, job);
        imported++;
        importedKeys.addAll(jobIdentityKeys(job));
        importedKeys.add(key);
        items.add(new BossFavoriteImportItemResponse(key, "imported", "岗位详情已补全并导入"));
      } catch (BossAuthRequiredException exception) {
        failed++;
        stopped = true;
        authRequired = true;
        stoppedReason = exception.getMessage();
        authData = jsonCodec.toTree(exception.getAuthData());
        items.add(new BossFavoriteImportItemResponse(key, "failed", stoppedReason));
      } catch (RuntimeException exception) {
        failed++;
        stopped = true;
        stoppedReason = safeImportError(exception);
        items.add(new BossFavoriteImportItemResponse(key, "failed", stoppedReason));
      }
      if (stopped) {
        for (int remaining = index + 1; remaining < requested.size(); remaining++) {
          String remainingKey = externalJobKey(jsonCodec.toMap(requested.get(remaining)));
          items.add(
              new BossFavoriteImportItemResponse(
                  remainingKey, "not_processed", "前序请求异常，为保护账号已停止后续导入"));
          unprocessed++;
        }
        break;
      }
    }
    return new BossFavoriteImportResponse(
        imported,
        existing,
        failed,
        unprocessed,
        stopped,
        authRequired,
        stoppedReason,
        authData,
        items,
        listFavorites(userId));
  }

  public void removeFavorite(String userId, String jobKey) {
    if (jobKey == null || jobKey.trim().isEmpty()) {
      return;
    }
    mapper.removeFavorite(userId, jobKey);
  }

  public JobFavoriteResponse analyzeFavorite(String userId, JobFavoriteAnalysisCommand command) {
    String jobKey = command == null ? null : command.getJobKey();
    String resumeId = command == null ? null : command.getResumeId();
    if (jobKey == null || jobKey.trim().isEmpty()) {
      throw new IllegalArgumentException("缺少收藏岗位标识");
    }
    Map<String, Object> row = mapper.findFavorite(userId, jobKey.trim());
    if (row == null) {
      throw new IllegalArgumentException("收藏岗位不存在: " + jobKey);
    }
    Map<String, Object> job = ensurePersistedJobDescription(userId, toJob(row));
    job = attachAnalysis(userId, job, resumeId);
    persistAnalysis(userId, jobKey.trim(), job);
    return jobResponse(job);
  }

  public JobFavoriteResponse analyzeJob(
      String userId, JobFavoriteSaveCommand command, String resumeId) {
    Map<String, Object> body =
        command == null ? new LinkedHashMap<String, Object>() : jsonCodec.toMap(command.snapshot());
    if (body.isEmpty()) {
      throw new IllegalArgumentException("缺少岗位信息");
    }
    String key = jobKey(body);
    Map<String, Object> row =
        (key == null || key.trim().isEmpty()) ? null : mapper.findFavorite(userId, key.trim());
    Map<String, Object> job;
    if (row != null) {
      job = ensurePersistedJobDescription(userId, toJob(row));
    } else {
      job = new LinkedHashMap<String, Object>(body);
      job.remove("resumeId");
    }
    job = attachAnalysis(userId, job, resumeId);
    if (row != null) {
      persistAnalysis(userId, key.trim(), job);
    }
    return jobResponse(job);
  }

  public JobFavoriteResponse analyzeJobIncrementally(
      String userId,
      JobFavoriteSaveCommand command,
      String resumeId,
      Consumer<AnalysisPartialResult> consumer) {
    Map<String, Object> body =
        command == null ? new LinkedHashMap<String, Object>() : jsonCodec.toMap(command.snapshot());
    if (body.isEmpty()) throw new IllegalArgumentException("缺少岗位信息");
    String key = jobKey(body);
    Map<String, Object> row =
        key == null || key.trim().isEmpty() ? null : mapper.findFavorite(userId, key.trim());
    Map<String, Object> job =
        row == null
            ? new LinkedHashMap<String, Object>(body)
            : ensurePersistedJobDescription(userId, toJob(row));
    job.remove("resumeId");
    requireJobDescription(job);
    ResumeRecord resume = resolveResumeForAnalysis(userId, resumeId);
    Map<String, Object> mergedMatch = new LinkedHashMap<String, Object>();
    List<List<String>> groups =
        java.util.Arrays.asList(
            java.util.Arrays.asList(
                "score",
                "score_confidence",
                "recommendation",
                "reasoning",
                "evidence",
                "hits",
                "gaps"),
            java.util.Arrays.asList("dimensions", "risks", "limitations"),
            java.util.Arrays.asList("interview_focus", "improvement_actions"));
    String[] sections = {"overview", "evidence", "actions"};
    String[] messages = {"投递结论与关键证据已生成", "能力维度与风险已生成", "简历补强与面试方案已生成"};
    Object schema = null;
    for (int index = 0; index < groups.size(); index++) {
      throwIfAnalysisCancelled();
      Map<String, Object> output;
      try {
        output =
            jobRuntimeService.matchResumeSections(
                resume, java.util.Collections.singletonList(job), null, groups.get(index));
      } catch (RuntimeException exception) {
        throw new JobAnalysisException("岗位匹配服务执行失败，请稍后重试", exception);
      }
      throwIfAnalysisCancelled();
      Object matches = output.get("matches");
      if (!(matches instanceof List)
          || ((List) matches).isEmpty()
          || !(((List) matches).get(0) instanceof Map)) {
        throw new JobAnalysisException("岗位分析未生成有效分组结果，请稍后重试");
      }
      mergedMatch.putAll((Map<String, Object>) ((List) matches).get(0));
      if (output.get("schema") != null) schema = output.get("schema");
      if (output.get("evaluation_schema") != null) schema = output.get("evaluation_schema");
      Map<String, Object> partialAnalysis = new LinkedHashMap<String, Object>();
      partialAnalysis.put("resumeId", resume.getResumeId());
      partialAnalysis.put("resumeName", resume.getOriginalName());
      partialAnalysis.put("schema", schema);
      partialAnalysis.put("match", new LinkedHashMap<String, Object>(mergedMatch));
      Map<String, Object> partialJob = new LinkedHashMap<String, Object>(job);
      partialJob.put("analysis", partialAnalysis);
      if (consumer != null)
        consumer.accept(
            new AnalysisPartialResult(
                sections[index], messages[index], jsonCodec.toTree(partialJob)));
      throwIfAnalysisCancelled();
    }
    Map<String, Object> analysis = new LinkedHashMap<String, Object>();
    analysis.put("resumeId", resume.getResumeId());
    analysis.put("resumeName", resume.getOriginalName());
    analysis.put("analyzedAt", Instant.now().toString());
    analysis.put("schema", schema);
    analysis.put("match", mergedMatch);
    job.put("analysis", analysis);
    job.put("analyzedAt", analysis.get("analyzedAt"));
    throwIfAnalysisCancelled();
    if (row != null) persistAnalysis(userId, key.trim(), job);
    return jobResponse(job);
  }

  private void throwIfAnalysisCancelled() {
    if (Thread.currentThread().isInterrupted()) throw new CancellationException("岗位分析已取消");
  }

  private Map<String, Object> attachAnalysis(
      String userId, Map<String, Object> job, String resumeId) {
    requireJobDescription(job);
    ResumeRecord resume = resolveResumeForAnalysis(userId, resumeId);
    Map<String, Object> match;
    try {
      match = jobRuntimeService.matchResume(resume, java.util.Collections.singletonList(job), null);
    } catch (RuntimeException exception) {
      throw new JobAnalysisException("岗位匹配服务执行失败，请稍后重试", exception);
    }

    Object matches = match.get("matches");
    if (!(matches instanceof List)
        || ((List) matches).isEmpty()
        || !(((List) matches).get(0) instanceof Map)) {
      throw new JobAnalysisException("岗位分析未生成有效结果，请稍后重试");
    }

    Map<String, Object> analysis = new LinkedHashMap<String, Object>();
    analysis.put("resumeId", resume.getResumeId());
    analysis.put("resumeName", resume.getOriginalName());
    analysis.put("analyzedAt", Instant.now().toString());
    analysis.put("schema", match.get("schema"));
    analysis.put("match", ((List) matches).get(0));
    job.put("analysis", analysis);
    job.put("analyzedAt", analysis.get("analyzedAt"));
    return job;
  }

  private void persistAnalysis(String userId, String jobKey, Map<String, Object> job) {
    Object analysisValue = job.get("analysis");
    if (!(analysisValue instanceof Map)) {
      throw new IllegalStateException("岗位分析结果结构无效");
    }
    Map analysis = (Map) analysisValue;
    Instant analyzedAt = Instant.parse(string(analysis.get("analyzedAt")));
    mapper.updateAnalysis(userId, jobKey, jsonCodec.toJson(analysis), analyzedAt);
  }

  private void persistFavoriteSnapshot(String userId, Map<String, Object> job) {
    Map<String, Object> payload =
        job == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(job);
    String key = jobKey(payload);
    payload.remove("analysis");
    payload.remove("analyzedAt");
    payload.put("favoriteKey", key);
    if (!payload.containsKey("favoritedAt")
        || String.valueOf(payload.get("favoritedAt")).trim().isEmpty()) {
      payload.put("favoritedAt", Instant.now().toString());
    }
    payload.put("updatedAt", Instant.now().toString());

    mapper.upsertFavorite(userId, key, jsonCodec.toJson(payload));
  }

  private Map<String, Object> ensurePersistedJobDescription(
      String userId, Map<String, Object> job) {
    if (hasJobDescription(job)) {
      putCanonicalJobDescription(job);
      return job;
    }
    Map<String, Object> completed = ensureJobDescription(job);
    persistFavoriteSnapshot(userId, completed);
    return completed;
  }

  private Map<String, Object> ensureJobDescription(Map<String, Object> snapshot) {
    Map<String, Object> job =
        snapshot == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(snapshot);
    if (hasJobDescription(job)) {
      putCanonicalJobDescription(job);
      return job;
    }

    String securityId =
        string(firstPresent(job, "securityId", "security_id", "encryptJobId", "encrypt_job_id"));
    String url =
        string(firstPresent(job, "originalUrl", "jobUrl", "url", "href", "link", "detailUrl"));
    if (securityId.trim().isEmpty() && url.trim().isEmpty()) {
      throw new IllegalArgumentException("缺少岗位详情链接或 securityId，无法在收藏时采集职位描述");
    }

    Map<String, Object> detail = jsonCodec.toMap(bossCliService.jobDetail(securityId, url));
    if (detail != null) {
      job.putAll(detail);
    }
    if (!hasJobDescription(job)) {
      throw new IllegalArgumentException("未获取到职位描述，本次岗位未收藏，请稍后重试或打开 Boss 原岗位查看");
    }
    putCanonicalJobDescription(job);
    return job;
  }

  private void requireJobDescription(Map<String, Object> job) {
    if (!hasJobDescription(job)) {
      throw new IllegalArgumentException("岗位缺少职位描述，请先查看职位描述或稍后重试");
    }
  }

  private boolean hasJobDescription(Map<String, Object> job) {
    return firstPresent(
            job,
            "jobDescription",
            "description",
            "postDescription",
            "jobDesc",
            "jobSecText",
            "detailText",
            "jobRequire",
            "jobContent")
        != null;
  }

  private void putCanonicalJobDescription(Map<String, Object> job) {
    Object description =
        firstPresent(
            job,
            "jobDescription",
            "description",
            "postDescription",
            "jobDesc",
            "jobSecText",
            "detailText",
            "jobRequire",
            "jobContent");
    if (description != null) {
      job.put("jobDescription", description);
    }
  }

  private ResumeRecord resolveResumeForAnalysis(String userId, String resumeId) {
    if (resumeId != null && !resumeId.trim().isEmpty()) {
      ResumeRecord record = resumeStorageService.get(resumeId.trim(), userId);
      if (record == null) throw new IllegalArgumentException("简历不存在: " + resumeId);
      return record;
    }
    for (ResumeSummaryResponse summary : resumeStorageService.list(userId)) {
      if ("success".equals(summary.getParseStatus())
          && summary.getParsed() != null
          && !summary.getParsed().isEmpty()) {
        ResumeRecord record = resumeStorageService.get(summary.getResumeId(), userId);
        if (record != null) return record;
      }
    }
    throw new IllegalArgumentException("未找到已解析的简历，请先上传并解析简历后再分析岗位");
  }

  private JobFavoriteResponse jobResponse(Map<String, Object> job) {
    return new JobFavoriteResponse(jsonCodec.toTree(job));
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

  private Set<String> importedIdentityKeys(String userId) {
    Set<String> keys = new LinkedHashSet<String>();
    for (Map<String, Object> row : mapper.listFavorites(userId)) {
      addIdentity(keys, row.get("jobKey"));
      String jobJson = string(row.get("jobJson"));
      if (!jobJson.trim().isEmpty()) keys.addAll(jobIdentityKeys(jsonCodec.toMap(jobJson)));
    }
    return keys;
  }

  private Set<String> jobIdentityKeys(Map<String, Object> job) {
    Set<String> keys = new LinkedHashSet<String>();
    if (job == null) return keys;
    addIdentity(keys, job.get("encryptJobId"));
    addIdentity(keys, job.get("encrypt_job_id"));
    addIdentity(keys, job.get("favoriteKey"));
    addIdentity(keys, job.get("jobKey"));
    addIdentity(keys, job.get("securityId"));
    addIdentity(keys, job.get("security_id"));
    addIdentity(keys, job.get("jobId"));
    addIdentity(keys, job.get("id"));
    return keys;
  }

  private void addIdentity(Set<String> keys, Object value) {
    String candidate = string(value).trim();
    if (!candidate.isEmpty() && !"0".equals(candidate)) keys.add(candidate);
  }

  private String externalJobKey(Map<String, Object> job) {
    Object key =
        firstPresent(
            job,
            "encryptJobId",
            "encrypt_job_id",
            "favoriteKey",
            "jobKey",
            "securityId",
            "security_id",
            "jobId",
            "id");
    return key == null ? "" : String.valueOf(key).trim();
  }

  private String safeImportError(RuntimeException exception) {
    String message = exception == null ? "" : String.valueOf(exception.getMessage()).trim();
    if (message.isEmpty() || "null".equals(message)) return "岗位详情读取失败，为保护账号已停止后续导入";
    return message;
  }

  private String jobKey(Map<String, Object> job) {
    Object key =
        firstPresent(job, "jobKey", "favoriteKey", "encryptJobId", "securityId", "id", "jobId");
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
