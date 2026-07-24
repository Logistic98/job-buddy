package com.jobbuddy.backend.modules.resume.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.analysis.dto.AnalysisPartialResult;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolArguments;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeToolResult;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeAssetUploadResponse;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeProfileSummaryResponse;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeSummaryResponse;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.mapper.ResumeAssetMapper;
import com.jobbuddy.backend.modules.resume.repository.ResumeRecordRepository;
import com.jobbuddy.backend.modules.resume.service.ResumeParsedContent;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import com.jobbuddy.backend.modules.resume.storage.ResumeObjectStorage;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

/** 简历存储服务：负责简历与附件的上传下载、求职画像的同步与保存、 解析/分析编排以及归属校验。签名令牌、缩略图渲染、Boss 画像归一化 和工具结果落盘分别由同包协作类承担。 */
@Service
public class ResumeStorageServiceImpl implements ResumeStorageService {

  private static final Logger LOG = LoggerFactory.getLogger(ResumeStorageService.class);
  private static final java.util.Set<String> ALLOWED_SUFFIXES =
      new java.util.HashSet<String>(java.util.Collections.singletonList("pdf"));
  private static final int DEFAULT_LIST_LIMIT = 50;
  private static final Duration THUMBNAIL_CACHE_TTL = Duration.ofHours(24);
  private static final String FALLBACK_TENANT_ID = "default-tenant";

  private final JobBuddyProperties properties;
  private final RuntimeToolClient toolClient;
  private final ResumeRecordRepository resumeRecordRepository;
  private final ResumeObjectStorage resumeObjectStorage;
  private final BossCliService bossCliService;
  private final ResumeAssetTokenSigner assetTokenSigner;
  private final ResumeThumbnailRenderer thumbnailRenderer;
  private final BossProfileNormalizer profileNormalizer;
  private final ResumeToolResultApplier toolResultApplier;
  private final StringRedisTemplate redisTemplate;
  private final JsonCodec jsonCodec;
  private ResumeAssetMapper resumeAssetMapper;

  @Autowired
  public ResumeStorageServiceImpl(
      JobBuddyProperties properties,
      RuntimeToolClient toolClient,
      ResumeRecordRepository resumeRecordRepository,
      ResumeObjectStorage resumeObjectStorage,
      BossCliService bossCliService,
      JsonCodec jsonCodec,
      StringRedisTemplate redisTemplate) {
    this.properties = properties;
    this.toolClient = toolClient;
    this.resumeRecordRepository = resumeRecordRepository;
    this.resumeObjectStorage = resumeObjectStorage;
    this.bossCliService = bossCliService;
    this.assetTokenSigner = new ResumeAssetTokenSigner(properties, jsonCodec);
    this.thumbnailRenderer = new ResumeThumbnailRenderer();
    this.profileNormalizer = new BossProfileNormalizer(jsonCodec);
    this.toolResultApplier = new ResumeToolResultApplier();
    this.redisTemplate = redisTemplate;
    this.jsonCodec = jsonCodec;
  }

  public ResumeStorageServiceImpl(
      JobBuddyProperties properties,
      RuntimeToolClient toolClient,
      ResumeRecordRepository resumeRecordRepository,
      ResumeObjectStorage resumeObjectStorage,
      BossCliService bossCliService,
      JsonCodec jsonCodec) {
    this(
        properties,
        toolClient,
        resumeRecordRepository,
        resumeObjectStorage,
        bossCliService,
        jsonCodec,
        null);
  }

  @Autowired(required = false)
  void setResumeAssetMapper(ResumeAssetMapper resumeAssetMapper) {
    this.resumeAssetMapper = resumeAssetMapper;
  }

