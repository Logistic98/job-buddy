package com.jobbuddy.backend.common.result;

/**
 * 统一错误码枚举。
 *
 * 集中维护对外返回的业务码与默认文案，避免在 Controller、异常处理器、下游客户端里散落
 * 4001、5001 等魔法数字。新增错误语义时在此登记，调用方统一引用枚举值，保持前后端约定一致。
 */
public enum ErrorCode {
    /** 成功。 */
    SUCCESS(200, "success"),
    /** 请求参数校验失败。 */
    BAD_REQUEST(400, "invalid request"),
    /** Boss 直聘登录态缺失，需引导用户扫码登录。 */
    BOSS_AUTH_REQUIRED(4001, "Boss 登录态缺失"),
    /** 服务端未捕获异常的兜底码。 */
    INTERNAL_ERROR(500, "服务暂时不可用，请稍后重试。"),
    /** 下游依赖（Runtime、工具、沙箱等）调用失败。 */
    DEPENDENCY_FAILURE(5001, "下游依赖调用失败");

    private final int code;
    private final String message;

    ErrorCode(int code, String message) {
        this.code = code;
        this.message = message;
    }

    public int getCode() {
        return code;
    }

    public String getMessage() {
        return message;
    }
}
