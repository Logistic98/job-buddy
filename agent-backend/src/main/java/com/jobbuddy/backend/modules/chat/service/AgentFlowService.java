package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.dto.request.ChatRequest;
import com.jobbuddy.backend.modules.chat.vo.ChatResponse;

public interface AgentFlowService {
    ChatResponse answer(ChatRequest request);
}
