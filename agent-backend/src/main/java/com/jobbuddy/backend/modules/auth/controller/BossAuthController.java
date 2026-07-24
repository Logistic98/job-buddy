package com.jobbuddy.backend.modules.auth.controller;

import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginCancelResponse;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginQrResponse;
import com.jobbuddy.backend.modules.auth.dto.response.BossLoginStatusResponse;
import com.jobbuddy.backend.modules.auth.service.BossAuthService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Boss 直聘认证接口，提供二维码登录、登录状态查询和取消登录能力。 */
@Tag(name = "Boss 直聘认证接口")
@RestController
@RequestMapping("/api/boss")
public class BossAuthController {
  private final BossAuthService bossAuthService;

  public BossAuthController(BossAuthService bossAuthService) {
    this.bossAuthService = bossAuthService;
  }

  /**
   * 获取 Boss 登录二维码。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "获取 Boss 登录二维码")
  @GetMapping("/login-qr")
  public ApiResponse<BossLoginQrResponse> loginQr(
      @RequestParam(value = "sessionId", required = false) String sessionId) {
    return ApiResponse.success(bossAuthService.startQrLogin(sessionId));
  }

  /**
   * 查询 Boss 登录状态。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "查询 Boss 登录状态")
  @GetMapping("/login-status")
  public ApiResponse<BossLoginStatusResponse> loginStatus(
      @RequestParam(value = "sessionId", required = false) String sessionId,
      @RequestParam(value = "qrSessionId", required = false) String qrSessionId) {
    return ApiResponse.success(bossAuthService.loginStatus(sessionId, qrSessionId));
  }

  /**
   * 取消 Boss 登录。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "取消 Boss 登录")
  @PostMapping("/login-cancel")
  public ApiResponse<BossLoginCancelResponse> loginCancel(
      @RequestParam(value = "sessionId", required = false) String sessionId,
      @RequestParam(value = "qrSessionId", required = false) String qrSessionId) {
    return ApiResponse.success(bossAuthService.cancelLogin(sessionId, qrSessionId));
  }
}
