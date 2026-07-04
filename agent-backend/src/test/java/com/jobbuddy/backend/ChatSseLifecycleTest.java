package com.jobbuddy.backend;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.chat.dto.request.ChatStreamRequest;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import com.jobbuddy.backend.modules.chat.service.IntentService;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.chat.service.impl.ChatSseServiceImpl;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.prompt.service.PersonalContextBuilder;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * SSE 生命周期行为测试：验证连接关闭（超时/客户端断开）后后台任务及时终止并清理取消标记，
 * 以及异常路径结束后不会在取消标记表中残留条目（防内存泄漏）。
 */
class ChatSseLifecycleTest {

    private ChatSseServiceImpl newService(IntentService intentService, AgentIntegrationService integrationService) {
        ChatSessionStore sessionStore = mock(ChatSessionStore.class);
        when(sessionStore.getOrCreate(anyString())).thenAnswer(inv -> {
            ChatSessionState state = new ChatSessionState();
            state.sessionId = inv.getArgument(0);
            return state;
        });
        return new ChatSseServiceImpl(
                mock(JobRuntimeService.class),
                sessionStore,
                integrationService,
                intentService,
                mock(ResumeStorageService.class),
                mock(PersonalContextBuilder.class),
                mock(SystemSettingsService.class),
                new JobBuddyProperties(),
                new AgentServiceProperties());
    }

    @SuppressWarnings("unchecked")
    private ConcurrentMap<SseEmitter, AtomicBoolean> cancelledMap(ChatSseServiceImpl service) throws Exception {
        Field field = ChatSseServiceImpl.class.getDeclaredField("emitterCancelled");
        field.setAccessible(true);
        return (ConcurrentMap<SseEmitter, AtomicBoolean>) field.get(service);
    }

    private boolean waitUntilRemoved(ConcurrentMap<SseEmitter, AtomicBoolean> map, SseEmitter emitter, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (!map.containsKey(emitter)) return true;
            Thread.sleep(20);
        }
        return !map.containsKey(emitter);
    }

    @Test
    void streamStopsPromptlyAfterConnectionClosed() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        IntentService intentService = mock(IntentService.class);
        when(intentService.classify(anyString())).thenAnswer(inv -> {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new IntentResult("job", "job.recommend", 1.0, Collections.<String>emptyList(),
                    "low", false, "call_get_recommend_jobs", Collections.<String, Object>emptyMap());
        });
        AgentIntegrationService integrationService = mock(AgentIntegrationService.class);
        ChatSseServiceImpl service = newService(intentService, integrationService);

        ChatStreamRequest request = new ChatStreamRequest();
        request.setMessage("帮我找岗位");
        SseEmitter emitter = service.stream(request);
        ConcurrentMap<SseEmitter, AtomicBoolean> map = cancelledMap(service);
        assertTrue(entered.await(3, TimeUnit.SECONDS), "后台任务应已进入意图分类阶段");
        AtomicBoolean cancelled = map.get(emitter);
        assertNotNull(cancelled, "在途流应存在取消标记");

        // 模拟容器 onError/onTimeout：连接已关闭。
        cancelled.set(true);
        release.countDown();

        // 任务应在下一次事件下发时感知取消并快速终止（远小于 180s 的流超时），并清理标记防止泄漏。
        assertTrue(waitUntilRemoved(map, emitter, 3000), "连接关闭后后台任务应及时终止并清理取消标记");
    }

    @Test
    void streamCleansUpAfterErrorPath() throws Exception {
        IntentService intentService = mock(IntentService.class);
        when(intentService.classify(anyString())).thenReturn(
                new IntentResult("job", "job.recommend", 1.0, Collections.<String>emptyList(),
                        "low", false, "call_get_recommend_jobs", Collections.<String, Object>emptyMap()));
        AgentIntegrationService integrationService = mock(AgentIntegrationService.class);
        // Runtime 返回空结果触发任务理解失败的异常路径。
        when(integrationService.runRuntime(any(Map.class))).thenReturn(Collections.<String, Object>emptyMap());
        ChatSseServiceImpl service = newService(intentService, integrationService);

        ChatStreamRequest request = new ChatStreamRequest();
        request.setMessage("帮我找岗位");
        SseEmitter emitter = service.stream(request);
        ConcurrentMap<SseEmitter, AtomicBoolean> map = cancelledMap(service);

        assertTrue(waitUntilRemoved(map, emitter, 3000), "异常路径结束后应清理取消标记");
        assertFalse(map.containsKey(emitter));
    }
}
