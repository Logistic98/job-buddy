package com.jobbuddy.backend.modules.auth.controller;

import com.jobbuddy.backend.common.dto.response.BooleanResultResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthSessionCookie;
import com.jobbuddy.backend.common.security.AuthenticatedUser;
import com.jobbuddy.backend.modules.auth.dto.request.LoginRequest;
import com.jobbuddy.backend.modules.auth.dto.response.CurrentUserResponse;
import com.jobbuddy.backend.modules.auth.dto.response.LoginResponse;
import com.jobbuddy.backend.modules.auth.service.UserLoginService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.security.SecurityRequirements;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 用户认证接口，提供登录、当前用户查询和退出登录能力。 */
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
  @Operation(summary = "用户登录", description = "使用全局唯一用户名和密码登录；成功后同时返回令牌并写入会话 Cookie。")
  @SecurityRequirements
  @PostMapping("/login")
  public ApiResponse<LoginResponse> login(
      @RequestBody LoginRequest body, HttpServletRequest request, HttpServletResponse response) {
    String username = body == null || body.getUsername() == null ? "" : body.getUsername();
    String password = body == null || body.getPassword() == null ? "" : body.getPassword();
    LoginResponse login = userLoginService.login(username, password, request.getRemoteAddr());
    AuthSessionCookie.write(response, login.getToken(), request.isSecure());
    return ApiResponse.success(login);
  }

  /**
   * 获取当前登录用户。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "获取当前登录用户")
  @GetMapping("/me")
  public ApiResponse<CurrentUserResponse> me(HttpServletRequest request) {
    AuthenticatedUser user = userLoginService.currentUser(AuthSessionCookie.resolveToken(request));
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
  public ApiResponse<BooleanResultResponse> logout(
      HttpServletRequest request, HttpServletResponse response) {
    userLoginService.logout(AuthSessionCookie.resolveToken(request));
    AuthSessionCookie.clear(response, request.isSecure());
    return ApiResponse.success(new BooleanResultResponse(true));
  }
}
