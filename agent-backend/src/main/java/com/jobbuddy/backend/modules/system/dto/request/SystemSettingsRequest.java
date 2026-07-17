package com.jobbuddy.backend.modules.system.dto.request;

import com.jobbuddy.backend.modules.system.dto.response.SystemSettingsResponse;

/**
 * 系统设置更新请求。
 *
 * <p>与设置查询响应共享字段结构，独立请求类型避免 Controller 直接暴露响应模型语义。
 */
public class SystemSettingsRequest extends SystemSettingsResponse {
}