  public ResumeRecord upload(MultipartFile file, String tenantId, String userId)
      throws IOException {
    validateFile(file);
    String original = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename();
    String suffix = ResumeAssetTokenSigner.extractSuffix(original);
    if (!ALLOWED_SUFFIXES.contains(suffix)) {
      throw new IllegalArgumentException("不支持的简历格式: " + suffix + ",仅支持 PDF");
    }

    String effectiveUser = defaultUser(userId);
    String effectiveTenant = effectiveTenant(tenantId, effectiveUser);
    String resumeId = "resume_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String objectName = effectiveUser + "/" + resumeId + "." + suffix;
    resumeObjectStorage.upload(file, objectName);

    ResumeRecord record = new ResumeRecord();
    record.setResumeId(resumeId);
    record.setTenantId(effectiveTenant);
    record.setUserId(effectiveUser);
    record.setOriginalName(original);
    record.setStoragePath(objectName);
    record.setSizeBytes(file.getSize());
    record.setSuffix(suffix);
    record.setUploadedAt(Instant.now());
    record.setParseStatus("pending");
    record.setParsed(new LinkedHashMap<String, Object>());
    resumeRecordRepository.save(record);
    LOG.info(
        "简历上传成功 - resumeId: {}, tenant: {}, user: {}, bucket: {}, object: {}, size: {}",
        resumeId,
        effectiveTenant,
        effectiveUser,
        resumeObjectStorage.bucket(),
        objectName,
        file.getSize());
    return record;
  }

  public ResumeAssetUploadResponse uploadAsset(MultipartFile file, String tenantId, String userId)
      throws IOException {
    validateFile(file);
    String original = file.getOriginalFilename() == null ? "asset" : file.getOriginalFilename();
    String suffix = ResumeAssetTokenSigner.extractSuffix(original);
    if (!ResumeAssetTokenSigner.ALLOWED_ASSET_SUFFIXES.contains(suffix)) {
      throw new IllegalArgumentException("不支持的图片格式: " + suffix + ",仅支持 JPG / PNG / WebP");
    }
    String effectiveUser = defaultUser(userId);
    String effectiveTenant = effectiveTenant(tenantId, effectiveUser);
    byte[] content = file.getBytes();
    String digest = sha256(content);
    String assetId = "asset_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    String objectName = effectiveUser + "/assets/" + assetId + "." + suffix;
    resumeObjectStorage.uploadBytes(content, objectName, file.getContentType());
    try {
      if (resumeAssetMapper != null) {
        Map<String, Object> asset = new LinkedHashMap<String, Object>();
        asset.put("assetId", assetId);
        asset.put("tenantId", effectiveTenant);
        asset.put("userId", effectiveUser);
        asset.put("fileName", original);
        asset.put(
            "contentType",
            file.getContentType() == null ? "application/octet-stream" : file.getContentType());
        asset.put("storagePath", objectName);
        asset.put("sizeBytes", content.length);
        asset.put("sha256", digest);
        asset.put("createdAt", Instant.now());
        resumeAssetMapper.insertAsset(asset);
      }
    } catch (RuntimeException e) {
      try {
        resumeObjectStorage.deleteObject(objectName);
      } catch (RuntimeException cleanupError) {
        e.addSuppressed(cleanupError);
      }
      throw e;
    }
    return jsonCodec.convert(
        assetResponse(assetId, file.getContentType(), file.getSize()),
        ResumeAssetUploadResponse.class);
  }

  public InputStream openAsset(String assetToken, String userId) {
    return resumeObjectStorage.openObjectStream(resolveAssetObjectName(assetToken, userId));
  }

  public String assetContentType(String assetToken, String userId) {
    try {
      String objectName = resolveAssetObjectName(assetToken, userId);
      String suffix = ResumeAssetTokenSigner.extractSuffix(objectName);
      if ("png".equals(suffix)) return "image/png";
      if ("webp".equals(suffix)) return "image/webp";
      return "image/jpeg";
    } catch (Exception e) {
      return "application/octet-stream";
    }
  }

