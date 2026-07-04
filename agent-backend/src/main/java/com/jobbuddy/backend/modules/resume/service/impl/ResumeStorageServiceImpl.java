package com.jobbuddy.backend.modules.resume.service.impl;

import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.repository.ResumeRecordRepository;
import com.jobbuddy.backend.modules.resume.storage.ResumeObjectStorage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 简历存储服务：负责简历与附件的上传下载、求职画像的同步与保存、
 * 解析/分析编排以及归属校验。签名令牌、缩略图渲染、Boss 画像归一化
 * 和工具结果落盘分别由同包协作类承担。
 */
@Service
public class ResumeStorageServiceImpl implements ResumeStorageService {

    private static final Logger LOG = LoggerFactory.getLogger(ResumeStorageService.class);
    private static final java.util.Set<String> ALLOWED_SUFFIXES = new java.util.HashSet<String>(
            java.util.Collections.singletonList("pdf"));
    private static final int DEFAULT_LIST_LIMIT = 50;

    private final JobBuddyProperties properties;
    private final RuntimeToolClient toolClient;
    private final ResumeRecordRepository resumeRecordRepository;
    private final ResumeObjectStorage resumeObjectStorage;
    private final BossCliService bossCliService;
    private final ResumeAssetTokenSigner assetTokenSigner;
    private final ResumeThumbnailRenderer thumbnailRenderer;
    private final BossProfileNormalizer profileNormalizer;
    private final ResumeToolResultApplier toolResultApplier;

    public ResumeStorageServiceImpl(JobBuddyProperties properties,
                                RuntimeToolClient toolClient,
                                ResumeRecordRepository resumeRecordRepository,
                                ResumeObjectStorage resumeObjectStorage,
                                BossCliService bossCliService,
                                JsonCodec jsonCodec) {
        this.properties = properties;
        this.toolClient = toolClient;
        this.resumeRecordRepository = resumeRecordRepository;
        this.resumeObjectStorage = resumeObjectStorage;
        this.bossCliService = bossCliService;
        this.assetTokenSigner = new ResumeAssetTokenSigner(properties, jsonCodec);
        this.thumbnailRenderer = new ResumeThumbnailRenderer();
        this.profileNormalizer = new BossProfileNormalizer(jsonCodec);
        this.toolResultApplier = new ResumeToolResultApplier();
    }

    public ResumeRecord upload(MultipartFile file, String userId) throws IOException {
        validateFile(file);
        String original = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename();
        String suffix = ResumeAssetTokenSigner.extractSuffix(original);
        if (!ALLOWED_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("不支持的简历格式: " + suffix + ",仅支持 PDF");
        }

        String effectiveUser = defaultUser(userId);
        String resumeId = "resume_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String objectName = effectiveUser + "/" + resumeId + "." + suffix;
        resumeObjectStorage.upload(file, objectName);

        ResumeRecord record = new ResumeRecord();
        record.setResumeId(resumeId);
        record.setUserId(effectiveUser);
        record.setOriginalName(original);
        record.setStoragePath(objectName);
        record.setSizeBytes(file.getSize());
        record.setSuffix(suffix);
        record.setUploadedAt(Instant.now());
        record.setParseStatus("pending");
        record.setParsed(new LinkedHashMap<String, Object>());
        resumeRecordRepository.save(record);
        LOG.info("简历上传成功 - resumeId: {}, user: {}, bucket: {}, object: {}, size: {}",
                resumeId, effectiveUser, resumeObjectStorage.bucket(), objectName, file.getSize());
        return record;
    }

    public Map<String, Object> uploadAsset(MultipartFile file, String userId) throws IOException {
        validateFile(file);
        String original = file.getOriginalFilename() == null ? "asset" : file.getOriginalFilename();
        String suffix = ResumeAssetTokenSigner.extractSuffix(original);
        if (!ResumeAssetTokenSigner.ALLOWED_ASSET_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("不支持的图片格式: " + suffix + ",仅支持 JPG / PNG / WebP");
        }
        String effectiveUser = defaultUser(userId);
        String assetId = "asset_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String objectName = effectiveUser + "/assets/" + assetId + "." + suffix;
        resumeObjectStorage.upload(file, objectName);
        String token = assetTokenSigner.signAssetToken(objectName, effectiveUser);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("assetId", assetId);
        data.put("url", "/api/resume/assets/" + token);
        data.put("contentType", file.getContentType());
        data.put("sizeBytes", file.getSize());
        return data;
    }

    public InputStream openAsset(String assetToken, String userId) {
        return resumeObjectStorage.openObjectStream(assetTokenSigner.requireAssetObjectName(assetToken, userId));
    }

