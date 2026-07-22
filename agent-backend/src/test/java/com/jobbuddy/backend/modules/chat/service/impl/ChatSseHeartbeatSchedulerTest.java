package com.jobbuddy.backend.modules.chat.service.impl;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ChatSseHeartbeatSchedulerTest {

  @Test
  void shouldSendHeartbeatDuringLongRunningRequestAndAllowLifecycleStop() throws Exception {
    ChatSseHeartbeatScheduler scheduler =
        new ChatSseHeartbeatScheduler(Executors.defaultThreadFactory());
    ChatSseEventSender sender = mock(ChatSseEventSender.class);
    SseEmitter emitter = mock(SseEmitter.class);
    AtomicBoolean cancelled = new AtomicBoolean(false);
    CountDownLatch heartbeatSent = new CountDownLatch(1);
    doAnswer(
            invocation -> {
              heartbeatSent.countDown();
              return null;
            })
        .when(sender)
        .send(same(emitter), eq("heartbeat"), org.mockito.ArgumentMatchers.any());

    ScheduledFuture<?> heartbeat = null;
    try {
      heartbeat = scheduler.start(emitter, sender, cancelled, 10L, "session-test", () -> {});

      assertTrue(heartbeatSent.await(1, TimeUnit.SECONDS));
      scheduler.stop(heartbeat);
      assertTrue(heartbeat.isCancelled());
    } finally {
      scheduler.stop(heartbeat);
      scheduler.shutdown();
    }
  }
}
