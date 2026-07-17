package com.jobbuddy.backend.modules.system.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.jobbuddy.backend.common.dto.response.StateKeyResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.modules.system.service.UserWorkspaceService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/workspace/state")
public class UserWorkspaceController {
  private final UserWorkspaceService service;

  public UserWorkspaceController(UserWorkspaceService service) {
    this.service = service;
  }

  @GetMapping("/{stateKey}")
  public ApiResponse<JsonNode> get(@PathVariable String stateKey, HttpServletRequest request) {
    return ApiResponse.success(service.get(AuthenticatedUserContext.userId(request), stateKey));
  }

  @PutMapping("/{stateKey}")
  public ApiResponse<JsonNode> save(
      @PathVariable String stateKey,
      @RequestBody(required = false) JsonNode payload,
      HttpServletRequest request) {
    return ApiResponse.success(
        service.save(AuthenticatedUserContext.userId(request), stateKey, payload));
  }

  @DeleteMapping("/{stateKey}")
  public ApiResponse<StateKeyResponse> delete(
      @PathVariable String stateKey, HttpServletRequest request) {
    service.delete(AuthenticatedUserContext.userId(request), stateKey);
    return ApiResponse.success(new StateKeyResponse(stateKey));
  }
}