  public ResumeRecord syncBossOnlineResume(String tenantId, String userId) throws IOException {
    String effectiveUser = defaultUser(userId);
    String effectiveTenant = effectiveTenant(tenantId, effectiveUser);
    Map<String, Object> profile = jsonCodec.toMap(bossCliService.fetchOnlineProfile());
    Map<String, Object> parsed = profileNormalizer.normalizeBossProfile(profile);
    ResumeRecord record = upsertProfileRecord(effectiveTenant, effectiveUser, parsed, profile);
    LOG.info(
        "求职画像同步成功 - resumeId: {}, tenant: {}, user: {}, size: {}",
        record.getResumeId(),
        effectiveTenant,
        effectiveUser,
        record.getSizeBytes());
    return record;
  }

  public ResumeSummaryResponse getJobProfileOrEmpty(String userId) {
    return getJobProfileOrEmpty(null, userId);
  }

  public ResumeSummaryResponse getJobProfileOrEmpty(String tenantId, String userId) {
    String effectiveUser = defaultUser(userId);
    ResumeRecord existing = findJobProfile(tenantId, effectiveUser);
    if (existing != null) return summarize(existing);
    Map<String, Object> view = new LinkedHashMap<String, Object>();
    view.put("resumeId", null);
    view.put("userId", effectiveUser);
    view.put("originalName", "求职画像");
    view.put("sizeBytes", 0L);
    view.put("suffix", "txt");
    view.put("uploadedAt", null);
    view.put("parseStatus", "draft");
    view.put("parsed", profileNormalizer.emptyJobProfile());
    view.put("parseError", null);
    return jsonCodec.convert(view, ResumeSummaryResponse.class);
  }

  public ResumeRecord getOrCreateJobProfile(String userId) throws IOException {
    String effectiveUser = defaultUser(userId);
    ResumeRecord existing = findJobProfile(null, effectiveUser);
    if (existing != null) return existing;
    Map<String, Object> parsed = profileNormalizer.emptyJobProfile();
    return upsertProfileRecord(
        effectiveTenant(null, effectiveUser),
        effectiveUser,
        parsed,
        Collections.<String, Object>emptyMap());
  }

  public ResumeRecord saveJobProfile(String tenantId, String userId, JsonNode parsed)
      throws IOException {
    String effectiveUser = defaultUser(userId);
    String effectiveTenant = effectiveTenant(tenantId, effectiveUser);
    Map<String, Object> safeParsed =
        parsed == null
            ? profileNormalizer.emptyJobProfile()
            : new LinkedHashMap<String, Object>(jsonCodec.toMap(parsed));
    profileNormalizer.ensureProfileSource(safeParsed, "手动填写", null);
    return upsertProfileRecord(
        effectiveTenant,
        effectiveUser,
        safeParsed,
        profileNormalizer.asMap(profileNormalizer.asMap(safeParsed.get("source")).get("raw")));
  }

  public ResumeProfileSummaryResponse generateJobProfileSummary(JsonNode parsed, String sessionId) {
    Map<String, Object> safeParsed =
        parsed == null
            ? profileNormalizer.emptyJobProfile()
            : new LinkedHashMap<String, Object>(jsonCodec.toMap(parsed));
    String oldSummary = stringOf(safeParsed.get("summary"));
    Map<String, Object> args = new LinkedHashMap<String, Object>();
    args.put("profile", safeParsed);
    try {
      Map<String, Object> result =
          invokeRuntimeTool("job_profile_summary", args, sessionId, workspaceForRuntime());
      if (!Boolean.TRUE.equals(result.get("success"))) {
        throw new RuntimeException("Runtime 画像摘要生成失败: " + stringOf(result.get("error")));
      }
      Object outputValue = result.get("output");
      if (!(outputValue instanceof Map)) {
        throw new RuntimeException("Runtime 画像摘要未返回有效结果");
      }
      Map<String, Object> output = (Map<String, Object>) outputValue;
      String summary = stringOf(output.get("summary")).trim();
      if (summary.isEmpty()) throw new RuntimeException("Runtime 画像摘要为空");
      Map<String, Object> view = new LinkedHashMap<String, Object>();
      view.put("oldSummary", oldSummary);
      view.put("newSummary", summary);
      view.put("highlights", output.get("highlights"));
      view.put("missingFields", output.get("missing_fields"));
      view.put("provider", "AI");
      return jsonCodec.convert(view, ResumeProfileSummaryResponse.class);
    } catch (RuntimeException e) {
      LOG.warn("AI 画像摘要生成失败，使用本地规则兜底: {}", e.getMessage());
      Map<String, Object> view = new LinkedHashMap<String, Object>();
      view.put("oldSummary", oldSummary);
      view.put("newSummary", profileNormalizer.fallbackProfileSummary(safeParsed));
      view.put("highlights", Collections.emptyList());
      view.put("missingFields", Collections.emptyList());
      view.put("provider", "fallback");
      return jsonCodec.convert(view, ResumeProfileSummaryResponse.class);
    }
  }

