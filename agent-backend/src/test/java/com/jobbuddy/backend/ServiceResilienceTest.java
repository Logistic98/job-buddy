package com.jobbuddy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * 验证 ServiceResilience 的核心行为、异常路径与边界条件。
 */
class ServiceResilienceTest {

  /**
   * 构造测试配置属性。
   *
   * @param maxAttempts 最大尝试次数
   * @param threshold 失败阈值
   * @param open 是否开启
   * @return 测试配置
   */
  private AgentServiceProperties props(int maxAttempts, int threshold, Duration open) {
    AgentServiceProperties p = new AgentServiceProperties();
    p.setMaxAttempts(maxAttempts);
    p.setRetryBackoff(Duration.ZERO);
    p.setCircuitFailureThreshold(threshold);
    p.setCircuitOpenDuration(open);
    return p;
  }

  /**
   * 验证 ServiceResilience 的失败恢复、超时与降级边界。
   */
  @Test
  void shouldReturnResultWithoutRetryOnSuccess() {
    ServiceResilience r = new ServiceResilience(props(3, 5, Duration.ofSeconds(10)));
    AtomicInteger calls = new AtomicInteger(0);
    String value =
        r.call(
            "svc",
            () -> {
              calls.incrementAndGet();
              return "ok";
            },
            "fallback",
            true);
    assertEquals("ok", value);
    assertEquals(1, calls.get());
  }

  /**
   * 验证 ServiceResilience 的失败恢复、超时与降级边界。
   */
  @Test
  void shouldRetryUpToMaxAttemptsThenFallback() {
    ServiceResilience r = new ServiceResilience(props(3, 10, Duration.ofSeconds(10)));
    AtomicInteger calls = new AtomicInteger(0);
    String value =
        r.call(
            "svc",
            () -> {
              calls.incrementAndGet();
              throw new RuntimeException("boom");
            },
            "fallback",
            true);
    assertEquals("fallback", value);
    assertEquals(3, calls.get());
  }

  /**
   * 验证 ServiceResilience 的失败恢复、超时与降级边界。
   */
  @Test
  void shouldNotRetryWhenRetryableFalse() {
    ServiceResilience r = new ServiceResilience(props(3, 10, Duration.ofSeconds(10)));
    AtomicInteger calls = new AtomicInteger(0);
    String value =
        r.call(
            "svc",
            () -> {
              calls.incrementAndGet();
              throw new RuntimeException("boom");
            },
            "fallback",
            false);
    assertEquals("fallback", value);
    assertEquals(1, calls.get());
  }

  /**
   * 验证 ServiceResilience 的失败恢复、超时与降级边界。
   */
  @Test
  void shouldSkipRetryAndCircuitForDeterministicFailure() {
    ServiceResilience r = new ServiceResilience(props(3, 1, Duration.ofSeconds(30)));
    AtomicInteger calls = new AtomicInteger(0);

    String value =
        r.call(
            "svc",
            () -> {
              calls.incrementAndGet();
              throw new IllegalArgumentException("deterministic");
            },
            "fallback",
            false,
            error -> false);

    assertEquals("fallback", value);
    assertEquals(1, calls.get());
    assertTrue(!r.isOpen("svc"));
  }

  /**
   * 验证 ServiceResilience 的失败恢复、超时与降级边界。
   */
  @Test
  void shouldOpenCircuitAfterThresholdAndShortCircuit() {
    ServiceResilience r = new ServiceResilience(props(1, 2, Duration.ofSeconds(30)));
    AtomicInteger calls = new AtomicInteger(0);
    for (int i = 0; i < 2; i++) {
      r.call(
          "svc",
          () -> {
            calls.incrementAndGet();
            throw new RuntimeException("boom");
          },
          "fallback",
          false);
    }
    assertTrue(r.isOpen("svc"));
    int before = calls.get();
    String value =
        r.call(
            "svc",
            () -> {
              calls.incrementAndGet();
              return "ok";
            },
            "fallback",
            false);
    assertEquals("fallback", value);
    assertEquals(before, calls.get());
  }

  /**
   * 验证 ServiceResilience 的失败恢复、超时与降级边界。
   */
  @Test
  void shouldResetFailuresOnSuccess() {
    ServiceResilience r = new ServiceResilience(props(1, 3, Duration.ofSeconds(30)));
    r.recordFailure("svc");
    r.recordFailure("svc");
    r.recordSuccess("svc");
    r.recordFailure("svc");
    r.recordFailure("svc");
    assertTrue(!r.isOpen("svc"));
  }
}
