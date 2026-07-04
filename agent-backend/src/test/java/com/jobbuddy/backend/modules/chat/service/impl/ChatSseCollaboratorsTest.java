package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import com.jobbuddy.backend.modules.prompt.service.PersonalContextBuilder;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * SSE 主链路协作类单元测试：覆盖事件下发的取消检查与"先推前端、再异步落库"顺序、
 * 持久化协调器的顺序落库与降级回退、Runtime 托管请求组装以及记忆分层写入。
 */
class ChatSseCollaboratorsTest {

    // ---- ChatSseEventSender ----

    @Test
    void sendShouldAbortWhenConnectionCancelled() {
        ConcurrentMap<SseEmitter, AtomicBoolean> cancelled = new ConcurrentHashMap<SseEmitter, AtomicBoolean>();
        SseEmitter emitter = new SseEmitter(0L);
        cancelled.put(emitter, new AtomicBoolean(true));
        ChatSseEventSender sender = new ChatSseEventSender(cancelled, mock(ChatPersistenceCoordinator.class));
        assertThrows(IOException.class, () -> sender.send(emitter, "message", "data"));
    }

    @Test
    void sendAssistantShouldSnapshotToolEventsAndPersistAsync() throws Exception {
        ChatPersistenceCoordinator persistence = mock(ChatPersistenceCoordinator.class);
        ChatSseEventSender sender = new ChatSseEventSender(
                new ConcurrentHashMap<SseEmitter, AtomicBoolean>(), persistence);
        ChatSessionState state = new ChatSessionState();
        state.sessionId = "s1";
        Map<String, Object> event = new LinkedHashMap<String, Object>();
        event.put("id", "job_search");
        event.put("status", "success");
        state.toolEvents.add(event);

        sender.sendAssistant(new SseEmitter(0L), "s1", state, "答案");

        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass((Class) Map.class);
        verify(persistence).appendMessageAsync(eq("s1"), eq("assistant"), eq("答案"), metaCaptor.capture());
        List<?> persistedEvents = (List<?>) metaCaptor.getValue().get("toolEvents");
        assertEquals(1, persistedEvents.size());
        verify(persistence).saveStateAsync(state);
    }

    @Test
    void sendToolStatusShouldAccumulateEventWithoutPersisting() throws Exception {
        ChatPersistenceCoordinator persistence = mock(ChatPersistenceCoordinator.class);
        ChatSseEventSender sender = new ChatSseEventSender(
                new ConcurrentHashMap<SseEmitter, AtomicBoolean>(), persistence);
        ChatSessionState state = new ChatSessionState();
        Map<String, Object> status = new LinkedHashMap<String, Object>();
        status.put("id", "job_search");
        status.put("status", "running");

        sender.sendToolStatus(new SseEmitter(0L), "s1", state, status);

        assertEquals(1, state.toolEvents.size());
        verify(persistence, never()).saveStateAsync(any(ChatSessionState.class));
    }

    // ---- ChatPersistenceCoordinator ----

