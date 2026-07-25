package com.jobbuddy.backend.modules.job.dto.response;

/**
 * 单个 Boss 岗位的选择性导入结果。
 */
public class BossFavoriteImportItemResponse {
  private final String jobKey;
  private final String status;
  private final String message;

  /**
   * 创建 Boss 收藏岗位导入数据项响应实例。
   *
   * @param jobKey 岗位键
   * @param status 状态
   * @param message 消息内容
   */
  public BossFavoriteImportItemResponse(String jobKey, String status, String message) {
    this.jobKey = jobKey;
    this.status = status;
    this.message = message;
  }

  /**
   * 获取岗位键。
   *
   * @return 岗位键
   */
  public String getJobKey() {
    return jobKey;
  }

  /**
   * 获取状态。
   *
   * @return 状态
   */
  public String getStatus() {
    return status;
  }

  /**
   * 获取消息。
   *
   * @return 消息
   */
  public String getMessage() {
    return message;
  }
}