    public String assetContentType(String assetToken, String userId) {
        try {
            String objectName = assetTokenSigner.requireAssetObjectName(assetToken, userId);
            String suffix = ResumeAssetTokenSigner.extractSuffix(objectName);
            if ("png".equals(suffix)) return "image/png";
            if ("webp".equals(suffix)) return "image/webp";
            return "image/jpeg";
        } catch (Exception e) {
            return "application/octet-stream";
        }
    }

    public ResumeRecord syncBossOnlineResume(String userId) throws IOException {
        String effectiveUser = defaultUser(userId);
        Map<String, Object> profile = bossCliService.fetchOnlineProfile();
        Map<String, Object> parsed = profileNormalizer.normalizeBossProfile(profile);
        ResumeRecord record = upsertProfileRecord(effectiveUser, parsed, profile);
        LOG.info("求职画像同步成功 - resumeId: {}, user: {}, size: {}", record.getResumeId(), effectiveUser, record.getSizeBytes());
        return record;
    }

    public Map<String, Object> getJobProfileOrEmpty(String userId) {
        String effectiveUser = defaultUser(userId);
        ResumeRecord existing = findJobProfile(effectiveUser);
        if (existing == null && !isDefaultUser(effectiveUser)) {
            existing = findJobProfile(defaultUser(null));
        }
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
        return view;
    }

    public ResumeRecord getOrCreateJobProfile(String userId) throws IOException {
        String effectiveUser = defaultUser(userId);
        ResumeRecord existing = findJobProfile(effectiveUser);
        if (existing != null) return existing;
        Map<String, Object> parsed = profileNormalizer.emptyJobProfile();
        return upsertProfileRecord(effectiveUser, parsed, Collections.<String, Object>emptyMap());
    }

    public ResumeRecord saveJobProfile(String userId, Map<String, Object> parsed) throws IOException {
        String effectiveUser = defaultUser(userId);
        Map<String, Object> safeParsed = parsed == null ? profileNormalizer.emptyJobProfile() : new LinkedHashMap<String, Object>(parsed);
        profileNormalizer.ensureProfileSource(safeParsed, "手动填写", null);
        return upsertProfileRecord(effectiveUser, safeParsed, profileNormalizer.asMap(profileNormalizer.asMap(safeParsed.get("source")).get("raw")));
    }

    public Map<String, Object> generateJobProfileSummary(Map<String, Object> parsed, String sessionId) {
        Map<String, Object> safeParsed = parsed == null ? profileNormalizer.emptyJobProfile() : new LinkedHashMap<String, Object>(parsed);
        String oldSummary = stringOf(safeParsed.get("summary"));
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("profile", safeParsed);
        try {
            Map<String, Object> result = toolClient.invoke("job_profile_summary", args, sessionId, workspaceForRuntime());
            String summary = stringOf(result.get("summary")).trim();
            if (summary.isEmpty()) summary = profileNormalizer.fallbackProfileSummary(safeParsed);
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("oldSummary", oldSummary);
            view.put("newSummary", summary);
            view.put("highlights", result.get("highlights"));
            view.put("missingFields", result.get("missing_fields"));
            view.put("provider", "AI");
            return view;
        } catch (RuntimeException e) {
            LOG.warn("AI 画像摘要生成失败，使用本地规则兜底: {}", e.getMessage());
            Map<String, Object> view = new LinkedHashMap<String, Object>();
            view.put("oldSummary", oldSummary);
            view.put("newSummary", profileNormalizer.fallbackProfileSummary(safeParsed));
            view.put("highlights", Collections.emptyList());
            view.put("missingFields", Collections.emptyList());
            view.put("provider", "fallback");
            return view;
        }
    }

    public ResumeRecord get(String resumeId) {
        if (resumeId == null || resumeId.isEmpty()) return null;
        return resumeRecordRepository.findById(resumeId);
    }

    public ResumeRecord get(String resumeId, String userId) {
        ResumeRecord record = get(resumeId);
        if (record == null) throw new IllegalArgumentException("简历不存在: " + resumeId);
        ensureOwner(record, userId);
        return record;
    }

    public InputStream openOriginalFile(String resumeId, String userId) {
        ResumeRecord record = get(resumeId, userId);
        return resumeObjectStorage.openStream(record);
    }

    public byte[] thumbnail(String resumeId, String userId) {
        ResumeRecord record = get(resumeId, userId);
        String suffix = record.getSuffix() == null ? "" : record.getSuffix().toLowerCase(java.util.Locale.ROOT);
        if (!"pdf".equals(suffix)) return thumbnailRenderer.placeholderThumbnail(record);
        Path thumbnailPath = thumbnailRenderer.thumbnailCachePath(record);
        Path tempFile = null;
        try {
            if (Files.exists(thumbnailPath)) return Files.readAllBytes(thumbnailPath);
            Files.createDirectories(thumbnailPath.getParent());
            tempFile = resumeObjectStorage.downloadToTempFile(record, workspaceForRuntime());
            byte[] bytes = thumbnailRenderer.renderPdfFirstPage(tempFile);
            Files.write(thumbnailPath, bytes);
            return bytes;
        } catch (Exception e) {
            LOG.warn("简历缩略图生成失败 - resumeId: {}, suffix: {}, error: {}", resumeId, suffix, e.getMessage());
            return thumbnailRenderer.placeholderThumbnail(record);
        } finally {
            deleteQuietly(tempFile);
        }
    }