  public ResumeRecord get(String resumeId) {
    if (resumeId == null || resumeId.isEmpty()) return null;
    return resumeRecordRepository.findById(resumeId);
  }

  public ResumeRecord get(String resumeId, String userId) {
    return get(resumeId, null, userId);
  }

  public ResumeRecord get(String resumeId, String tenantId, String userId) {
    ResumeRecord record = get(resumeId);
    if (record == null) throw new IllegalArgumentException("简历不存在: " + resumeId);
    ensureOwner(record, tenantId, userId);
    return record;
  }

  public InputStream openOriginalFile(String resumeId, String tenantId, String userId) {
    ResumeRecord record = get(resumeId, tenantId, userId);
    return resumeObjectStorage.openStream(record);
  }

  public byte[] thumbnail(String resumeId, String tenantId, String userId) {
    ResumeRecord record = get(resumeId, tenantId, userId);
    String suffix =
        record.getSuffix() == null ? "" : record.getSuffix().toLowerCase(java.util.Locale.ROOT);
    if (!"pdf".equals(suffix)) return thumbnailRenderer.placeholderThumbnail(record);
    String cacheKey = "job-buddy:resume-thumbnail:" + record.getResumeId();
    Path tempFile = null;
    try {
      byte[] cached = readThumbnailCache(cacheKey);
      if (cached != null && cached.length > 0) return cached;
      tempFile = resumeObjectStorage.downloadToTempFile(record, workspaceForRuntime());
      byte[] bytes = thumbnailRenderer.renderPdfFirstPage(tempFile);
      writeThumbnailCache(cacheKey, bytes);
      return bytes;
    } catch (Exception e) {
      LOG.warn("简历缩略图生成失败 - resumeId: {}, suffix: {}, error: {}", resumeId, suffix, e.getMessage());
      return thumbnailRenderer.placeholderThumbnail(record);
    } finally {
      deleteQuietly(tempFile);
    }
  }

  private byte[] readThumbnailCache(String cacheKey) {
    if (redisTemplate == null) return null;
    try {
      String encoded = redisTemplate.opsForValue().get(cacheKey);
      return encoded == null || encoded.isEmpty() ? null : Base64.getDecoder().decode(encoded);
    } catch (Exception e) {
      LOG.warn("读取 Redis 简历缩略图缓存失败: {}", e.getMessage());
      return null;
    }
  }

  private void writeThumbnailCache(String cacheKey, byte[] bytes) {
    if (redisTemplate == null || bytes == null || bytes.length == 0) return;
    try {
      redisTemplate
          .opsForValue()
          .set(cacheKey, Base64.getEncoder().encodeToString(bytes), THUMBNAIL_CACHE_TTL);
    } catch (Exception e) {
      LOG.warn("写入 Redis 简历缩略图缓存失败: {}", e.getMessage());
    }
  }

