package com.jobbuddy.backend.modules.resume.service.impl;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.resume.dto.request.ResumeWriterRestoreRequest;
import com.jobbuddy.backend.modules.resume.dto.request.ResumeWriterVersionCreateRequest;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeWriterVersionResponse;
import com.jobbuddy.backend.modules.resume.mapper.ResumeWriterVersionMapper;
import com.jobbuddy.backend.modules.resume.service.ResumeWriterVersionService;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ResumeWriterVersionServiceImpl implements ResumeWriterVersionService {

  private static final Logger LOG = LoggerFactory.getLogger(ResumeWriterVersionService.class);
  private static final Set<String> ALLOWED_SOURCES =
      new HashSet<String>(
          Arrays.asList(SOURCE_MANUAL, SOURCE_AUTO, SOURCE_IMPORT_BACKUP, SOURCE_RESTORE_BACKUP));
  private static final int MAX_TITLE_LENGTH = 256;

  private final ResumeWriterVersionMapper mapper;
  private final JobBuddyProperties properties;
  private final JsonCodec jsonCodec;

  @Autowired
  public ResumeWriterVersionServiceImpl(
      ResumeWriterVersionMapper mapper, JobBuddyProperties properties, JsonCodec jsonCodec) {
    this.mapper = mapper;
    this.properties = properties;
    this.jsonCodec = jsonCodec;
  }

  public ResumeWriterVersionServiceImpl(
      ResumeWriterVersionMapper mapper, JobBuddyProperties properties) {
    this(mapper, properties, new JsonCodec());
  }

  public List<ResumeWriterVersionResponse> list(String tenantId, String userId) {
    requireOwner(tenantId, userId);
    return jsonCodec.convertList(
        mapper.listByOwner(tenantId, userId, versionLimit()), ResumeWriterVersionResponse.class);
  }

  public ResumeWriterVersionResponse get(String tenantId, String userId, String versionId) {
    return jsonCodec.convert(
        getMap(tenantId, userId, versionId), ResumeWriterVersionResponse.class);
  }

  private Map<String, Object> getMap(String tenantId, String userId, String versionId) {
    requireOwner(tenantId, userId);
    Map<String, Object> version =
        mapper.findByIdAndOwner(tenantId, userId, requireVersionId(versionId));
    if (version == null) throw new IllegalArgumentException("版本不存在: " + versionId);
    return version;
  }

  @Transactional
  public ResumeWriterVersionResponse create(
      String tenantId, String userId, ResumeWriterVersionCreateRequest request) {
    if (request == null) throw new IllegalArgumentException("请求体不能为空");
    return jsonCodec.convert(
        createMap(
            tenantId,
            userId,
            request.getResumeId(),
            request.getSource(),
            request.getTitle(),
            request.getSnapshot()),
        ResumeWriterVersionResponse.class);
  }

  private Map<String, Object> createMap(
      String tenantId,
      String userId,
      String resumeId,
      String source,
      String title,
      String snapshotJson) {
    requireOwner(tenantId, userId);
    String safeSource = source == null ? "" : source.trim();
    if (!ALLOWED_SOURCES.contains(safeSource)) {
      throw new IllegalArgumentException("不支持的版本来源: " + source);
    }
    if (snapshotJson == null || snapshotJson.trim().isEmpty()) {
      throw new IllegalArgumentException("版本快照不能为空");
    }
    int snapshotBytes = snapshotJson.getBytes(StandardCharsets.UTF_8).length;
    if (snapshotBytes > properties.getResumeWriterSnapshotMaxBytes()) {
      throw new IllegalArgumentException(
          "版本快照超出大小限制: " + properties.getResumeWriterSnapshotMaxBytes() + " bytes");
    }

    Long maxNo = mapper.maxVersionNo(tenantId, userId);
    long versionNo = (maxNo == null ? 0L : maxNo.longValue()) + 1L;
    Map<String, Object> version = new LinkedHashMap<String, Object>();
    String versionId = "rwv_" + UUID.randomUUID().toString().replace("-", "").substring(0, 20);
    version.put("versionId", versionId);
    version.put("tenantId", tenantId);
    version.put("userId", userId);
    version.put("resumeId", resumeId == null || resumeId.trim().isEmpty() ? null : resumeId.trim());
    version.put("versionNo", Long.valueOf(versionNo));
    version.put("source", safeSource);
    version.put("title", normalizeTitle(title, safeSource, versionNo));
    version.put("snapshotJson", snapshotJson);
    version.put("createdAt", Instant.now());
    mapper.insertVersion(version);
    int trimmed = mapper.deleteBeyondLimit(tenantId, userId, versionLimit());
    if (trimmed > 0) {
      LOG.info(
          "撰写版本历史已裁剪 - tenant: {}, user: {}, trimmed: {}, keep: {}",
          tenantId,
          userId,
          trimmed,
          versionLimit());
    }
    Map<String, Object> view = new LinkedHashMap<String, Object>(version);
    view.remove("snapshotJson");
    view.put("snapshotBytes", Integer.valueOf(snapshotBytes));
    return view;
  }

  @Transactional
  public ResumeWriterVersionResponse restore(
      String tenantId, String userId, String versionId, ResumeWriterRestoreRequest request) {
    Map<String, Object> target = getMap(tenantId, userId, versionId);
    String currentSnapshotJson = request == null ? null : request.getCurrentSnapshot();
    if (currentSnapshotJson != null && !currentSnapshotJson.trim().isEmpty()) {
      createMap(
          tenantId,
          userId,
          request.getCurrentResumeId(),
          SOURCE_RESTORE_BACKUP,
          "回退前自动备份",
          currentSnapshotJson);
    }
    return jsonCodec.convert(target, ResumeWriterVersionResponse.class);
  }

  @Transactional
  public void delete(String tenantId, String userId, String versionId) {
    requireOwner(tenantId, userId);
    int deleted = mapper.deleteByIdAndOwner(tenantId, userId, requireVersionId(versionId));
    if (deleted == 0) throw new IllegalArgumentException("版本不存在: " + versionId);
  }

  private int versionLimit() {
    int limit = properties.getResumeWriterVersionLimit();
    return limit > 0 ? limit : 30;
  }

  private String normalizeTitle(String title, String source, long versionNo) {
    String safe = title == null ? "" : title.trim();
    if (safe.isEmpty()) {
      if (SOURCE_AUTO.equals(source)) safe = "自动快照";
      else if (SOURCE_IMPORT_BACKUP.equals(source)) safe = "导入前自动备份";
      else if (SOURCE_RESTORE_BACKUP.equals(source)) safe = "回退前自动备份";
      else safe = "手动保存";
    }
    if (safe.length() > MAX_TITLE_LENGTH) safe = safe.substring(0, MAX_TITLE_LENGTH);
    return safe;
  }

  private void requireOwner(String tenantId, String userId) {
    if (tenantId == null || tenantId.trim().isEmpty())
      throw new IllegalArgumentException("当前账号缺少租户归属");
    if (userId == null || userId.trim().isEmpty()) throw new IllegalArgumentException("未登录或登录已过期");
  }

  private String requireVersionId(String versionId) {
    if (versionId == null || versionId.trim().isEmpty())
      throw new IllegalArgumentException("版本 ID 不能为空");
    return versionId.trim();
  }
}
