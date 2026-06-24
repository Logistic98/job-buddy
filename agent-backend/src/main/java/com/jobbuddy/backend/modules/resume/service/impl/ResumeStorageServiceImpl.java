package com.jobbuddy.backend.modules.resume.service.impl;

import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.service.BossCliService;
import com.jobbuddy.backend.modules.chat.service.RuntimeToolClient;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.repository.ResumeRecordRepository;
import com.jobbuddy.backend.modules.resume.storage.ResumeObjectStorage;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.ImageType;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

@Service
public class ResumeStorageServiceImpl implements ResumeStorageService {

    private static final Logger LOG = LoggerFactory.getLogger(ResumeStorageService.class);
    private static final java.util.Set<String> ALLOWED_SUFFIXES = new java.util.HashSet<String>(
            java.util.Collections.singletonList("pdf"));
    private static final java.util.Set<String> ALLOWED_ASSET_SUFFIXES = new java.util.HashSet<String>(
            java.util.Arrays.asList("jpg", "jpeg", "png", "webp"));
    private static final int DEFAULT_LIST_LIMIT = 50;
    private static final String PROFILE_SOURCE_TYPE = "job_profile";
    private static final String BOSS_PROFILE_SOURCE_TYPE = "boss_online_resume";

    private final JobBuddyProperties properties;
    private final RuntimeToolClient toolClient;
    private final ResumeRecordRepository resumeRecordRepository;
    private final ResumeObjectStorage resumeObjectStorage;
    private final BossCliService bossCliService;
    private final JsonCodec jsonCodec;
    private final byte[] assetSigningKey;

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
        this.jsonCodec = jsonCodec;
        this.assetSigningKey = initAssetSigningKey(properties);
    }

    public ResumeRecord upload(MultipartFile file, String userId) throws IOException {
        validateFile(file);
        String original = file.getOriginalFilename() == null ? "resume" : file.getOriginalFilename();
        String suffix = extractSuffix(original);
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
        String suffix = extractSuffix(original);
        if (!ALLOWED_ASSET_SUFFIXES.contains(suffix)) {
            throw new IllegalArgumentException("不支持的图片格式: " + suffix + ",仅支持 JPG / PNG / WebP");
        }
        String effectiveUser = defaultUser(userId);
        String assetId = "asset_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String objectName = effectiveUser + "/assets/" + assetId + "." + suffix;
        resumeObjectStorage.upload(file, objectName);
        String token = signAssetToken(objectName, effectiveUser);
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("assetId", assetId);
        data.put("url", "/api/resume/assets/" + token);
        data.put("contentType", file.getContentType());
        data.put("sizeBytes", file.getSize());
        return data;
    }

    public InputStream openAsset(String assetToken, String userId) {
        return resumeObjectStorage.openObjectStream(requireAssetObjectName(assetToken, userId));
    }

    public String assetContentType(String assetToken, String userId) {
        try {
            String objectName = requireAssetObjectName(assetToken, userId);
            String suffix = extractSuffix(objectName);
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
        Map<String, Object> parsed = normalizeBossProfile(profile);
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
        view.put("parsed", emptyJobProfile());
        view.put("parseError", null);
        return view;
    }

    public ResumeRecord getOrCreateJobProfile(String userId) throws IOException {
        String effectiveUser = defaultUser(userId);
        ResumeRecord existing = findJobProfile(effectiveUser);
        if (existing != null) return existing;
        Map<String, Object> parsed = emptyJobProfile();
        return upsertProfileRecord(effectiveUser, parsed, Collections.<String, Object>emptyMap());
    }

    public ResumeRecord saveJobProfile(String userId, Map<String, Object> parsed) throws IOException {
        String effectiveUser = defaultUser(userId);
        Map<String, Object> safeParsed = parsed == null ? emptyJobProfile() : new LinkedHashMap<String, Object>(parsed);
        ensureProfileSource(safeParsed, "手动填写", null);
        return upsertProfileRecord(effectiveUser, safeParsed, asMap(asMap(safeParsed.get("source")).get("raw")));
    }

    public Map<String, Object> generateJobProfileSummary(Map<String, Object> parsed, String sessionId) {
        Map<String, Object> safeParsed = parsed == null ? emptyJobProfile() : new LinkedHashMap<String, Object>(parsed);
        String oldSummary = stringOf(safeParsed.get("summary"));
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("profile", safeParsed);
        try {
            Map<String, Object> result = toolClient.invoke("job_profile_summary", args, sessionId, workspaceForRuntime());
            String summary = stringOf(result.get("summary")).trim();
            if (summary.isEmpty()) summary = fallbackProfileSummary(safeParsed);
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
            view.put("newSummary", fallbackProfileSummary(safeParsed));
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
        if (!"pdf".equals(suffix)) return placeholderThumbnail(record);
        Path thumbnailPath = thumbnailCachePath(record);
        Path tempFile = null;
        try {
            if (Files.exists(thumbnailPath)) return Files.readAllBytes(thumbnailPath);
            Files.createDirectories(thumbnailPath.getParent());
            tempFile = resumeObjectStorage.downloadToTempFile(record, workspaceForRuntime());
            byte[] bytes = renderPdfFirstPage(tempFile);
            Files.write(thumbnailPath, bytes);
            return bytes;
        } catch (Exception e) {
            LOG.warn("简历缩略图生成失败 - resumeId: {}, suffix: {}, error: {}", resumeId, suffix, e.getMessage());
            return placeholderThumbnail(record);
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
        Map<String, Object> source = asMap(record.getParsed() == null ? null : record.getParsed().get("source"));
        String type = stringOf(source.get("type"));
        return PROFILE_SOURCE_TYPE.equals(type) || BOSS_PROFILE_SOURCE_TYPE.equals(type);
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
                applyParseResult(record, toolClient.invoke("resume_parse", parseArgs, sessionId, workspaceDir));
            }
            applyAnalysisResult(record, toolClient.invoke("resume_analyze", analysisArgs(tempFile, record), sessionId, workspaceDir));
            record.setParseStatus("success");
            record.setParseError(null);
            resumeRecordRepository.save(record);
            return record;
        } catch (RuntimeException e) {
            if (isAnalyzeToolUnavailable(e)) {
                applyLocalAnalysisFallback(record, e.getMessage());
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
            applyParseResult(record, result);
            applyAnalysisResult(record, toolClient.invoke("resume_analyze", analysisArgs(tempFile, record), sessionId, workspaceDir));
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
        ensureProfileSource(parsed, stringOf(asMap(parsed.get("source")).get("provider")), raw);
        record.setOriginalName("求职画像-" + java.time.LocalDate.now() + ".txt");
        byte[] content = renderBossProfileText(parsed, raw == null ? Collections.<String, Object>emptyMap() : raw).getBytes(StandardCharsets.UTF_8);
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
            Map<String, Object> source = asMap(record.getParsed() == null ? null : record.getParsed().get("source"));
            String type = stringOf(source.get("type"));
            if (PROFILE_SOURCE_TYPE.equals(type) || BOSS_PROFILE_SOURCE_TYPE.equals(type)) return record;
        }
        return null;
    }

    private Map<String, Object> emptyJobProfile() {
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

    private void ensureProfileSource(Map<String, Object> parsed, String provider, Map<String, Object> raw) {
        Map<String, Object> source = asMap(parsed.get("source"));
        source.put("type", PROFILE_SOURCE_TYPE);
        source.put("provider", provider == null || provider.isEmpty() ? "手动填写" : provider);
        source.put("synced_at", Instant.now().toString());
        if (raw != null && !raw.isEmpty()) source.put("raw", raw);
        parsed.put("source", source);
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

    private Map<String, Object> normalizeBossProfile(Map<String, Object> profile) {
        Map<String, Object> basic = asMap(profile.get("basicInfo"));
        Map<String, Object> expectations = asMap(profile.get("jobExpectations"));
        Map<String, Object> status = asMap(profile.get("jobStatus"));
        Map<String, Object> user = asMap(profile.get("userInfo"));
        Map<String, Object> advantage = asMap(profile.get("personalAdvantage"));
        Map<String, Object> work = asMap(profile.get("workExperiences"));
        Map<String, Object> project = asMap(profile.get("projectExperiences"));
        Map<String, Object> education = asMap(profile.get("educationExperiences"));
        Map<String, Object> intention = asMap(profile.get("jobIntentions"));

        Map<String, Object> parsed = new LinkedHashMap<String, Object>();
        parsed.put("name", firstString(basic, user, profile, "name", "userName", "user_name", "geekName", "nickName"));
        parsed.put("summary", buildBossSummary(basic, expectations, status, user, profile));
        parsed.put("current_title", firstString(basic, expectations, profile, "currentTitle", "current_title", "position", "jobTitle", "positionName", "expectPosition"));
        parsed.put("years_experience", firstPresent(basic, profile, "years_experience", "workYears", "work_years", "experience"));
        parsed.put("expected_titles", firstPresent(expectations, basic, profile, "expected_titles", "expectPositions", "expect_positions", "positions", "positionName", "expectPosition", "expectPositionName"));
        parsed.put("skills", firstPresent(basic, profile, "skills", "skillTags", "skill_tags", "tags", "geekSkillList", "skillList"));

        parsed.put("basic_info", basic.isEmpty() ? profile : basic);
        parsed.put("personal_advantage", advantage.isEmpty() ? firstPresentDeep(basic, profile, "personalAdvantage", "advantage", "summary", "description", "intro", "geekDesc", "selfDescription", "personalSummary") : advantage);
        parsed.put("job_status", status.isEmpty() ? firstPresentDeep(basic, profile, "jobStatus", "job_status", "status", "statusDesc", "applyStatus", "applyStatusDesc") : status);
        parsed.put("job_expectations", expectations.isEmpty() ? firstPresentDeep(basic, profile, "jobExpectations", "job_expectations", "expectations", "expectList", "jobExpectList", "geekExpectList") : expectations);
        parsed.put("work_experiences", work.isEmpty() ? firstPresentDeep(basic, profile, "workExperiences", "work_experiences", "workExpList", "workExperienceList", "geekWorkExpList", "workList", "workExperience") : work);
        parsed.put("project_experiences", project.isEmpty() ? firstPresentDeep(basic, profile, "projectExperiences", "project_experiences", "projectExpList", "projectExperienceList", "geekProjectExpList", "projectList", "projectExperience") : project);
        parsed.put("education_experiences", education.isEmpty() ? firstPresentDeep(basic, profile, "educationExperiences", "education_experiences", "education", "educations", "educationExpList", "eduExpList", "geekEduExpList", "eduList", "educationExperience") : education);
        parsed.put("job_intentions", intention.isEmpty() ? firstPresentDeep(expectations, profile, "jobIntentions", "job_intentions", "expectList", "expectPositionList", "jobExpectList", "geekExpectList", "expectations", "jobExpectations") : intention);

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

    private String buildBossSummary(Map<String, Object> basic, Map<String, Object> expectations, Map<String, Object> status, Map<String, Object> user, Map<String, Object> profile) {
        String direct = firstString(basic, profile, "summary", "advantage", "personalAdvantage", "description", "intro", "geekDesc");
        if (!direct.isEmpty()) return direct;
        StringBuilder builder = new StringBuilder();
        String name = firstString(basic, user, profile, "name", "userName", "user_name", "geekName", "nickName");
        String title = firstString(basic, expectations, profile, "currentTitle", "current_title", "position", "jobTitle", "positionName", "expectPosition");
        Object exp = firstPresent(basic, profile, "years_experience", "workYears", "work_years", "experience");
        Object expects = firstPresent(expectations, profile, "expected_titles", "expectPositions", "expect_positions", "positions", "expectList", "jobExpectList");
        Object state = firstPresent(status, profile, "status", "statusDesc", "jobStatus", "jobStatusDesc", "applyStatusDesc");
        if (!name.isEmpty()) builder.append(name);
        if (!title.isEmpty()) appendPart(builder, title);
        if (exp != null) appendPart(builder, "经验 " + exp);
        if (state != null) appendPart(builder, "求职状态 " + state);
        if (expects != null) appendPart(builder, "期望 " + expects);
        return builder.length() == 0 ? "已从 Boss 直聘在线资料同步，可作为问答画像上下文。" : builder.toString();
    }

    private String renderBossProfileText(Map<String, Object> parsed, Map<String, Object> raw) {
        StringBuilder builder = new StringBuilder();
        builder.append("# 求职画像\n\n");
        builder.append("姓名: ").append(stringOf(parsed.get("name"))).append('\n');
        builder.append("摘要: ").append(stringOf(parsed.get("summary"))).append("\n\n");
        builder.append("## 结构化信息\n").append(jsonCodec.toJson(parsed)).append("\n\n");
        builder.append("## 原始信息\n").append(jsonCodec.toJson(raw)).append('\n');
        return builder.toString();
    }

    private String fallbackProfileSummary(Map<String, Object> parsed) {
        Map<String, Object> basic = asMap(parsed.get("basic_info"));
        Map<String, Object> expectation = asMap(firstNonEmpty(parsed.get("job_expectations"), parsed.get("expectations")));
        Map<String, Object> status = asMap(parsed.get("job_status"));
        String name = firstText(parsed.get("name"), basic.get("name"));
        String years = firstText(parsed.get("years_experience"), basic.get("workYears"), basic.get("work_years"));
        String title = firstText(parsed.get("current_title"), basic.get("currentTitle"), basic.get("current_title"));
        String expectedTitle = firstText(parsed.get("expected_titles"), expectation.get("position"));
        String city = firstText(expectation.get("city"), basic.get("city"));
        String salary = firstText(expectation.get("salary"));
        String skills = shortText(parsed.get("skills"), 90);
        String advantage = firstSentence(firstText(parsed.get("personal_advantage")), 90);
        String rejects = firstText(expectation.get("rejectExcludes"), expectation.get("reject_excludes"), expectation.get("hard_excludes"), expectation.get("excludes"));
        String jobStatus = firstText(status.get("status"), status.get("statusDesc"));

        List<String> sentences = new ArrayList<String>();
        StringBuilder lead = new StringBuilder();
        if (!name.isEmpty()) lead.append(name).append("，");
        if (!years.isEmpty()) lead.append(years).append("经验");
        if (!title.isEmpty()) lead.append(lead.length() > 0 && lead.charAt(lead.length() - 1) != '，' ? "，" : "").append("当前方向为").append(title);
        if (lead.length() > 0) sentences.add(trimSentence(lead.toString()));

        StringBuilder target = new StringBuilder();
        if (!expectedTitle.isEmpty()) target.append("目标岗位聚焦").append(shortText(expectedTitle, 50));
        if (!city.isEmpty()) target.append(target.length() > 0 ? "，" : "").append("期望城市").append(shortText(city, 40));
        if (!salary.isEmpty()) target.append(target.length() > 0 ? "，" : "").append("薪资").append(shortText(salary, 30));
        if (!jobStatus.isEmpty()) target.append(target.length() > 0 ? "，" : "").append(shortText(jobStatus, 30));
        if (target.length() > 0) sentences.add(trimSentence(target.toString()));

        if (!skills.isEmpty()) sentences.add(trimSentence("核心技术栈包括" + skills));
        if (!advantage.isEmpty()) sentences.add(trimSentence(advantage));
        if (!rejects.isEmpty()) sentences.add(trimSentence("硬性排除" + shortText(rejects, 50)));
        String result = String.join("。", sentences).replaceAll("。+", "。").trim();
        if (!result.endsWith("。")) result = result + "。";
        return result.length() > 220 ? result.substring(0, 220) : (result.length() <= 1 ? "具备软件研发相关经验，关注岗位匹配度、技术栈契合度和长期发展空间，可结合岗位要求进一步补充项目亮点与技能证明。" : result);
    }

    private Object firstNonEmpty(Object... values) {
        for (Object value : values) if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
        return null;
    }

    private String firstText(Object... values) {
        Object value = firstNonEmpty(values);
        return value == null ? "" : shortText(value, 200).trim();
    }

    private String firstSentence(String value, int maxLength) {
        String text = value == null ? "" : value.replaceAll("[\\r\\n]+", " ").trim();
        String[] parts = text.split("[。！？；;]");
        return shortText(parts.length == 0 ? text : parts[0], maxLength);
    }

    private String trimSentence(String value) {
        return value == null ? "" : value.replaceAll("[。；;]+$", "").trim();
    }

    private String shortText(Object value, int maxLength) {
        String text = String.valueOf(value == null ? "" : value).replaceAll("[\\[\\]{}]", "").replaceAll("\\s+", " ").trim();
        return text.length() > maxLength ? text.substring(0, maxLength) : text;
    }

    private Map<String, Object> asMap(Object value) {
        if (value instanceof Map) return new LinkedHashMap<String, Object>((Map<String, Object>) value);
        return new LinkedHashMap<String, Object>();
    }

    private Object firstPresent(Map<String, Object> primary, Map<String, Object> secondary, String... keys) {
        Object value = firstPresent(primary, keys);
        return value != null ? value : firstPresent(secondary, keys);
    }

    private Object firstPresent(Map<String, Object> first, Map<String, Object> second, Map<String, Object> third, String... keys) {
        Object value = firstPresent(first, keys);
        if (value != null) return value;
        value = firstPresent(second, keys);
        return value != null ? value : firstPresent(third, keys);
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
        }
        return null;
    }

    private Object firstPresentDeep(Map<String, Object> primary, Map<String, Object> secondary, String... keys) {
        Object value = firstPresent(primary, keys);
        if (value != null) return value;
        value = deepFind(primary, keys, 0);
        if (value != null) return value;
        value = firstPresent(secondary, keys);
        return value != null ? value : deepFind(secondary, keys, 0);
    }

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

    private String firstString(Map<String, Object> first, Map<String, Object> second, Map<String, Object> third, String... keys) {
        Object value = firstPresent(first, second, third, keys);
        return value == null ? "" : String.valueOf(value);
    }

    private String firstString(Map<String, Object> primary, Map<String, Object> secondary, String... keys) {
        Object value = firstPresent(primary, secondary, keys);
        return value == null ? "" : String.valueOf(value);
    }

    private void appendPart(StringBuilder builder, String value) {
        if (value == null || value.trim().isEmpty()) return;
        if (builder.length() > 0) builder.append("，");
        builder.append(value.trim());
    }

    private Map<String, Object> analysisArgs(Path tempFile, ResumeRecord record) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("file_path", tempFile.toString());
        args.put("parsed", record.getParsed() == null ? Collections.<String, Object>emptyMap() : record.getParsed());
        return args;
    }

    private boolean isAnalyzeToolUnavailable(RuntimeException e) {
        return isAnalyzeToolUnavailable(e.getMessage());
    }

    private boolean isAnalyzeToolUnavailable(String message) {
        String text = message == null ? "" : message;
        return text.contains("resume_analyze") && (text.contains("工具未找到") || text.contains("已禁用") || text.contains("404"));
    }

    private void applyLocalAnalysisFallback(ResumeRecord record, String reason) {
        Map<String, Object> parsed = record.getParsed() == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(record.getParsed());
        Map<String, Object> analysis = new LinkedHashMap<String, Object>();
        analysis.put("overall_score", "-");
        analysis.put("summary", "已完成基础分析。Runtime 暂未启用 resume_analyze 工具，因此当前展示基于解析结果生成的兜底报告。" + (reason == null ? "" : " 原因：" + reason));
        analysis.put("advantages", java.util.Arrays.asList(
                "简历已成功上传并完成基础解析，可继续用于岗位匹配和问答上下文。",
                "候选人姓名、摘要、技能、经历等结构化字段已被提取，可作为后续优化依据。"
        ));
        analysis.put("disadvantages", java.util.Arrays.asList(
                "当前缺少大模型深度分析结果，暂无法给出更细粒度的优势、风险和面试深挖建议。"
        ));
        analysis.put("problems", java.util.Arrays.asList(
                "请启用 Runtime 的 resume_analyze 工具后重新点击开始分析，以获得完整报告。"
        ));
        analysis.put("interview_deep_dive_points", Collections.emptyList());
        analysis.put("layout_issues", Collections.emptyList());
        analysis.put("typo_issues", Collections.emptyList());
        analysis.put("action_items", java.util.Arrays.asList(
                "检查 agent-runtime 是否已重启并注册 ResumeAnalyzeTool。",
                "确认后端配置的 Runtime 地址指向最新运行实例。"
        ));
        parsed.put("analysis", analysis);
        record.setParsed(parsed);
    }

    private void applyAnalysisResult(ResumeRecord record, Map<String, Object> result) {
        if (!Boolean.TRUE.equals(result.get("success"))) {
            String error = stringOf(result.get("error"));
            if (isAnalyzeToolUnavailable(error)) {
                applyLocalAnalysisFallback(record, error);
                return;
            }
            Map<String, Object> parsed = record.getParsed() == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(record.getParsed());
            Map<String, Object> analysis = new LinkedHashMap<String, Object>();
            analysis.put("summary", "简历分析失败: " + error);
            analysis.put("advantages", Collections.emptyList());
            analysis.put("disadvantages", Collections.emptyList());
            analysis.put("problems", Collections.emptyList());
            analysis.put("interview_deep_dive_points", Collections.emptyList());
            analysis.put("layout_issues", Collections.emptyList());
            analysis.put("typo_issues", Collections.emptyList());
            parsed.put("analysis", analysis);
            record.setParsed(parsed);
            return;
        }
        Object output = result.get("output");
        if (output instanceof Map) {
            Object analysis = ((Map) output).get("analysis");
            if (analysis instanceof Map) {
                Map<String, Object> parsed = record.getParsed() == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(record.getParsed());
                parsed.put("analysis", analysis);
                record.setParsed(parsed);
            }
        }
    }

    private void applyParseResult(ResumeRecord record, Map<String, Object> result) {
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (!success) {
            record.setParseStatus("fail");
            record.setParseError(stringOf(result.get("error")));
            return;
        }
        Object output = result.get("output");
        if (output instanceof Map) {
            Object parsed = ((Map) output).get("resume");
            if (parsed instanceof Map) record.setParsed((Map<String, Object>) parsed);
        }
        record.setParseStatus("success");
        record.setParseError(null);
    }

    private void validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("上传文件不能为空");
        if (file.getSize() > properties.getMaxResumeBytes()) {
            throw new IllegalArgumentException("简历文件超出大小限制: " + properties.getMaxResumeBytes() + " bytes");
        }
    }


    private Path thumbnailCachePath(ResumeRecord record) {
        String uploaded = record.getUploadedAt() == null ? "0" : String.valueOf(record.getUploadedAt().toEpochMilli());
        return Paths.get(System.getProperty("java.io.tmpdir"), "job-buddy-resume-thumbnails", record.getResumeId() + "-" + uploaded + ".png");
    }

    private byte[] renderPdfFirstPage(Path pdfFile) throws IOException {
        PDDocument document = PDDocument.load(pdfFile.toFile());
        try {
            PDFRenderer renderer = new PDFRenderer(document);
            BufferedImage rendered = renderer.renderImageWithDPI(0, 92, ImageType.RGB);
            int targetWidth = 260;
            int targetHeight = Math.max(1, rendered.getHeight() * targetWidth / Math.max(1, rendered.getWidth()));
            BufferedImage scaled = new BufferedImage(targetWidth, targetHeight, BufferedImage.TYPE_INT_RGB);
            Graphics2D graphics = scaled.createGraphics();
            try {
                graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                graphics.drawImage(rendered, 0, 0, targetWidth, targetHeight, null);
            } finally {
                graphics.dispose();
            }
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(scaled, "png", output);
            return output.toByteArray();
        } finally {
            document.close();
        }
    }


    private byte[] placeholderThumbnail(ResumeRecord record) {
        int width = 260;
        int height = 340;
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_RGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(java.awt.Color.WHITE);
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new java.awt.Color(229, 235, 245));
            graphics.fillRect(0, 0, width, height);
            graphics.setColor(new java.awt.Color(49, 87, 255));
            graphics.fillRoundRect(22, 26, 56, 28, 8, 8);
            graphics.setColor(java.awt.Color.WHITE);
            graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 15));
            graphics.drawString((record.getSuffix() == null ? "CV" : record.getSuffix()).toUpperCase(java.util.Locale.ROOT), 36, 46);
            graphics.setColor(new java.awt.Color(23, 32, 51));
            graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.BOLD, 18));
            graphics.drawString("简历文件", 22, 94);
            graphics.setColor(new java.awt.Color(102, 112, 133));
            graphics.setFont(new java.awt.Font("SansSerif", java.awt.Font.PLAIN, 13));
            String name = record.getOriginalName() == null ? "Resume" : record.getOriginalName();
            if (name.length() > 15) name = name.substring(0, 15) + "...";
            graphics.drawString(name, 22, 122);
        } finally {
            graphics.dispose();
        }
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            ImageIO.write(image, "png", output);
            return output.toByteArray();
        } catch (IOException e) {
            return new byte[0];
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

    private byte[] initAssetSigningKey(JobBuddyProperties properties) {
        String configured = properties.getAuth().getAssetUrlSigningKey();
        if (configured != null && !configured.trim().isEmpty()) {
            return sha256Bytes(configured.trim());
        }
        String minioSecret = properties.getMinio() == null ? "" : properties.getMinio().getSecretKey();
        if (minioSecret != null && !minioSecret.trim().isEmpty() && !minioSecret.contains("${")) {
            return sha256Bytes(minioSecret.trim());
        }
        byte[] random = new byte[32];
        new SecureRandom().nextBytes(random);
        return random;
    }

    private String signAssetToken(String objectName, String userId) {
        Map<String, Object> payload = new LinkedHashMap<String, Object>();
        payload.put("objectName", objectName);
        payload.put("userId", userId);
        payload.put("exp", Long.valueOf(Instant.now().plusSeconds(assetUrlTtlSeconds()).getEpochSecond()));
        String payloadJson = jsonCodec.toJson(payload);
        String encodedPayload = base64Url(payloadJson.getBytes(StandardCharsets.UTF_8));
        String encodedSignature = base64Url(hmacSha256(encodedPayload));
        return encodedPayload + "." + encodedSignature;
    }

    private String requireAssetObjectName(String token, String userId) {
        if (token == null || token.trim().isEmpty()) throw new IllegalArgumentException("非法资源标识");
        String[] parts = token.split("\\.", -1);
        if (parts.length != 2 || parts[0].isEmpty() || parts[1].isEmpty()) {
            throw new IllegalArgumentException("非法资源标识");
        }
        byte[] expected = hmacSha256(parts[0]);
        byte[] actual;
        try {
            actual = Base64.getUrlDecoder().decode(parts[1]);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法资源标识", e);
        }
        if (!MessageDigest.isEqual(expected, actual)) {
            throw new IllegalArgumentException("非法资源标识");
        }
        Map<String, Object> payload;
        try {
            payload = jsonCodec.toMap(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("非法资源标识", e);
        }
        String effectiveUser = defaultUser(userId);
        String tokenUser = stringOf(payload.get("userId"));
        String objectName = stringOf(payload.get("objectName"));
        long exp = longValue(payload.get("exp"), 0L);
        if (exp < Instant.now().getEpochSecond()) throw new IllegalArgumentException("资源链接已过期");
        String legacyUser = defaultUser(null);
        boolean legacyDefaultAsset = legacyUser.equals(tokenUser) && !isDefaultUser(effectiveUser);
        if (!effectiveUser.equals(tokenUser) && !legacyDefaultAsset) throw new IllegalArgumentException("无权访问该资源");
        String ownerPrefix = legacyDefaultAsset ? legacyUser : effectiveUser;
        if (!objectName.startsWith(ownerPrefix + "/assets/")) throw new IllegalArgumentException("非法资源路径");
        String suffix = extractSuffix(objectName);
        if (!ALLOWED_ASSET_SUFFIXES.contains(suffix)) throw new IllegalArgumentException("非法资源类型");
        return objectName;
    }

    private long assetUrlTtlSeconds() {
        return Math.max(60L, properties.getAuth().getAssetUrlTtlSeconds());
    }

    private byte[] hmacSha256(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(assetSigningKey, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("资源签名失败", e);
        }
    }

    private byte[] sha256Bytes(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("资源签名密钥初始化失败", e);
        }
    }

    private String base64Url(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
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

    private String extractSuffix(String name) {
        int idx = name.lastIndexOf('.');
        return idx <= 0 || idx == name.length() - 1 ? "" : name.substring(idx + 1).toLowerCase();
    }

    private String stringOf(Object value) { return value == null ? "" : String.valueOf(value); }

    private long longValue(Object value, long fallback) {
        if (value instanceof Number) return ((Number) value).longValue();
        if (value == null) return fallback;
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return fallback;
        }
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