  public ResumeRecord updateParsed(
      String resumeId, JsonNode parsed, String tenantId, String userId) {
    ResumeRecord record = get(resumeId);
    if (record == null) throw new IllegalArgumentException("简历不存在: " + resumeId);
    ensureOwner(record, tenantId, userId);
    record.setParsed(
        parsed == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(jsonCodec.toMap(parsed)));
    // 仅当载荷包含真实简历内容时才标记解析成功；文件夹整理等元数据更新不得改变解析状态，
    // 否则聊天/匹配链路会误判已解析而跳过真正的简历解析。
    if (ResumeParsedContent.hasContent(record.getParsed())) {
      record.setParseStatus("success");
      record.setParseError(null);
    }
    resumeRecordRepository.save(record);
    return record;
  }

  public void delete(String resumeId, String tenantId, String userId) {
    ResumeRecord record = get(resumeId);
    if (record == null) return;
    ensureOwner(record, tenantId, userId);
    resumeObjectStorage.delete(record);
    resumeRecordRepository.deleteById(resumeId);
  }

  public List<ResumeSummaryResponse> list(String userId) {
    return list(null, userId);
  }

  public List<ResumeSummaryResponse> list(String tenantId, String userId) {
    String effectiveUser = defaultUser(userId);
    return jsonCodec.convertList(
        resumeRecordRepository.findLatestSummariesByUserId(
            tenantId, effectiveUser, DEFAULT_LIST_LIMIT),
        ResumeSummaryResponse.class);
  }

  private boolean isInternalProfileRecord(ResumeRecord record) {
    Map<String, Object> source =
        profileNormalizer.asMap(
            record.getParsed() == null ? null : record.getParsed().get("source"));
    String type = stringOf(source.get("type"));
    return BossProfileNormalizer.PROFILE_SOURCE_TYPE.equals(type)
        || BossProfileNormalizer.BOSS_PROFILE_SOURCE_TYPE.equals(type);
  }

  public ResumeRecord analyzeSync(String resumeId, String sessionId) {
    ResumeRecord record = get(resumeId);
    if (record == null) throw new IllegalArgumentException("简历不存在: " + resumeId);
    return analyzeRecordSync(record, sessionId);
  }

  public ResumeRecord analyzeSync(
      String resumeId, String sessionId, String tenantId, String userId) {
    ResumeRecord record = get(resumeId, tenantId, userId);
    return analyzeRecordSync(record, sessionId);
  }

  public ResumeRecord analyzeIncrementally(
      String resumeId,
      String sessionId,
      String tenantId,
      String userId,
      Consumer<AnalysisPartialResult> consumer) {
    ResumeRecord record = get(resumeId, tenantId, userId);
    requirePdfResume(record, "简历分析");
    Path tempFile = null;
    try {
      String workspaceDir = workspaceForRuntime();
      tempFile = resumeObjectStorage.downloadToTempFile(record, workspaceDir);
      if (!ResumeParsedContent.hasContent(record.getParsed())) {
        Map<String, Object> parseArgs = new LinkedHashMap<String, Object>();
        parseArgs.put("file_path", tempFile.toString());
        toolResultApplier.applyParseResult(
            record, invokeRuntimeTool("resume_parse", parseArgs, sessionId, workspaceDir));
      }
      Map<String, Object> parsed =
          record.getParsed() == null
              ? new LinkedHashMap<String, Object>()
              : new LinkedHashMap<String, Object>(record.getParsed());
      parsed.remove("analysis");
      record.setParsed(parsed);
      resumeRecordRepository.save(record);

      List<List<String>> groups =
          java.util.Arrays.asList(
              java.util.Arrays.asList(
                  "overall_score", "score_breakdown", "summary", "advantages", "disadvantages"),
              java.util.Arrays.asList("problems", "content_quality", "experience_value"),
              java.util.Arrays.asList("interview_deep_dive_points", "action_items"));
      String[] sections = {"overview", "content", "actions"};
      String[] messages = {"总体判断、优势与风险已生成", "内容质量与经历价值已生成", "面试深挖与行动建议已生成"};
      for (int index = 0; index < groups.size(); index++) {
        Map<String, Object> result =
            invokeRuntimeTool(
                "resume_analyze",
                toolResultApplier.analysisArgs(tempFile, record, groups.get(index)),
                sessionId,
                workspaceDir);
        toolResultApplier.mergeAnalysisResult(record, result);
        record.setParseStatus("success");
        record.setParseError(null);
        resumeRecordRepository.save(record);
        if (consumer != null)
          consumer.accept(
              new AnalysisPartialResult(
                  sections[index], messages[index], jsonCodec.toTree(summarize(record))));
      }
      return record;
    } catch (RuntimeException e) {
      record.setParseStatus("fail");
      record.setParseError(e.getMessage());
      resumeRecordRepository.save(record);
      throw e;
    } finally {
      deleteQuietly(tempFile);
    }
  }

