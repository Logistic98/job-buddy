package com.jobbuddy.backend.modules.job.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 承载收藏岗位请求参数。
 */
public class JobFavoriteRequest {
  private final JsonNode snapshot;

  /**
   * 创建收藏岗位请求实例。
   *
   * @param snapshot 快照
   */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public JobFavoriteRequest(JsonNode snapshot) {
    this.snapshot = snapshot;
  }

  /**
   * 生成当前数据快照。
   *
   * @return 数据快照
   */
  public JsonNode snapshot() {
    return snapshot == null ? null : snapshot.deepCopy();
  }

  /**
   * 解析岗位唯一键。
   *
   * @return 岗位唯一键
   */
  public String jobKey() {
    return firstText("jobKey", "favoriteKey", "securityId", "id", "jobId", "encryptJobId");
  }

  /**
   * 解析简历标识。
   *
   * @return 简历标识
   */
  public String resumeId() {
    return text("resumeId");
  }

  /**
   * 读取首个非空文本。
   *
   * @param names 名称列表
   * @return 首个非空文本
   */
  private String firstText(String... names) {
    for (String name : names) {
      String value = text(name);
      if (value != null) return value;
    }
    return null;
  }

  /**
   * 读取文本内容。
   *
   * @param name 名称
   * @return 文本内容
   */
  private String text(String name) {
    if (snapshot == null) return null;
    JsonNode value = snapshot.get(name);
    if (value == null || value.isNull()) return null;
    String text = value.asText().trim();
    return text.isEmpty() ? null : text;
  }
}
