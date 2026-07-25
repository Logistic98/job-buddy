package com.jobbuddy.backend.modules.resume.service;

import com.jobbuddy.backend.modules.resume.dto.request.ResumeWriterRestoreRequest;
import com.jobbuddy.backend.modules.resume.dto.request.ResumeWriterVersionCreateRequest;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeWriterVersionResponse;
import java.util.List;

/**
 * 定义简历撰写器版本服务契约。
 */
public interface ResumeWriterVersionService {
  String SOURCE_MANUAL = "manual";
  String SOURCE_AUTO = "auto";
  String SOURCE_IMPORT_BACKUP = "import_backup";
  String SOURCE_RESTORE_BACKUP = "restore_backup";

  /**
   * 查询简历版本列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 数据列表
   */
  List<ResumeWriterVersionResponse> list(String tenantId, String userId);

  /**
   * 按标识读取数据。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param versionId 版本标识
   * @return 查询结果
   */
  ResumeWriterVersionResponse get(String tenantId, String userId, String versionId);

  /**
   * 创建租户用户。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param request 请求对象
   * @return 创建后的资源数据
   */
  ResumeWriterVersionResponse create(
      String tenantId, String userId, ResumeWriterVersionCreateRequest request);

  /**
   * 恢复指定简历版本。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param versionId 版本标识
   * @param request 请求对象
   * @return 恢复结果
   */
  ResumeWriterVersionResponse restore(
      String tenantId, String userId, String versionId, ResumeWriterRestoreRequest request);

  /**
   * 删除指定简历版本。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param versionId 版本标识
   */
  void delete(String tenantId, String userId, String versionId);
}