  private ResumeRecord analyzeRecordSync(ResumeRecord record, String sessionId) {
    requirePdfResume(record, "简历分析");
    Path tempFile = null;
    try {
      String workspaceDir = workspaceForRuntime();
      tempFile = resumeObjectStorage.downloadToTempFile(record, workspaceDir);
      if (!ResumeParsedContent.hasContent(record.getParsed())) {
        Map<String, Object> parseArgs = new LinkedHashMap<String, Object>();
        parseArgs.put("file_path", tempFile.toString());
        toolResultApplier.applyParseResult(
            record, invokeRuntimeTool("resume_parse", parseArgs, sessionId, workspaceDir));
      }
      toolResultApplier.applyAnalysisResult(
          record,
          invokeRuntimeTool(
              "resume_analyze",
              toolResultApplier.analysisArgs(tempFile, record),
              sessionId,
              workspaceDir));
      record.setParseStatus("success");
      record.setParseError(null);
      resumeRecordRepository.save(record);
      return record;
    } catch (RuntimeException e) {
      record.setParseStatus("fail");
      record.setParseError(e.getMessage());
      resumeRecordRepository.save(record);
      throw e;
    } finally {
      deleteQuietly(tempFile);
    }
  }

  public ResumeRecord parseSync(String resumeId, String sessionId) {
    ResumeRecord record = get(resumeId);
    if (record == null) throw new IllegalArgumentException("简历不存在: " + resumeId);
    return parseRecordSync(record, sessionId);
  }

  public ResumeRecord parseSync(String resumeId, String sessionId, String tenantId, String userId) {
    ResumeRecord record = get(resumeId, tenantId, userId);
    return parseRecordSync(record, sessionId);
  }

  private ResumeRecord parseRecordSync(ResumeRecord record, String sessionId) {
    // parse_status 可能被 updateParsed（如文件夹整理）置为 success 而 parsed 只有元数据，必须校验内容字段。
    if ("success".equals(record.getParseStatus())
        && ResumeParsedContent.hasContent(record.getParsed())) return record;

    Path tempFile = null;
    try {
      String workspaceDir = workspaceForRuntime();
      tempFile = resumeObjectStorage.downloadToTempFile(record, workspaceDir);
      Map<String, Object> args = new LinkedHashMap<String, Object>();
      args.put("file_path", tempFile.toString());
      Map<String, Object> result = invokeRuntimeTool("resume_parse", args, sessionId, workspaceDir);
      toolResultApplier.applyParseResult(record, result);
      toolResultApplier.applyAnalysisResult(
          record,
          invokeRuntimeTool(
              "resume_analyze",
              toolResultApplier.analysisArgs(tempFile, record),
              sessionId,
              workspaceDir));
      resumeRecordRepository.save(record);
      return record;
    } catch (RuntimeException e) {
      record.setParseStatus("fail");
      record.setParseError(e.getMessage());
      resumeRecordRepository.save(record);
      throw e;
    } finally {
      deleteQuietly(tempFile);
    }
  }

