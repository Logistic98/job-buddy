package com.jobbuddy.backend.modules.job.exception;

/** 岗位分析下游执行失败。对外只暴露稳定、可操作的业务文案，原始异常保留在 cause 中供日志排查。 */
public class JobAnalysisException extends RuntimeException {
  public JobAnalysisException(String message) {
    super(message);
  }

  public JobAnalysisException(String message, Throwable cause) {
    super(message, cause);
  }
}
