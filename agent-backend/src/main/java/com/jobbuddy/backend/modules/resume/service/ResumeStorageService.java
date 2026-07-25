package com.jobbuddy.backend.modules.resume.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.modules.analysis.dto.AnalysisPartialResult;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeAssetUploadResponse;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeProfileSummaryResponse;
import com.jobbuddy.backend.modules.resume.dto.response.ResumeSummaryResponse;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.function.Consumer;
import org.springframework.web.multipart.MultipartFile;

/**
 * 定义简历存储服务契约。
 */
public interface ResumeStorageService {
  /**
   * 上传简历存储。
   *
   * @param file 上传文件
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 上传结果
   * @throws IOException 文件或网络读写失败时抛出
   */
  ResumeRecord upload(MultipartFile file, String tenantId, String userId) throws IOException;

  /**
   * 上传简历存储。
   *
   * @param file 上传文件
   * @param originalName 原始文件名
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 上传结果
   * @throws IOException 文件或网络读写失败时抛出
   */
  ResumeRecord upload(MultipartFile file, String originalName, String tenantId, String userId)
      throws IOException;

  /**
   * 上传资源。
   *
   * @param file 上传文件
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 上传后的附件
   * @throws IOException 文件或网络读写失败时抛出
   */
  ResumeAssetUploadResponse uploadAsset(MultipartFile file, String tenantId, String userId)
      throws IOException;

  /**
   * 打开资源。
   *
   * @param assetToken 附件访问令牌
   * @param userId 用户标识
   * @return 可读取的附件
   */
  InputStream openAsset(String assetToken, String userId);

  /**
   * 解析资源内容类型。
   *
   * @param assetToken 附件访问令牌
   * @param userId 用户标识
   * @return 资源内容类型
   */
  String assetContentType(String assetToken, String userId);

  /**
   * 同步 Boss 在线数据简历。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return Boss 在线简历同步结果
   * @throws IOException 文件或网络读写失败时抛出
   */
  ResumeRecord syncBossOnlineResume(String tenantId, String userId) throws IOException;

  /**
   * 获取岗位画像或空值。
   *
   * @param userId 用户标识
   * @return 岗位画像或空值
   */
  ResumeSummaryResponse getJobProfileOrEmpty(String userId);

  /**
   * 获取岗位画像或空值。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 岗位画像或空值
   */
  ResumeSummaryResponse getJobProfileOrEmpty(String tenantId, String userId);

  /**
   * 获取或创建岗位画像。
   *
   * @param userId 用户标识
   * @return 或创建岗位画像
   * @throws IOException 文件或网络读写失败时抛出
   */
  ResumeRecord getOrCreateJobProfile(String userId) throws IOException;

  /**
   * 保存岗位画像。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param parsed 解析结果
   * @return 保存后的岗位画像
   * @throws IOException 文件或网络读写失败时抛出
   */
  ResumeRecord saveJobProfile(String tenantId, String userId, JsonNode parsed) throws IOException;

  /**
   * 生成岗位画像摘要。
   *
   * @param parsed 解析结果
   * @param sessionId 会话标识
   * @return 岗位画像摘要
   */
  ResumeProfileSummaryResponse generateJobProfileSummary(JsonNode parsed, String sessionId);

  /**
   * 按属主范围读取简历记录。
   *
   * @param resumeId 简历标识
   * @return 查询结果
   */
  ResumeRecord get(String resumeId);

  /**
   * 按属主范围读取简历记录。
   *
   * @param resumeId 简历标识
   * @param userId 用户标识
   * @return 查询结果
   */
  ResumeRecord get(String resumeId, String userId);

  /**
   * 按属主范围读取简历记录。
   *
   * @param resumeId 简历标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 查询结果
   */
  ResumeRecord get(String resumeId, String tenantId, String userId);

  /**
   * 打开原始简历文件。
   *
   * @param resumeId 简历标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 可读取的原始文件
   */
  InputStream openOriginalFile(String resumeId, String tenantId, String userId);

  /**
   * 获取缩略图。
   *
   * @param resumeId 简历标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 缩略图
   */
  byte[] thumbnail(String resumeId, String tenantId, String userId);

  /**
   * 更新解析结果。
   *
   * @param resumeId 简历标识
   * @param parsed 解析结果
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 更新后的解析结果
   */
  ResumeRecord updateParsed(String resumeId, JsonNode parsed, String tenantId, String userId);

  /**
   * 删除简历存储。
   *
   * @param resumeId 简历标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   */
  void delete(String resumeId, String tenantId, String userId);

  /**
   * 查询简历存储列表。
   *
   * @param userId 用户标识
   * @return 数据列表
   */
  List<ResumeSummaryResponse> list(String userId);

  /**
   * 查询简历存储列表。
   *
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 数据列表
   */
  List<ResumeSummaryResponse> list(String tenantId, String userId);

  /**
   * 分析同步结果。
   *
   * @param resumeId 简历标识
   * @param sessionId 会话标识
   * @return 同步分析结果
   */
  ResumeRecord analyzeSync(String resumeId, String sessionId);

  /**
   * 分析同步结果。
   *
   * @param resumeId 简历标识
   * @param sessionId 会话标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 同步分析结果
   */
  ResumeRecord analyzeSync(String resumeId, String sessionId, String tenantId, String userId);

  /**
   * 分析增量结果。
   *
   * @param resumeId 简历标识
   * @param sessionId 会话标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @param consumer 结果消费函数
   * @return 增量分析结果
   */
  ResumeRecord analyzeIncrementally(
      String resumeId,
      String sessionId,
      String tenantId,
      String userId,
      Consumer<AnalysisPartialResult> consumer);

  /**
   * 解析同步结果。
   *
   * @param resumeId 简历标识
   * @param sessionId 会话标识
   * @return 同步结果
   */
  ResumeRecord parseSync(String resumeId, String sessionId);

  /**
   * 解析同步结果。
   *
   * @param resumeId 简历标识
   * @param sessionId 会话标识
   * @param tenantId 租户标识
   * @param userId 用户标识
   * @return 同步结果
   */
  ResumeRecord parseSync(String resumeId, String sessionId, String tenantId, String userId);

  /**
   * 生成简历存储摘要。
   *
   * @param record 记录
   * @return 摘要文本
   */
  ResumeSummaryResponse summarize(ResumeRecord record);
}