    public ResumeRecord updateParsed(String resumeId, Map<String, Object> parsed, String userId) {
        ResumeRecord record = get(resumeId);
        if (record == null) throw new IllegalArgumentException("简历不存在: " + resumeId);
        ensureOwner(record, userId);
        record.setParsed(parsed == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(parsed));
        record.setParseStatus("success");
        record.setParseError(null);
        resumeRecordRepository.save(record);
        return record;
    }

    public void delete(String resumeId, String userId) {
        ResumeRecord record = get(resumeId);
        if (record == null) return;
        ensureOwner(record, userId);
        resumeObjectStorage.delete(record);
        resumeRecordRepository.deleteById(resumeId);
    }

    public List<Map<String, Object>> list(String userId) {
        String effectiveUser = defaultUser(userId);
        List<ResumeRecord> records = new ArrayList<ResumeRecord>(resumeRecordRepository.findLatestByUserId(effectiveUser, DEFAULT_LIST_LIMIT));
        if (!isDefaultUser(effectiveUser)) {
            appendLegacyDefaultUserRecords(records);
        }
        List<Map<String, Object>> result = new java.util.ArrayList<Map<String, Object>>();
        for (ResumeRecord record : records) {
            if (isInternalProfileRecord(record)) continue;
            result.add(summarize(record));
        }
        return result;
    }

    private boolean isInternalProfileRecord(ResumeRecord record) {
        Map<String, Object> source = profileNormalizer.asMap(record.getParsed() == null ? null : record.getParsed().get("source"));
        String type = stringOf(source.get("type"));
        return BossProfileNormalizer.PROFILE_SOURCE_TYPE.equals(type) || BossProfileNormalizer.BOSS_PROFILE_SOURCE_TYPE.equals(type);
    }

    public ResumeRecord analyzeSync(String resumeId, String sessionId) {
        ResumeRecord record = get(resumeId);
        if (record == null) throw new IllegalArgumentException("简历不存在: " + resumeId);
        return analyzeRecordSync(record, sessionId);
    }

    public ResumeRecord analyzeSync(String resumeId, String sessionId, String userId) {
        ResumeRecord record = get(resumeId, userId);
        return analyzeRecordSync(record, sessionId);
    }

