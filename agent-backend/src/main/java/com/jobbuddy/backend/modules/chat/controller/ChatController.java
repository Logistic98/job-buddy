package com.jobbuddy.backend.modules.chat.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import com.jobbuddy.backend.common.dto.response.HealthResponse;
import com.jobbuddy.backend.common.dto.response.NamedValueResponse;
import com.jobbuddy.backend.common.result.ApiResponse;
import com.jobbuddy.backend.modules.chat.dto.response.ChatMessageResponse;
import com.jobbuddy.backend.modules.chat.dto.request.ChatRequest;
import com.jobbuddy.backend.modules.chat.dto.response.ChatSessionResponse;
import com.jobbuddy.backend.modules.chat.dto.request.ChatStreamRequest;
import com.jobbuddy.backend.modules.chat.service.AgentFlowService;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import com.jobbuddy.backend.modules.chat.service.ChatSseService;
import com.jobbuddy.backend.modules.chat.vo.ChatResponse;
import javax.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 对话接口，提供健康检查、普通问答、流式问答和会话管理能力。
 */
@Tag(name = "对话接口")
@RestController
@RequestMapping("/api")
public class ChatController {
    private final AgentFlowService agentFlowService;
    private final ChatSseService chatSseService;
    private final ChatSessionStore chatSessionStore;

    public ChatController(AgentFlowService agentFlowService, ChatSseService chatSseService, ChatSessionStore chatSessionStore) {
        this.agentFlowService = agentFlowService;
        this.chatSseService = chatSseService;
        this.chatSessionStore = chatSessionStore;
    }

    /**
     * 服务健康检查。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "服务健康检查")
    @GetMapping("/health")
    public ApiResponse<HealthResponse> health() {
        return ApiResponse.success(new HealthResponse("UP", "agent-backend"));
    }

    /**
     * 普通对话问答。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "普通对话问答")
    @PostMapping("/chat/ask")
    public ApiResponse<ChatResponse> ask(@Valid @RequestBody ChatRequest request) {
        return ApiResponse.success(agentFlowService.answer(request));
    }

    /**
     * 流式对话问答。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "流式对话问答")
    @PostMapping(value = "/chat/stream", produces = "text/event-stream")
    public SseEmitter stream(@RequestBody ChatStreamRequest request) {
        return chatSseService.stream(request);
    }

    /**
     * 查询对话会话列表。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "查询对话会话列表")
    @GetMapping("/chat/sessions")
    public ApiResponse<List<ChatSessionResponse>> sessions() {
        return ApiResponse.success(chatSessionStore.listSessions().stream().map(ChatSessionResponse::from).collect(Collectors.toList()));
    }

    /**
     * 查询会话消息列表。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "查询会话消息列表")
    @GetMapping("/chat/sessions/{sessionId}/messages")
    public ApiResponse<List<ChatMessageResponse>> messages(@PathVariable String sessionId) {
        return ApiResponse.success(chatSessionStore.listMessages(sessionId).stream().map(ChatMessageResponse::from).collect(Collectors.toList()));
    }

    /**
     * 删除对话会话。
     *
     * @return 统一接口响应
     */
    @Operation(summary = "删除对话会话")
    @DeleteMapping("/chat/sessions/{sessionId}")
    public ApiResponse<NamedValueResponse> deleteSession(@PathVariable String sessionId) {
        chatSessionStore.clear(sessionId);
        return ApiResponse.success(new NamedValueResponse("sessionId", sessionId));
    }
}
