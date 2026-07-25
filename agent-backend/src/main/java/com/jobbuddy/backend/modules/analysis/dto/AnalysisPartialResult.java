package com.jobbuddy.backend.modules.analysis.dto;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

/**
 * 定义分析增量结果。
 */
public class AnalysisPartialResult {
  private final String section;
  private final String message;
  private final JsonNode payload;

  /**
   * 创建分析增量结果实例。
   *
   * @param section 章节
   * @param message 消息内容
   * @param payload 请求载荷
   */
  public AnalysisPartialResult(String section, String message, JsonNode payload) {
    this.section = section;
    this.message = message;
    this.payload = payload == null ? JsonNodeFactory.instance.objectNode() : payload.deepCopy();
  }

  /**
   * 获取结果分段。
   *
   * @return 内容章节
   */
  public String getSection() {
    return section;
  }

  /**
   * 获取消息。
   *
   * @return 消息
   */
  public String getMessage() {
    return message;
  }

  /**
   * 获取载荷。
   *
   * @return 载荷
   */
  public JsonNode getPayload() {
    return payload.deepCopy();
  }
}
