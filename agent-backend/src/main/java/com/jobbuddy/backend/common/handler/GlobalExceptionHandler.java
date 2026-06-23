package com.jobbuddy.backend.common.handler;

import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.result.ErrorCode;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import javax.servlet.http.HttpServletRequest;
import java.util.Map;

@RestControllerAdvice
public class GlobalExceptionHandler {
    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    /** Boss 直聘登录态缺失：返回 4001 并携带登录引导数据，前端据此弹出扫码登录。 */
    @ExceptionHandler(BossAuthRequiredException.class)
    @ResponseStatus(HttpStatus.OK)
    public ApiResponse<Map<String, Object>> handleBossAuthRequired(BossAuthRequiredException exception) {
        return new ApiResponse<Map<String, Object>>(ErrorCode.BOSS_AUTH_REQUIRED.getCode(), exception.getMessage(), exception.getAuthData());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleValidation(MethodArgumentNotValidException exception) {
        String message = exception.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(error -> error.getField() + ": " + error.getDefaultMessage())
                .orElse(ErrorCode.BAD_REQUEST.getMessage());
        return ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(), message);
    }

    /** 非法入参属于客户端错误：统一返回 400 与真实校验文案，避免落到兜底分支被当成 500。 */
    @ExceptionHandler(IllegalArgumentException.class)
    @ResponseStatus(HttpStatus.BAD_REQUEST)
    public ApiResponse<Void> handleIllegalArgument(IllegalArgumentException exception) {
        String message = exception.getMessage();
        return ApiResponse.error(ErrorCode.BAD_REQUEST.getCode(),
                message == null || message.trim().isEmpty() ? ErrorCode.BAD_REQUEST.getMessage() : message);
    }

    /** 兜底异常：原始堆栈/SQL 错误只落服务端日志，对外仅返回稳定的友好文案，避免把数据库连接、栈信息等内部细节泄露给前端。 */
    @ExceptionHandler(Exception.class)
    @ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
    public ApiResponse<Void> handleException(Exception exception, HttpServletRequest request) {
        LOG.error("请求处理失败：method={}, uri={}", request.getMethod(), request.getRequestURI(), exception);
        return ApiResponse.error(ErrorCode.INTERNAL_ERROR.getCode(), ErrorCode.INTERNAL_ERROR.getMessage());
    }
}