  private ResumeRecord upsertProfileRecord(
      String effectiveTenant,
      String effectiveUser,
      Map<String, Object> parsed,
      Map<String, Object> raw)
      throws IOException {
    ResumeRecord record = findJobProfile(null, effectiveUser);
    if (record == null) {
      String resumeId = "profile_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
      record = new ResumeRecord();
      record.setResumeId(resumeId);
      record.setTenantId(effectiveTenant);
      record.setUserId(effectiveUser);
      record.setStoragePath(effectiveUser + "/" + resumeId + ".txt");
      record.setSuffix("txt");
    }
    profileNormalizer.ensureProfileSource(
        parsed, stringOf(profileNormalizer.asMap(parsed.get("source")).get("provider")), raw);
    record.setOriginalName("求职画像-" + java.time.LocalDate.now() + ".txt");
    byte[] content =
        profileNormalizer
            .renderBossProfileText(
                parsed, raw == null ? Collections.<String, Object>emptyMap() : raw)
            .getBytes(StandardCharsets.UTF_8);
    resumeObjectStorage.uploadBytes(content, record.getStoragePath(), "text/plain; charset=utf-8");
    record.setSizeBytes(content.length);
    record.setUploadedAt(Instant.now());
    record.setParseStatus("success");
    record.setParseError(null);
    record.setParsed(parsed);
    resumeRecordRepository.save(record);
    return record;
  }

  private ResumeRecord findJobProfile(String tenantId, String effectiveUser) {
    List<ResumeRecord> records =
        resumeRecordRepository.findLatestByUserId(tenantId, effectiveUser, DEFAULT_LIST_LIMIT);
    for (ResumeRecord record : records) {
      if (isInternalProfileRecord(record)) return record;
    }
    return null;
  }

  public ResumeSummaryResponse summarize(ResumeRecord record) {
    if (record == null) return null;
    Map<String, Object> view = new LinkedHashMap<String, Object>();
    view.put("resumeId", record.getResumeId());
    view.put("userId", record.getUserId());
    view.put("originalName", record.getOriginalName());
    view.put("sizeBytes", record.getSizeBytes());
    view.put("suffix", record.getSuffix());
    view.put("uploadedAt", record.getUploadedAt());
    view.put("parseStatus", record.getParseStatus());
    view.put("parsed", record.getParsed());
    view.put("parseError", record.getParseError());
    return jsonCodec.convert(view, ResumeSummaryResponse.class);
  }

  private void requirePdfResume(ResumeRecord record, String operation) {
    String suffix =
        record == null || record.getSuffix() == null
            ? ""
            : record.getSuffix().trim().toLowerCase(java.util.Locale.ROOT);
    if (!"pdf".equals(suffix)) {
      throw new IllegalArgumentException(operation + "仅支持 PDF 格式");
    }
  }

  private void validateFile(MultipartFile file) {
    if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传文件不能为空");
    if (file.getSize() > properties.getMaxResumeBytes()) {
      throw new IllegalArgumentException(
          "简历文件超出大小限制: " + properties.getMaxResumeBytes() + " bytes");
    }
  }

  private Map<String, Object> invokeRuntimeTool(
      String toolName, Map<String, Object> arguments, String sessionId, String workspaceDir) {
    RuntimeToolResult result =
        toolClient.invoke(
            toolName, RuntimeToolArguments.fromMap(arguments, jsonCodec), sessionId, workspaceDir);
    return result == null ? Collections.<String, Object>emptyMap() : result.toMap(jsonCodec);
  }

