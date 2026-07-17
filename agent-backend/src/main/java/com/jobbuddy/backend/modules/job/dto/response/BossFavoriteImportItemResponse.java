package com.jobbuddy.backend.modules.job.dto.response;

/** 单个 Boss 岗位的选择性导入结果。 */
public class BossFavoriteImportItemResponse {
  private final String jobKey;
  private final String status;
  private final String message;

  public BossFavoriteImportItemResponse(String jobKey, String status, String message) {
    this.jobKey = jobKey;
    this.status = status;
    this.message = message;
  }

  public String getJobKey() {
    return jobKey;
  }

  public String getStatus() {
    return status;
  }

  public String getMessage() {
    return message;
  }
}