    private ResumeRecord analyzeRecordSync(ResumeRecord record, String sessionId) {
        Path tempFile = null;
        try {
            String workspaceDir = workspaceForRuntime();
            tempFile = resumeObjectStorage.downloadToTempFile(record, workspaceDir);
            if (record.getParsed() == null || record.getParsed().isEmpty()) {
                Map<String, Object> parseArgs = new LinkedHashMap<String, Object>();
                parseArgs.put("file_path", tempFile.toString());
                toolResultApplier.applyParseResult(record, toolClient.invoke("resume_parse", parseArgs, sessionId, workspaceDir));
            }
            toolResultApplier.applyAnalysisResult(record, toolClient.invoke("resume_analyze", toolResultApplier.analysisArgs(tempFile, record), sessionId, workspaceDir));
            record.setParseStatus("success");
            record.setParseError(null);
            resumeRecordRepository.save(record);
            return record;
        } catch (RuntimeException e) {
            if (toolResultApplier.isAnalyzeToolUnavailable(e)) {
                toolResultApplier.applyLocalAnalysisFallback(record, e.getMessage());
                record.setParseStatus("success");
                record.setParseError(null);
                resumeRecordRepository.save(record);
                return record;
            }
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

    public ResumeRecord parseSync(String resumeId, String sessionId, String userId) {
        ResumeRecord record = get(resumeId, userId);
        return parseRecordSync(record, sessionId);
    }

    private ResumeRecord parseRecordSync(ResumeRecord record, String sessionId) {
        if ("success".equals(record.getParseStatus()) && record.getParsed() != null && !record.getParsed().isEmpty()) return record;

        Path tempFile = null;
        try {
            String workspaceDir = workspaceForRuntime();
            tempFile = resumeObjectStorage.downloadToTempFile(record, workspaceDir);
            Map<String, Object> args = new LinkedHashMap<String, Object>();
            args.put("file_path", tempFile.toString());
            Map<String, Object> result = toolClient.invoke("resume_parse", args, sessionId, workspaceDir);
            toolResultApplier.applyParseResult(record, result);
            toolResultApplier.applyAnalysisResult(record, toolClient.invoke("resume_analyze", toolResultApplier.analysisArgs(tempFile, record), sessionId, workspaceDir));
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

    private ResumeRecord upsertProfileRecord(String effectiveUser, Map<String, Object> parsed, Map<String, Object> raw) throws IOException {
        ResumeRecord record = findJobProfile(effectiveUser);
        if (record == null) {
            String resumeId = "profile_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
            record = new ResumeRecord();
            record.setResumeId(resumeId);
            record.setUserId(effectiveUser);
            record.setStoragePath(effectiveUser + "/" + resumeId + ".txt");
            record.setSuffix("txt");
        }
        profileNormalizer.ensureProfileSource(parsed, stringOf(profileNormalizer.asMap(parsed.get("source")).get("provider")), raw);
        record.setOriginalName("求职画像-" + java.time.LocalDate.now() + ".txt");
        byte[] content = profileNormalizer.renderBossProfileText(parsed, raw == null ? Collections.<String, Object>emptyMap() : raw).getBytes(StandardCharsets.UTF_8);
        resumeObjectStorage.uploadBytes(content, record.getStoragePath(), "text/plain; charset=utf-8");
        record.setSizeBytes(content.length);
        record.setUploadedAt(Instant.now());
        record.setParseStatus("success");
        record.setParseError(null);
        record.setParsed(parsed);
        resumeRecordRepository.save(record);
        return record;
    }

    private ResumeRecord findJobProfile(String effectiveUser) {
        List<ResumeRecord> records = resumeRecordRepository.findLatestByUserId(effectiveUser, DEFAULT_LIST_LIMIT);
        for (ResumeRecord record : records) {
            if (isInternalProfileRecord(record)) return record;
        }
        return null;
    }

    public Map<String, Object> summarize(ResumeRecord record) {
        if (record == null) return Collections.emptyMap();
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
        return view;
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传文件不能为空");
        if (file.getSize() > properties.getMaxResumeBytes()) {
            throw new IllegalArgumentException("简历文件超出大小限制: " + properties.getMaxResumeBytes() + " bytes");
        }
    }

    private String workspaceForRuntime() {
        String override = properties.getResumeRuntimeWorkspace();
        Path workspace;
        if (override != null && !override.isEmpty()) {
            workspace = Paths.get(override);
            if (!workspace.isAbsolute()) {
                Path cwd = Paths.get(System.getProperty("user.dir")).toAbsolutePath().normalize();
                Path root = cwd.getFileName() != null && "agent-backend".equals(cwd.getFileName().toString())
                        ? cwd.getParent()
                        : cwd;
                workspace = root.resolve(workspace);
            }
        } else {
            workspace = Paths.get(System.getProperty("java.io.tmpdir"), "job-buddy-runtime-workspace");
        }
        return workspace.toAbsolutePath().normalize().toString();
    }

    private String defaultUser(String userId) {
        return (userId == null || userId.isEmpty()) ? properties.getDefaultUserId() : userId;
    }

    private void ensureOwner(ResumeRecord record, String userId) {
        String effectiveUser = defaultUser(userId);
        if (record.getUserId() != null && !record.getUserId().equals(effectiveUser) && !isLegacyDefaultUserRecord(record, effectiveUser)) {
            throw new IllegalArgumentException("无权操作该简历");
        }
    }

    private void appendLegacyDefaultUserRecords(List<ResumeRecord> records) {
        String legacyUser = defaultUser(null);
        java.util.Set<String> seen = new java.util.HashSet<String>();
        for (ResumeRecord record : records) {
            if (record != null && record.getResumeId() != null) seen.add(record.getResumeId());
        }
        for (ResumeRecord record : resumeRecordRepository.findLatestByUserId(legacyUser, DEFAULT_LIST_LIMIT)) {
            if (record == null || record.getResumeId() == null || seen.contains(record.getResumeId())) continue;
            records.add(record);
            seen.add(record.getResumeId());
        }
    }

    private boolean isLegacyDefaultUserRecord(ResumeRecord record, String effectiveUser) {
        return record != null
                && record.getUserId() != null
                && record.getUserId().equals(defaultUser(null))
                && !isDefaultUser(effectiveUser);
    }

    private boolean isDefaultUser(String userId) {
        return defaultUser(null).equals(userId);
    }

    private String stringOf(Object value) { return value == null ? "" : String.valueOf(value); }

    private void deleteQuietly(Path tempFile) {
        if (tempFile == null) return;
        try {
            Files.deleteIfExists(tempFile);
        } catch (IOException e) {
            LOG.warn("删除临时简历文件失败: {}", tempFile, e);
        }
    }
}
