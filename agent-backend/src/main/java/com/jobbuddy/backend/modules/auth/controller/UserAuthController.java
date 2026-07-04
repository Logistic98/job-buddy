package com.jobbuddy.backend.modules.auth.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.jobbuddy.backend.common.dto.response.BooleanResultResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.response.CurrentUserResponse;
import com.jobbuddy.backend.modules.auth.dto.request.LoginRequest;
import com.jobbuddy.backend.modules.auth.dto.response.LoginResponse;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户认证接口，提供登录、当前用户查询和退出登录能力。
 */
@Tag(name = "用户认证接口")
@RestController
@RequestMapping("/api/auth")
public class UserAuthController {
    private final UserLoginService userLoginService;

    public UserAuthController(UserLoginService userLoginService) {
        this.userLoginService = userLoginService;
    }

    /**
     * 用户登录。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@RequestBody LoginRequest body) {
        try {
            String username = body == null || body.getUsername() == null ? "" : body.getUsername();
            String password = body == null || body.getPassword() == null ? "" : body.getPassword();
            return ApiResponse.success(userLoginService.login(username, password));
        } catch (IllegalArgumentException e) {
            return ApiResponse.error(401, e.getMessage());
        }
    }

    /**
     * 获取当前登录用户。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "获取当前登录用户")
    @GetMapping("/me")
    public ApiResponse<CurrentUserResponse> me(@RequestHeader(value = "Authorization", required = false) String authorization) {
        AuthenticatedUser user = userLoginService.currentUser(extractToken(authorization));
        if (user == null) return ApiResponse.error(401, "未登录或登录已过期");
        return ApiResponse.success(CurrentUserResponse.from(user));
    }

    /**
     * 退出登录。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "退出登录")
    @PostMapping("/logout")
    public ApiResponse<BooleanResultResponse> logout(@RequestHeader(value = "Authorization", required = false) String authorization) {
        userLoginService.logout(extractToken(authorization));
        return ApiResponse.success(new BooleanResultResponse(true));
    }

    private String extractToken(String authorization) {
        if (authorization == null) return "";
        String value = authorization.trim();
        if (value.toLowerCase().startsWith("bearer ")) return value.substring(7).trim();
        return value;
    }
}
