package com.jobbuddy.backend.modules.job.exception;

/**
 * 岗位分析下游执行失败。对外只暴露稳定、可操作的业务文案，原始异常保留在 cause 中供日志排查。
 */
public class JobAnalysisException extends RuntimeException {
  /**
   * 创建岗位分析异常实例。
   *
   * @param message 消息内容
   */
  public JobAnalysisException(String message) {
    super(message);
  }

  /**
   * 创建岗位分析异常实例。
   *
   * @param message 消息内容
   * @param cause 异常原因
   */
  public JobAnalysisException(String message, Throwable cause) {
    super(message, cause);
  }
}
