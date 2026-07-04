package com.jobbuddy.backend.common.security;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 已认证用户的类型化视图，替代跨层传递的 Map，供拦截器与业务层读取当前用户信息。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class AuthenticatedUser {
    private String userId;
    private String username;
    private String displayName;
    private String role;
}
