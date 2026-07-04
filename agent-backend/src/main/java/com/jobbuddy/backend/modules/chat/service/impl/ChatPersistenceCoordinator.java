package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * 会话持久化协调器：把 Postgres/Redis 读写从 SSE 主线程剥离，统一交给单线程顺序执行，
 * 既保证用户消息/助手消息/工具事件的落库顺序，又避免每次 tool_status 的 DB 写阻塞首包与答案流式。
 */
class ChatPersistenceCoordinator {
    private static final Logger log = LoggerFactory.getLogger(ChatPersistenceCoordinator.class);
    private final ChatSessionStore sessionStore;
    private final ExecutorService persistExecutor;

    ChatPersistenceCoordinator(ChatSessionStore sessionStore, ThreadFactory threadFactory) {
        this.sessionStore = sessionStore;
        this.persistExecutor = Executors.newSingleThreadExecutor(threadFactory);
    }

    /** 持久化队列允许已提交任务执行完毕，避免关停时丢失尚未落库的会话消息。 */
    void shutdown() {
        persistExecutor.shutdown();
    }

    /** 顺序异步落库助手消息，保证与用户消息的先后顺序，且不阻塞 SSE 主线程。 */
    void appendMessageAsync(final String sessionId, final String role, final String content, final Map<String, Object> metadata) {
        persistExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    if (metadata == null || metadata.isEmpty()) sessionStore.appendMessage(sessionId, role, content);
                    else sessionStore.appendMessage(sessionId, role, content, metadata);
                } catch (Exception e) {
                    // 异步落库失败不影响已推送给前端的流式内容，但需留痕以便定位消息丢失。
                    log.warn("异步落库消息失败 sessionId={} role={}", sessionId, role, e);
                }
            }
        });
    }

    /** 顺序异步替换最近一条岗位助手消息；若历史中尚无岗位消息则回退为追加，保证新会话首屏仍可持久化。 */
    void replaceLatestJobMessageAsync(final String sessionId, final List<Map<String, Object>> jobs, final List<Map<String, Object>> toolEvents) {
        final List<Map<String, Object>> jobsSnapshot = jobs == null ? Collections.<Map<String, Object>>emptyList() : new java.util.ArrayList<Map<String, Object>>(jobs);
        final List<Map<String, Object>> toolEventsSnapshot = toolEvents == null ? Collections.<Map<String, Object>>emptyList() : new java.util.ArrayList<Map<String, Object>>(toolEvents);
        persistExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean replaced = sessionStore.replaceLatestAssistantJobMessage(sessionId, jobsSnapshot, toolEventsSnapshot);
                    if (!replaced) {
                        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
                        metadata.put("jobCards", jobsSnapshot);
                        if (!toolEventsSnapshot.isEmpty()) {
                            metadata.put("toolEvents", toolEventsSnapshot);
                        }
                        sessionStore.appendMessage(sessionId, "assistant", "", metadata);
                    }
                } catch (Exception e) {
                    log.warn("异步替换岗位消息失败 sessionId={}", sessionId, e);
                }
            }
        });
    }

    /**
     * 等待持久化队列排空：persistExecutor 为单线程顺序执行，提交一个空屏障任务并等待其完成，
     * 即代表此前排队的用户消息/助手消息/会话状态落库均已结束。用于在 done 之前保证服务端一致。
     */
    void awaitPersistFlush() {
        try {
            persistExecutor.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // 落库屏障等待超时/中断不阻断 done 下发，仅留痕：可能存在尚未刷盘的会话消息。
            log.warn("等待持久化队列排空异常", e);
        }
    }

    /** 顺序异步保存会话状态（槽位/岗位/工具事件等），从 SSE 主线程剥离。 */
    void saveStateAsync(final ChatSessionState state) {
        if (state == null) return;
        persistExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    sessionStore.save(state);
                } catch (Exception e) {
                    // 会话状态异步保存失败不阻断当前流，但需留痕以便定位状态回看缺失。
                    log.warn("异步保存会话状态失败 sessionId={}", state.sessionId, e);
                }
            }
        });
    }
}
