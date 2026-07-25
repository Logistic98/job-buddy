package com.jobbuddy.backend.modules.resume.entity;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 定义简历记录。
 */
public class ResumeRecord {
  private String resumeId;
  private String tenantId;
  private String userId;
  private String originalName;
  private String storagePath;
  private long sizeBytes;
  private String suffix;
  private Instant uploadedAt;
  private String parseStatus;
  private Map<String, Object> parsed;
  private String parsedJson;
  private String parseError;

  /**
   * 创建简历记录实例。
   */
  public ResumeRecord() {
    this.parsed = new LinkedHashMap<String, Object>();
  }

  /**
   * 获取简历标识。
   *
   * @return 简历标识
   */
  public String getResumeId() {
    return resumeId;
  }

  /**
   * 设置简历标识。
   *
   * @param resumeId 简历标识
   */
  public void setResumeId(String resumeId) {
    this.resumeId = resumeId;
  }

  /**
   * 获取租户标识。
   *
   * @return 租户标识
   */
  public String getTenantId() {
    return tenantId;
  }

  /**
   * 设置租户标识。
   *
   * @param tenantId 租户标识
   */
  public void setTenantId(String tenantId) {
    this.tenantId = tenantId;
  }

  /**
   * 获取用户标识。
   *
   * @return 用户标识
   */
  public String getUserId() {
    return userId;
  }

  /**
   * 设置用户标识。
   *
   * @param userId 用户标识
   */
  public void setUserId(String userId) {
    this.userId = userId;
  }

  /**
   * 获取原始名称。
   *
   * @return 原始名称
   */
  public String getOriginalName() {
    return originalName;
  }

  /**
   * 设置原始名称。
   *
   * @param originalName 原始名称
   */
  public void setOriginalName(String originalName) {
    this.originalName = originalName;
  }

  /**
   * 获取存储路径。
   *
   * @return 存储路径
   */
  public String getStoragePath() {
    return storagePath;
  }

  /**
   * 设置存储路径。
   *
   * @param storagePath 存储路径
   */
  public void setStoragePath(String storagePath) {
    this.storagePath = storagePath;
  }

  /**
   * 获取大小字节。
   *
   * @return 大小字节数
   */
  public long getSizeBytes() {
    return sizeBytes;
  }

  /**
   * 设置大小字节。
   *
   * @param sizeBytes 大小字节
   */
  public void setSizeBytes(long sizeBytes) {
    this.sizeBytes = sizeBytes;
  }

  /**
   * 获取文件后缀。
   *
   * @return 文件后缀
   */
  public String getSuffix() {
    return suffix;
  }

  /**
   * 设置文件后缀。
   *
   * @param suffix 后缀
   */
  public void setSuffix(String suffix) {
    this.suffix = suffix;
  }

  /**
   * 获取上传时间。
   *
   * @return 上传时间
   */
  public Instant getUploadedAt() {
    return uploadedAt;
  }

  /**
   * 设置上传时间。
   *
   * @param uploadedAt 上传时间
   */
  public void setUploadedAt(Instant uploadedAt) {
    this.uploadedAt = uploadedAt;
  }

  /**
   * 获取解析状态。
   *
   * @return 解析状态
   */
  public String getParseStatus() {
    return parseStatus;
  }

  /**
   * 设置解析状态。
   *
   * @param parseStatus 解析状态
   */
  public void setParseStatus(String parseStatus) {
    this.parseStatus = parseStatus;
  }

  /**
   * 获取解析后。
   *
   * @return 解析后
   */
  public Map<String, Object> getParsed() {
    return parsed;
  }

  /**
   * 设置解析后。
   *
   * @param parsed 解析结果
   */
  public void setParsed(Map<String, Object> parsed) {
    this.parsed = parsed;
  }

  /**
   * 获取解析后 JSON。
   *
   * @return 解析后 JSON
   */
  public String getParsedJson() {
    return parsedJson;
  }

  /**
   * 设置解析后 JSON。
   *
   * @param parsedJson 解析结果 JSON
   */
  public void setParsedJson(String parsedJson) {
    this.parsedJson = parsedJson;
  }

  /**
   * 获取解析错误。
   *
   * @return 解析错误
   */
  public String getParseError() {
    return parseError;
  }

  /**
   * 设置解析错误。
   *
   * @param parseError 解析错误
   */
  public void setParseError(String parseError) {
    this.parseError = parseError;
  }
}
