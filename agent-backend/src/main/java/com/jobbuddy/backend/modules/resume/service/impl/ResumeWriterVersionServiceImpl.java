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

/**
 * 管理简历撰写快照的创建、回退与容量裁剪。
 *
 * <p>版本号在用户维度单调递增；回退前可先保存当前快照，避免覆盖后无法恢复。
 */
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

  /**
   * 创建简历撰写器版本服务实例。
   *
   * @param mapper 数据映射
   * @param properties 配置属性
   * @param jsonCodec JSON 编解码器
   */
  @Autowired
  public ResumeWriterVersionServiceImpl(
      ResumeWriterVersionMapper mapper, JobBuddyProperties properties, JsonCodec jsonCodec) {
    this.mapper = mapper;
    this.properties = properties;
    this.jsonCodec = jsonCodec;
  }

  /**
   * 创建简历撰写器版本服务实例。
   *
   * @param mapper 数据映射
   * @param properties 配置属性
   */
  public ResumeWriterVersionServiceImpl(
      ResumeWriterVersionMapper mapper, JobBuddyProperties properties) {
    this(mapper, properties, new JsonCodec());
  }

  /**
   * 查询简历版本列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 数据列表
   */
  public List<ResumeWriterVersionResponse> list(String tenantId, String userId) {
    requireOwner(tenantId, userId);
    return jsonCodec.convertList(
        mapper.listByOwner(tenantId, userId, versionLimit()), ResumeWriterVersionResponse.class);
  }

  /**
   * 按标识读取数据。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param versionId 版本标识
   * @return 查询结果
   */
  public ResumeWriterVersionResponse get(String tenantId, String userId, String versionId) {
    return jsonCodec.convert(
        getMap(tenantId, userId, versionId), ResumeWriterVersionResponse.class);
  }

  /**
   * 获取映射。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param versionId 版本标识
   * @return 映射
   */
  private Map<String, Object> getMap(String tenantId, String userId, String versionId) {
    requireOwner(tenantId, userId);
    Map<String, Object> version =
        mapper.findByIdAndOwner(tenantId, userId, requireVersionId(versionId));
    if (version == null) throw new IllegalArgumentException("版本不存在: " + versionId);
    return version;
  }

  /**
   * 创建租户用户。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param request 请求对象
   * @return 创建后的资源数据
   */
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

  /**
   * 创建映射。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param resumeId 简历标识
   * @param source 源数据
   * @param title 标题
   * @param snapshotJson 快照 JSON
   * @return 创建后的映射
   */
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

  /**
   * 回退到目标版本；请求携带当前快照时，先创建回退前备份。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param versionId 版本标识
   * @param request 请求对象
   * @return 恢复结果
   */
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

  /**
   * 删除指定简历版本。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param versionId 版本标识
   */
  @Transactional
  public void delete(String tenantId, String userId, String versionId) {
    requireOwner(tenantId, userId);
    int deleted = mapper.deleteByIdAndOwner(tenantId, userId, requireVersionId(versionId));
    if (deleted == 0) throw new IllegalArgumentException("版本不存在: " + versionId);
  }

  /**
   * 计算版本限制。
   *
   * @return 版本数量上限
   */
  private int versionLimit() {
    int limit = properties.getResumeWriterVersionLimit();
    return limit > 0 ? limit : 30;
  }

  /**
   * 规范化标题。
   *
   * @param title 标题
   * @param source 源数据
   * @param versionNo 版本号
   * @return 规范化后的标题
   */
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

  /**
   * 校验并获取属主。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   */
  private void requireOwner(String tenantId, String userId) {
    if (tenantId == null || tenantId.trim().isEmpty())
      throw new IllegalArgumentException("当前账号缺少租户归属");
    if (userId == null || userId.trim().isEmpty()) throw new IllegalArgumentException("未登录或登录已过期");
  }

  /**
   * 校验并获取版本标识。
   *
   * @param versionId 版本标识
   * @return 校验后的并获取版本标识
   */
  private String requireVersionId(String versionId) {
    if (versionId == null || versionId.trim().isEmpty())
      throw new IllegalArgumentException("版本 ID 不能为空");
    return versionId.trim();
  }
}