    private ChatPersistenceCoordinator newCoordinator(ChatSessionStore store) {
        return new ChatPersistenceCoordinator(store, runnable -> {
            Thread thread = new Thread(runnable, "test-persist");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Test
    void appendMessageAsyncShouldFlushInOrder() {
        ChatSessionStore store = mock(ChatSessionStore.class);
        ChatPersistenceCoordinator coordinator = newCoordinator(store);
        coordinator.appendMessageAsync("s1", "user", "你好", null);
        Map<String, Object> metadata = Collections.<String, Object>singletonMap("k", "v");
        coordinator.appendMessageAsync("s1", "assistant", "答案", metadata);
        coordinator.awaitPersistFlush();
        verify(store).appendMessage("s1", "user", "你好");
        verify(store).appendMessage("s1", "assistant", "答案", metadata);
        coordinator.shutdown();
    }

    @Test
    void replaceLatestJobMessageShouldFallBackToAppendWhenMissing() {
        ChatSessionStore store = mock(ChatSessionStore.class);
        when(store.replaceLatestAssistantJobMessage(anyString(), anyList(), anyList())).thenReturn(false);
        ChatPersistenceCoordinator coordinator = newCoordinator(store);
        List<Map<String, Object>> jobs = Arrays.<Map<String, Object>>asList(
                Collections.<String, Object>singletonMap("jobName", "后端工程师"));
        coordinator.replaceLatestJobMessageAsync("s1", jobs, null);
        coordinator.awaitPersistFlush();
        ArgumentCaptor<Map<String, Object>> metaCaptor = ArgumentCaptor.forClass((Class) Map.class);
        verify(store).appendMessage(eq("s1"), eq("assistant"), eq(""), metaCaptor.capture());
        assertEquals(jobs, metaCaptor.getValue().get("jobCards"));
        coordinator.shutdown();
    }

    @Test
    void replaceLatestJobMessageShouldNotAppendWhenReplaced() {
        ChatSessionStore store = mock(ChatSessionStore.class);
        when(store.replaceLatestAssistantJobMessage(anyString(), anyList(), anyList())).thenReturn(true);
        ChatPersistenceCoordinator coordinator = newCoordinator(store);
        coordinator.replaceLatestJobMessageAsync("s1",
                Collections.<Map<String, Object>>emptyList(), Collections.<Map<String, Object>>emptyList());
        coordinator.awaitPersistFlush();
        verify(store, never()).appendMessage(anyString(), anyString(), anyString(), anyMap());
        coordinator.shutdown();
    }

    @Test
    void persistFailureShouldNotBreakSubsequentTasks() {
        ChatSessionStore store = mock(ChatSessionStore.class);
        doThrow(new RuntimeException("db down")).when(store).save(any(ChatSessionState.class));
        ChatPersistenceCoordinator coordinator = newCoordinator(store);
        ChatSessionState state = new ChatSessionState();
        state.sessionId = "s1";
        coordinator.saveStateAsync(state);
        coordinator.appendMessageAsync("s1", "user", "你好", null);
        coordinator.awaitPersistFlush();
        verify(store).appendMessage("s1", "user", "你好");
        coordinator.shutdown();
    }

    // ---- RuntimeManagedRequestFactory ----

    @Test
    void buildRuntimeManagedRequestShouldCarryBudgetAndMetadata() {
        JobBuddyProperties properties = new JobBuddyProperties();
        RuntimeManagedRequestFactory factory = new RuntimeManagedRequestFactory(
                mock(AgentIntegrationService.class), mock(PersonalContextBuilder.class), properties);
        Map<String, Object> extra = Collections.<String, Object>singletonMap("entrypoint", "chat.ask");

        Map<String, Object> request = factory.buildRuntimeManagedRequest("s1", "帮我找岗位", "job_buddy", extra, true);

        assertEquals("s1", request.get("session_id"));
        assertEquals(Boolean.TRUE, request.get("stream"));
        List<?> messages = (List<?>) request.get("messages");
        assertEquals(1, messages.size());
        assertEquals("帮我找岗位", ((Map<?, ?>) messages.get(0)).get("content"));
        Map<?, ?> budget = (Map<?, ?>) request.get("budget");
        assertEquals(properties.getRuntimeMaxTurns(), budget.get("max_turns"));
        assertEquals(properties.getRuntimeMaxToolCalls(), budget.get("max_tool_calls"));
        assertEquals(properties.getRuntimeMaxFailures(), budget.get("max_failures"));
        Map<?, ?> metadata = (Map<?, ?>) request.get("metadata");
        assertEquals("job_buddy", metadata.get("profile"));
        assertEquals("chat.ask", metadata.get("entrypoint"));
    }

    @Test
    void buildPersonalContextShouldDegradeToEmptyOnFailure() {
        PersonalContextBuilder builder = mock(PersonalContextBuilder.class);
        when(builder.build(any(), anyString(), any(), any())).thenThrow(new RuntimeException("profile missing"));
        RuntimeManagedRequestFactory factory = new RuntimeManagedRequestFactory(
                mock(AgentIntegrationService.class), builder, new JobBuddyProperties());
        Map<String, Object> context = factory.buildPersonalContext("消息", null, new ChatSessionState());
        assertTrue(context.isEmpty());
    }

    @Test
    void runtimeManagedMetadataShouldToleranteNullState() {
        RuntimeManagedRequestFactory factory = new RuntimeManagedRequestFactory(
                mock(AgentIntegrationService.class), mock(PersonalContextBuilder.class), new JobBuddyProperties());
        Map<String, Object> metadata = factory.runtimeManagedMetadata("消息", null, null, null);
        assertEquals(Boolean.TRUE, metadata.get("runtime_execute"));
        assertEquals("chat.ask", metadata.get("entrypoint"));
        assertEquals(0, metadata.get("current_jobs_count"));
        assertEquals(Collections.emptyMap(), metadata.get("previous_slots"));
        assertEquals(Collections.emptyMap(), metadata.get("upstream_directive"));
    }

    // ---- ChatMemoryWriter ----

    private static final Executor DIRECT = new Executor() {
        @Override
        public void execute(Runnable command) {
            command.run();
        }
    };

    @Test
    void memoryWriterShouldPersistOnlyLongTermSignals() {
        SystemSettingsService settings = mock(SystemSettingsService.class);
        ChatMemoryWriter writer = new ChatMemoryWriter(settings, DIRECT);
        writer.captureLongTermMemoryAsync("排除外包岗位");
        writer.captureLongTermMemoryAsync("帮我看下这个岗位");
        writer.captureLongTermMemoryAsync("  ");
        writer.captureLongTermMemoryAsync(null);
        verify(settings).writeLocalMemory("constraint", "排除外包岗位", "chat");
        verify(settings, never()).writeLocalMemory(eq("preference"), anyString(), anyString());
    }

    @Test
    void memoryWriteFailureShouldNotPropagate() {
        SystemSettingsService settings = mock(SystemSettingsService.class);
        doThrow(new RuntimeException("disk full")).when(settings).writeLocalMemory(anyString(), anyString(), anyString());
        ChatMemoryWriter writer = new ChatMemoryWriter(settings, DIRECT);
        writer.captureLongTermMemoryAsync("我希望做后端");
        verify(settings).writeLocalMemory("preference", "我希望做后端", "chat");
    }
}
