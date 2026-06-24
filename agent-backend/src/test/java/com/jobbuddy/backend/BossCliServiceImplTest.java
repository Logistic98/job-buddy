package com.jobbuddy.backend;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.auth.client.BossBrowserClient;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.auth.service.impl.BossCliServiceImpl;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class BossCliServiceImplTest {
    @Test
    void searchJobsAcceptsHttpStyleSuccessEnvelope() {
        BossBrowserClient browserClient = mock(BossBrowserClient.class);
        Map<String, Object> job = new LinkedHashMap<String, Object>();
        job.put("jobName", "大模型应用开发");
        job.put("brandName", "Legacy AI Co");
        when(browserClient.post(eq("/search"), anyMap()))
                .thenReturn(envelope(200, "success", Collections.<String, Object>singletonMap("jobs", Collections.singletonList(job))));
        BossCliServiceImpl service = newService(browserClient);
        IntentResult intent = new IntentResult();
        Map<String, Object> slots = new LinkedHashMap<String, Object>();
        slots.put("role", "大模型应用开发");
        slots.put("city", "上海");
        intent.setSlots(slots);

        List<Map<String, Object>> jobs = service.searchJobsPage(intent, 1);

        assertEquals(1, jobs.size());
        assertEquals("大模型应用开发", jobs.get(0).get("jobName"));
    }

    @Test
    void fetchOnlineProfileAcceptsHttpStyleSuccessEnvelope() {
        BossBrowserClient browserClient = mock(BossBrowserClient.class);
        Map<String, Object> profile = new LinkedHashMap<String, Object>();
        profile.put("name", "测试候选人");
        when(browserClient.post(eq("/profile"), anyMap())).thenReturn(envelope(200, "success", profile));
        BossCliServiceImpl service = newService(browserClient);

        Map<String, Object> result = service.fetchOnlineProfile();

        assertEquals("测试候选人", result.get("name"));
    }

    @Test
    void searchJobsStillRoutesAuthRequiredEnvelope() {
        BossBrowserClient browserClient = mock(BossBrowserClient.class);
        when(browserClient.post(eq("/search"), anyMap())).thenReturn(envelope(4001, "auth required", null));
        BossCliServiceImpl service = newService(browserClient);

        assertThrows(BossAuthRequiredException.class, () -> service.searchJobsPage(new IntentResult(), 1));
    }

    private BossCliServiceImpl newService(BossBrowserClient browserClient) {
        return new BossCliServiceImpl(browserClient, mock(ApplicationEventPublisher.class), new JobBuddyProperties());
    }

    private Map<String, Object> envelope(int code, String message, Object data) {
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("code", code);
        envelope.put("message", message);
        envelope.put("data", data);
        return envelope;
    }
}
