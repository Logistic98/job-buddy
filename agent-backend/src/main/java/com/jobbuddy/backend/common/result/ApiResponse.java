package com.jobbuddy.backend.common.result;

import io.swagger.v3.oas.annotations.media.Schema;

/**
 * 后端 JSON 接口的统一响应结构。
 */
@Schema(description = "统一接口响应")
public class ApiResponse<T> {
  @Schema(description = "业务状态码；成功为 200，错误使用 HTTP 状态码或 4001、5001 等扩展码", example = "200")
  private int code;

  @Schema(description = "结果说明或可定位的错误信息", example = "success")
  private String message;

  @Schema(description = "接口返回数据；失败时通常为空")
  private T data;

  /**
   * 创建 API 响应实例。
   */
  public ApiResponse() {}

  /**
   * 创建 API 响应实例。
   *
   * @param code 编码
   * @param message 消息内容
   * @param data 数据
   */
  public ApiResponse(int code, String message, T data) {
    this.code = code;
    this.message = message;
    this.data = data;
  }

  /**
   * 创建成功响应。
   *
   * @param data 数据
   * @return 成功响应
   */
  public static <T> ApiResponse<T> success(T data) {
    return new ApiResponse<T>(ErrorCode.SUCCESS.getCode(), ErrorCode.SUCCESS.getMessage(), data);
  }

  /**
   * 创建错误响应。
   *
   * @param code 编码
   * @param message 消息内容
   * @return 错误响应
   */
  public static <T> ApiResponse<T> error(int code, String message) {
    return new ApiResponse<T>(code, message, null);
  }

  /**
   * 获取编码。
   *
   * @return 编码
   */
  public int getCode() {
    return code;
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
   * 获取数据。
   *
   * @return 数据
   */
  public T getData() {
    return data;
  }
}
