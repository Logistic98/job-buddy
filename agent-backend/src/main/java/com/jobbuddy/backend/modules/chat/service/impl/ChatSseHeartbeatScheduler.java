package com.jobbuddy.backend.modules.chat.service.impl;

import java.io.IOException;
import java.time.Instant;
import java.util.Collections;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 聊天 SSE 保活调度器：长时间模型或工具调用期间定期下发心跳，避免浏览器把正常静默计算误判为断流。 */
final class ChatSseHeartbeatScheduler {
  private static final Logger log = LoggerFactory.getLogger(ChatSseHeartbeatScheduler.class);
  private final ScheduledExecutorService executor;

  ChatSseHeartbeatScheduler(java.util.concurrent.ThreadFactory threadFactory) {
    this.executor = Executors.newSingleThreadScheduledExecutor(threadFactory);
  }

  ScheduledFuture<?> start(
      final SseEmitter emitter,
      final ChatSseEventSender sender,
      final AtomicBoolean cancelled,
      final long intervalMillis,
      final String sessionId,
      final Runnable onDisconnect) {
    if (intervalMillis <= 0L) return null;
    return executor.scheduleAtFixedRate(
        new Runnable() {
          @Override
          public void run() {
            if (cancelled.get()) return;
            try {
              sender.send(
                  emitter,
                  "heartbeat",
                  Collections.singletonMap("timestamp", Instant.now().toString()));
            } catch (IOException | RuntimeException error) {
              if (cancelled.compareAndSet(false, true)) {
                log.debug("SSE 心跳下发失败，终止后台任务 sessionId={}: {}", sessionId, error.getMessage());
                onDisconnect.run();
              }
            }
          }
        },
        intervalMillis,
        intervalMillis,
        TimeUnit.MILLISECONDS);
  }

  void stop(ScheduledFuture<?> heartbeat) {
    if (heartbeat != null) heartbeat.cancel(false);
  }

  void shutdown() {
    executor.shutdownNow();
  }
}
