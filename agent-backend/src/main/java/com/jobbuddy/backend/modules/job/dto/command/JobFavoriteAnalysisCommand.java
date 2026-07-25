package com.jobbuddy.backend.modules.job.dto.command;

/**
 * 收藏岗位分析命令对象，显式表达岗位标识与可选简历标识。
 */
public class JobFavoriteAnalysisCommand {
  private final String jobKey;
  private final String resumeId;

  /**
   * 创建收藏岗位分析命令实例。
   *
   * @param jobKey 岗位键
   * @param resumeId 简历标识
   */
  private JobFavoriteAnalysisCommand(String jobKey, String resumeId) {
    this.jobKey = trimToNull(jobKey);
    this.resumeId = trimToNull(resumeId);
  }

  /**
   * 根据参数创建对象。
   *
   * @param jobKey 岗位键
   * @param resumeId 简历标识
   * @return 创建后的对象
   */
  public static JobFavoriteAnalysisCommand of(String jobKey, String resumeId) {
    return new JobFavoriteAnalysisCommand(jobKey, resumeId);
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
   * 获取简历标识。
   *
   * @return 简历标识
   */
  public String getResumeId() {
    return resumeId;
  }

  /**
   * 裁剪文本并将空白值转换为空值。
   *
   * @param value 待处理值
   * @return 规范化文本
   */
  private static String trimToNull(String value) {
    if (value == null) return null;
    String trimmed = value.trim();
    return trimmed.isEmpty() ? null : trimmed;
  }
}
