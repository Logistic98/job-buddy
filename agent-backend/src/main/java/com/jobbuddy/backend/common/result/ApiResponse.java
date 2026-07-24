package com.jobbuddy.backend.common.result;

import io.swagger.v3.oas.annotations.media.Schema;

/** 后端 JSON 接口的统一响应结构。 */
@Schema(description = "统一接口响应")
public class ApiResponse<T> {
  @Schema(description = "业务状态码；成功为 200，错误使用 HTTP 状态码或 4001、5001 等扩展码", example = "200")
  private int code;

  @Schema(description = "结果说明或可定位的错误信息", example = "success")
  private String message;

  @Schema(description = "接口返回数据；失败时通常为空")
  private T data;

  public ApiResponse() {}

  public ApiResponse(int code, String message, T data) {
    this.code = code;
    this.message = message;
    this.data = data;
  }

  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<T>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
  }

  public static <T> ApiResponse<T> error(int code, String message) {
    return new ApiResponse<T>(code, message, null);
  }

  public int getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }

  public T getData() {
    return data;
  }
}
