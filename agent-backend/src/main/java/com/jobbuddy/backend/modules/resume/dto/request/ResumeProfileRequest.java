package com.jobbuddy.backend.modules.resume.dto.request;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.databind.JsonNode;

/**
 * 承载简历画像请求参数。
 */
public class ResumeProfileRequest {
  private final JsonNode payload;

  /**
   * 创建简历画像请求实例。
   *
   * @param payload 请求载荷
   */
  @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
  public ResumeProfileRequest(JsonNode payload) {
    this.payload = payload;
  }

  /**
   * 解析并规范化请求载荷。
   *
   * @return 规范化载荷
   */
  public JsonNode parsedPayload() {
    if (payload == null || payload.isNull()) return null;
    JsonNode parsed = payload.get("parsed");
    return parsed != null && parsed.isObject() ? parsed.deepCopy() : payload.deepCopy();
  }
}
