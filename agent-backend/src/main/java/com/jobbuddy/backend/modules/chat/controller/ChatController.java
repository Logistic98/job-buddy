package com.jobbuddy.backend.modules.chat.controller;

import com.jobbuddy.backend.common.dto.response.SessionIdResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.common.security.AuthenticatedUserContext;
import com.jobbuddy.backend.common.security.PermissionCodes;
import com.jobbuddy.backend.common.security.RequirePermission;
import com.jobbuddy.backend.modules.chat.dto.request.ChatRequest;
import com.jobbuddy.backend.modules.chat.dto.request.ChatStreamRequest;
import com.jobbuddy.backend.modules.chat.dto.response.ChatMessageResponse;
import com.jobbuddy.backend.modules.chat.dto.response.ChatSessionResponse;
import com.jobbuddy.backend.modules.chat.service.AgentFlowService;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import com.jobbuddy.backend.modules.chat.service.ChatSseService;
import com.jobbuddy.backend.modules.chat.vo.ChatResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 对话接口，提供健康检查、普通问答、流式问答和会话管理能力。 */
@Tag(name = "对话接口")
@RestController
@RequirePermission(PermissionCodes.CHAT_USE)
@RequestMapping("/api")
public class ChatController {
  private final AgentFlowService agentFlowService;
  private final ChatSseService chatSseService;
  private final ChatSessionStore chatSessionStore;

  public ChatController(
      AgentFlowService agentFlowService,
      ChatSseService chatSseService,
      ChatSessionStore chatSessionStore) {
    this.agentFlowService = agentFlowService;
    this.chatSseService = chatSseService;
    this.chatSessionStore = chatSessionStore;
  }

  /**
   * 普通对话问答。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "普通对话问答")
  @PostMapping("/chat/ask")
  public ApiResponse<ChatResponse> ask(
      @Valid @RequestBody ChatRequest body, HttpServletRequest request) {
    ensureSessionId(body);
    chatSessionStore.bindOwner(
        body.getSessionId(),
        AuthenticatedUserContext.tenantId(request),
        AuthenticatedUserContext.userId(request));
    return ApiResponse.success(agentFlowService.answer(body));
  }

  /**
   * 流式对话问答。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "流式对话问答")
  @PostMapping(value = "/chat/stream", produces = "text/event-stream")
  public SseEmitter stream(@Valid @RequestBody ChatStreamRequest body, HttpServletRequest request) {
    ensureSessionId(body);
    String tenantId = AuthenticatedUserContext.tenantId(request);
    String userId = AuthenticatedUserContext.userId(request);
    body.setAuthenticatedTenantId(tenantId);
    body.setAuthenticatedUserId(userId);
    chatSessionStore.bindOwner(body.getSessionId(), tenantId, userId);
    return chatSseService.stream(body);
  }

  /**
   * 查询对话会话列表。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "查询对话会话列表")
  @GetMapping("/chat/sessions")
  public ApiResponse<List<ChatSessionResponse>> sessions(HttpServletRequest request) {
    return ApiResponse.success(
        chatSessionStore.listSessions(
            AuthenticatedUserContext.tenantId(request), AuthenticatedUserContext.userId(request)));
  }

  /**
   * 查询会话消息列表。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "查询会话消息列表")
  @GetMapping("/chat/sessions/{sessionId}/messages")
  public ApiResponse<List<ChatMessageResponse>> messages(
      @PathVariable String sessionId, HttpServletRequest request) {
    return ApiResponse.success(
        chatSessionStore.listMessages(
            AuthenticatedUserContext.tenantId(request),
            AuthenticatedUserContext.userId(request),
            sessionId));
  }

  /**
   * 删除对话会话。
   *
   * @return 统一接口响应
   */
  @Operation(summary = "删除对话会话")
  @DeleteMapping("/chat/sessions/{sessionId}")
  public ApiResponse<SessionIdResponse> deleteSession(
      @PathVariable String sessionId, HttpServletRequest request) {
    chatSessionStore.clear(
        AuthenticatedUserContext.tenantId(request),
        AuthenticatedUserContext.userId(request),
        sessionId);
    return ApiResponse.success(new SessionIdResponse(sessionId));
  }

  private void ensureSessionId(ChatRequest request) {
    if (request.getSessionId() == null || request.getSessionId().trim().isEmpty())
      request.setSessionId(newSessionId());
  }

  private void ensureSessionId(ChatStreamRequest request) {
    if (request.getSessionId() == null || request.getSessionId().trim().isEmpty())
      request.setSessionId(newSessionId());
  }

  private String newSessionId() {
    return "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
  }
}