  private String workspaceForRuntime() {
    String override = properties.getResumeRuntimeWorkspace();
    Path workspace;
    if (override != null && !override.isEmpty()) {
      workspace = Paths.get(override);
      if (!workspace.isAbsolute()) {
        Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
        Path root =
            cwd.getFileName() != null && "agent-backend".equals(cwd.getFileName().toString())
                ? cwd.getParent()
                : cwd;
        workspace = root.resolve(workspace);
      }
    } else {
      workspace = Paths.get(System.getProperty("java.io.tmpdir"), "job-buddy-runtime-workspace");
    }
    return workspace.toAbsolutePath().normalize().toString();
  }

  private Map<String, Object> assetResponse(String assetId, String contentType, long sizeBytes) {
    Map<String, Object> data = new LinkedHashMap<String, Object>();
    data.put("assetId", assetId);
    data.put("url", "/api/resume/assets/" + assetId);
    data.put("contentType", contentType);
    data.put("sizeBytes", sizeBytes);
    return data;
  }

  private String resolveAssetObjectName(String assetToken, String userId) {
    String effectiveUser = defaultUser(userId);
    if (assetToken != null && assetToken.matches("asset_[a-f0-9]{16}")) {
      if (resumeAssetMapper == null) throw new IllegalArgumentException("照片资源不可用");
      Map<String, Object> query = new LinkedHashMap<String, Object>();
      query.put("assetId", assetToken);
      query.put("userId", effectiveUser);
      Map<String, Object> asset = resumeAssetMapper.findByAssetIdAndUser(query);
      if (asset == null || asset.get("storagePath") == null)
        throw new IllegalArgumentException("照片资源不存在");
      String objectName = String.valueOf(asset.get("storagePath"));
      if (!objectName.startsWith(effectiveUser + "/assets/"))
        throw new IllegalArgumentException("无权访问该资源");
      String suffix = ResumeAssetTokenSigner.extractSuffix(objectName);
      if (!ResumeAssetTokenSigner.ALLOWED_ASSET_SUFFIXES.contains(suffix))
        throw new IllegalArgumentException("非法资源类型");
      return objectName;
    }
    return assetTokenSigner.requireAssetObjectName(assetToken, effectiveUser);
  }

  private String sha256(byte[] content) {
    try {
      return HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(content));
    } catch (Exception e) {
      throw new IllegalStateException("计算资产摘要失败", e);
    }
  }

  private String defaultUser(String userId) {
    return (userId == null || userId.isEmpty()) ? properties.getDefaultUserId() : userId;
  }

  private String effectiveTenant(String tenantId, String effectiveUser) {
    if (tenantId != null && !tenantId.trim().isEmpty()) return tenantId;
    try {
      String resolved = resumeRecordRepository.findTenantIdByUserId(effectiveUser);
      if (resolved != null && !resolved.trim().isEmpty()) return resolved;
    } catch (RuntimeException e) {
      LOG.warn("解析用户租户失败,回退默认租户 - user: {}, error: {}", effectiveUser, e.getMessage());
    }
    return FALLBACK_TENANT_ID;
  }

  private void ensureOwner(ResumeRecord record, String tenantId, String userId) {
    String effectiveUser = defaultUser(userId);
    if (record.getUserId() == null
        || record.getUserId().trim().isEmpty()
        || !record.getUserId().equals(effectiveUser)) {
      throw new IllegalArgumentException("无权操作该简历");
    }
    if (record.getTenantId() == null || record.getTenantId().trim().isEmpty()) {
      throw new IllegalArgumentException("简历缺少租户归属");
    }
    if (tenantId != null
        && !tenantId.trim().isEmpty()
        && !record.getTenantId().equals(tenantId.trim())) {
      throw new IllegalArgumentException("无权操作该简历");
    }
  }

  private String stringOf(Object value) {
    return value == null ? "" : String.valueOf(value);
  }

  private void deleteQuietly(Path tempFile) {
    if (tempFile == null) return;
    try {
      Files.deleteIfExists(tempFile);
    } catch (IOException e) {
      LOG.warn("删除临时简历文件失败: {}", tempFile, e);
    }
  }
}
