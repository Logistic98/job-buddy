package com.jobbuddy.backend.modules.prompt.controller;

import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.common.security.PermissionCodes;
import com.jobbuddy.backend.common.security.RequirePermission;
import com.jobbuddy.backend.modules.prompt.dto.response.FrontendPromptResponse;
import com.jobbuddy.backend.modules.prompt.dto.response.ProfileContextResponse;
import com.jobbuddy.backend.modules.prompt.model.UserProfileContext;
import com.jobbuddy.backend.modules.prompt.service.ProfileContextService;
import com.jobbuddy.backend.modules.prompt.service.PromptRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** 提示词接口，提供前端提示词配置和用户画像上下文查询能力。 */
@Tag(name = "提示词接口")
@RestController
@RequirePermission(PermissionCodes.CHAT_USE)
@RequestMapping("/api/prompts")
public class PromptController {
  private final PromptRegistryService promptRegistryService;
  private final ProfileContextService profileContextService;

  public PromptController(
      PromptRegistryService promptRegistryService, ProfileContextService profileContextService) {
    this.promptRegistryService = promptRegistryService;
    this.profileContextService = profileContextService;
  }

  /**
   * 获取前端提示词配置。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "获取前端提示词配置")
  @GetMapping("/frontend")
  public ApiResponse<FrontendPromptResponse> frontend(
      @RequestParam(value = "profile", required = false) String profile) {
    FrontendPromptResponse data = new FrontendPromptResponse();
    String activeProfile =
        profile == null || profile.trim().isEmpty()
            ? promptRegistryService.activeProfile()
            : profile.trim();
    data.setActiveProfile(activeProfile);
    data.setWorkbench(promptRegistryService.frontendWorkbench(activeProfile));
    data.setProfile(promptRegistryService.profileConfig(activeProfile));
    return ApiResponse.success(data);
  }

  /**
   * 获取用户画像上下文。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "获取用户画像上下文")
  @GetMapping("/profile-context")
  public ApiResponse<ProfileContextResponse> profileContext(
      HttpServletRequest request,
      @RequestParam(value = "resumeId", required = false) String resumeId) {
    String userId = AuthenticatedUserContext.userId(request);
    UserProfileContext context = profileContextService.current(userId, resumeId);
    ProfileContextResponse response = new ProfileContextResponse();
    response.setSummary(context.getSummary());
    response.setProfile(context.getProfile());
    return ApiResponse.success(response);
  }
}
