package com.jobbuddy.backend.common.handler;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.result.ErrorCode;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.auth.exception.AuthorizationDeniedException;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.auth.exception.InvalidCredentialsException;
import com.jobbuddy.backend.modules.auth.exception.LoginRateLimitException;
import com.jobbuddy.backend.modules.chat.exception.ChatStreamRejectedException;
import com.jobbuddy.backend.modules.job.exception.JobAnalysisException;
import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.async.AsyncRequestNotUsableException;
import org.springframework.web.context.request.async.AsyncRequestTimeoutException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

@RestControllerAdvice
public class GlobalExceptionHandler {
  private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  private final JsonCodec jsonCodec;

  public GlobalExceptionHandler(JsonCodec jsonCodec) {
    this.jsonCodec = jsonCodec;
  }

  /** Boss 直聘登录态缺失：返回 4001，并将字段不稳定的登录引导数据保留为明确 JSON 边界。 */
  @ExceptionHandler(BossAuthRequiredException.class)
  @ResponseStatus(HttpStatus.OK)
  public ApiResponse<JsonNode> handleBossAuthRequired(BossAuthRequiredException exception) {
    return new ApiResponse<JsonNode>(
        ErrorCode.BOSS_AUTH_REQUIRED.getCode(),
        exception.getMessage(),
        jsonCodec.toTree(exception.getAuthData()));
  }

  @ExceptionHandler(JobAnalysisException.class)
  @ResponseStatus(HttpStatus.BAD_GATEWAY)
  public ApiResponse<Void> handleJobAnalysis(
      JobAnalysisException exception, HttpServletRequest request) {
    LOG.warn(
        "岗位分析失败：method={}, uri={}, reason={}",
        request.getMethod(),
        request.getRequestURI(),
        exception.getMessage());
    return ApiResponse.error(ErrorCode.DEPENDENCY_FAILURE.getCode(), exception.getMessage());
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleValidation(MethodArgumentNotValidException exception) {
    String message =
        exception.getBindingResult().getFieldErrors().stream()
            .findFirst()
            .map(error -> error.getField() + ": " + error.getDefaultMessage())
            .orElse(ErrorCode.BAD_REQUEST.getMessage());
    return ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(), message);
  }

  @ExceptionHandler(InvalidCredentialsException.class)
  @ResponseStatus(HttpStatus.UNAUTHORIZED)
  public ApiResponse<Void> handleInvalidCredentials(InvalidCredentialsException exception) {
    return ApiResponse.error(HttpStatus.UNAUTHORIZED.value(), exception.getMessage());
  }

  @ExceptionHandler(LoginRateLimitException.class)
  @ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
  public ApiResponse<Void> handleLoginRateLimit(
      LoginRateLimitException exception, jakarta.servlet.http.HttpServletResponse response) {
    response.setHeader("Retry-After", String.valueOf(exception.getRetryAfterSeconds()));
    return ApiResponse.error(HttpStatus.TOO_MANY_REQUESTS.value(), exception.getMessage());
  }

  @ExceptionHandler(AuthorizationDeniedException.class)
  @ResponseStatus(HttpStatus.FORBIDDEN)
  public ApiResponse<Void> handleAccessDenied(AuthorizationDeniedException exception) {
    return ApiResponse.error(HttpStatus.FORBIDDEN.value(), exception.getMessage());
  }

  @ExceptionHandler(ChatStreamRejectedException.class)
  @ResponseStatus(HttpStatus.SERVICE_UNAVAILABLE)
  public ApiResponse<Void> handleChatStreamRejected(
      ChatStreamRejectedException exception, jakarta.servlet.http.HttpServletResponse response) {
    if (exception.isRetryable()) response.setHeader("Retry-After", "1");
    return ApiResponse.error(HttpStatus.SERVICE_UNAVAILABLE.value(), exception.getMessage());
  }

  /** 非法入参属于客户端错误：统一返回 400 与真实校验文案，避免落到兜底分支被当成 500。 */
  @ExceptionHandler(IllegalArgumentException.class)
  @ResponseStatus(HttpStatus.BAD_REQUEST)
  public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException exception) {
    String message = exception.getMessage();
    return ApiResponse.error(
        ErrorCode.BAD_REQUEST.getCode(),
        message == null || message.trim().isEmpty() ? ErrorCode.BAD_REQUEST.getMessage() : message);
  }

  /** 静态资源或接口不存在属于普通 404，不应按服务端异常记录 ERROR 堆栈。 */
  @ExceptionHandler(NoResourceFoundException.class)
  @ResponseStatus(HttpStatus.NOT_FOUND)
  public ApiResponse<Void> handleNoResourceFound(
      NoResourceFoundException exception, HttpServletRequest request) {
    LOG.debug("资源不存在：method={}, uri={}", request.getMethod(), request.getRequestURI());
    return ApiResponse.error(ErrorCode.NOT_FOUND.getCode(), ErrorCode.NOT_FOUND.getMessage());
  }

  /**
   * SSE 客户端刷新、切换会话或主动取消后，容器可能上抛 AsyncRequestNotUsableException。 响应已经是 text/event-stream
   * 且通常已经提交，此时不能再返回 ApiResponse，否则会触发 HttpMessageNotWritableException 并形成第二段级联堆栈。
   */
  @ExceptionHandler(AsyncRequestNotUsableException.class)
  public void handleAsyncClientDisconnect(
      AsyncRequestNotUsableException exception, HttpServletRequest request) {
    LOG.debug(
        "客户端已断开异步请求：method={}, uri={}, reason={}",
        request.getMethod(),
        request.getRequestURI(),
        exception.getMessage());
  }

  /**
   * SSE 总生命周期超时后响应通常已经提交，不能再由兜底异常处理器写入 JSON。保持无响应体， 避免在 text/event-stream 上触发
   * HttpMessageNotWritableException。
   */
  @ExceptionHandler(AsyncRequestTimeoutException.class)
  public void handleAsyncRequestTimeout(
      AsyncRequestTimeoutException exception, HttpServletRequest request) {
    LOG.debug(
        "异步请求已超时：method={}, uri={}, reason={}",
        request.getMethod(),
        request.getRequestURI(),
        exception.getMessage());
  }

  /** 兜底异常：原始堆栈/SQL 错误只落服务端日志，对外仅返回稳定的友好文案，避免把数据库连接、栈信息等内部细节泄露给前端。 */
  @ExceptionHandler(Exception.class)
  @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
  public ApiResponse<Void> handleException(Exception exception, HttpServletRequest request) {
    LOG.error("请求处理失败：method={}, uri={}", request.getMethod(), request.getRequestURI(), exception);
    return ApiResponse.error(
        ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage());
  }
}
