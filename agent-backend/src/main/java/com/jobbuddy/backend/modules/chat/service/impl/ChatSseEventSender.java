package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.accumulateToolEvent;

/**
 * SSE 事件下发器：统一负责事件写出、取消检查、助手消息终态下发与增量下发，
 * 保证"先推前端、再异步落库"的顺序在所有链路一致。
 */
class ChatSseEventSender {
    private static final Logger log = LoggerFactory.getLogger(ChatSseEventSender.class);
    private final ConcurrentMap<SseEmitter, AtomicBoolean> emitterCancelled;
    private final ChatPersistenceCoordinator persistence;

    ChatSseEventSender(ConcurrentMap<SseEmitter, AtomicBoolean> emitterCancelled, ChatPersistenceCoordinator persistence) {
        this.emitterCancelled = emitterCancelled;
        this.persistence = persistence;
    }

    void send(SseEmitter emitter, String event, Object data) throws IOException {
        AtomicBoolean cancelled = emitterCancelled.get(emitter);
        if (cancelled != null && cancelled.get()) {
            // 连接已超时/断开/完成：立即终止后台任务的后续下发与计算，而不是继续空转到 180s。
            throw new IOException("SSE 连接已关闭（超时或客户端断开），停止下发事件: " + event);
        }
        emitter.send(SseEmitter.event().name(event).data(data));
    }

    void completeQuietly(SseEmitter emitter) {
        try {
            emitter.complete();
        } catch (Exception e) {
            // 连接已超时或已被容器关闭时 complete 会抛异常，属预期，不影响任务收尾。
            log.debug("SSE complete 失败（连接可能已关闭）: {}", e.getMessage());
        }
    }

    void sendAssistant(SseEmitter emitter, String sessionId, ChatSessionState state, String value) throws IOException {
        sendAssistant(emitter, sessionId, state, value, null);
    }

    void sendAssistant(SseEmitter emitter, String sessionId, ChatSessionState state, String value, Map<String, Object> metadata) throws IOException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("role", "assistant");
        data.put("content", value);
        data.put("createdAt", java.time.Instant.now().toString());
        if (metadata != null && !metadata.isEmpty()) data.putAll(metadata);
        // 把本轮推理过程随助手消息一并落库，刷新或切换会话后仍可查看该轮的思考与工具执行过程。
        // 推理步骤已在本轮累积到内存会话状态，这里直接取用，无需再读库。
        Map<String, Object> persistMeta = metadata == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(metadata);
        List<Map<String, Object>> toolEventsSnapshot = null;
        if (state != null && state.toolEvents != null && !state.toolEvents.isEmpty()) {
            toolEventsSnapshot = new java.util.ArrayList<Map<String, Object>>(state.toolEvents);
            if (!persistMeta.containsKey("toolEvents")) persistMeta.put("toolEvents", toolEventsSnapshot);
        }
        // 终态 message 同时携带本轮推理过程，前端据此把推理步骤绑定到最终助手消息，
        // 避免后续从服务端重载时因落库尚未完成而把内存里的推理过程覆盖丢失。
        if (toolEventsSnapshot != null && !data.containsKey("toolEvents")) {
            data.put("toolEvents", toolEventsSnapshot);
        }
        // 先把答案推到前端，再异步落库（助手消息 + 会话状态含推理过程），避免持久化 IO 阻塞用户感知。
        send(emitter, "message", data);
        persistence.appendMessageAsync(sessionId, "assistant", value, persistMeta.isEmpty() ? null : persistMeta);
        persistence.saveStateAsync(state);
    }

    /** 下发答案 Token 增量，前端按 assistantId 追加到在途助手消息，不落库（终态 message 落库）。 */
    void sendMessageDelta(SseEmitter emitter, String sessionId, String assistantId, String delta) throws IOException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("sessionId", sessionId);
        data.put("assistantId", assistantId);
        data.put("delta", delta);
        send(emitter, "message_delta", data);
    }

    /** 下发推理过程增量，前端按 assistantId 追加到在途助手消息的推理过程，不落库（终态 message 携带完整推理过程落库）。 */
    void sendReasoningDelta(SseEmitter emitter, String sessionId, String assistantId, String delta) throws IOException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("sessionId", sessionId);
        data.put("assistantId", assistantId);
        data.put("delta", delta);
        send(emitter, "reasoning_delta", data);
    }

    void sendToolStatus(SseEmitter emitter, String sessionId, ChatSessionState state, Map<String, Object> status) throws IOException {
        // 先把工具状态推给前端，再累积到内存会话状态（本轮结束统一落库），不在主线程做 DB 写。
        send(emitter, "tool_status", status);
        accumulateToolEvent(state, status);
    }
}
