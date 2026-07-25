package com.jobbuddy.backend.modules.prompt.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.common.util.JsonCodec;

/**
 * 定义用户画像上下文。
 */
public class UserProfileContext {
  private static final JsonCodec JSON = new JsonCodec();
  private final JsonNode profile;
  private final String summary;

  /**
   * 创建用户画像上下文实例。
   *
   * @param profile 画像
   * @param summary 摘要
   */
  public UserProfileContext(Object profile, String summary) {
    this.profile = JSON.toTree(profile);
    this.summary = summary == null ? "" : summary;
  }

  /**
   * 获取画像。
   *
   * @return 画像
   */
  public JsonNode getProfile() {
    return profile.deepCopy();
  }

  /**
   * 获取摘要。
   *
   * @return 摘要
   */
  public String getSummary() {
    return summary;
  }

  /**
   * 判断是否空值。
   *
   * @return 是否未包含画像内容
   */
  public boolean isEmpty() {
    return profile.isEmpty() && summary.trim().isEmpty();
  }
}
