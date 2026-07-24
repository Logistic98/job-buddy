package com.jobbuddy.backend.common.resilience;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Dependency-free resilience layer for outbound calls to the Python agent services. Provides
 * bounded retry with backoff and a per-service circuit breaker so a single unavailable downstream
 * cannot stall every request on the configured read timeout.
 */
@Component
public class ServiceResilience {
  private static final Logger log = LoggerFactory.getLogger(ServiceResilience.class);

  private final AgentServiceProperties properties;
  private final ConcurrentHashMap<String, Circuit> circuits =
      new ConcurrentHashMap<String, Circuit>();

  public ServiceResilience(AgentServiceProperties properties) {
    this.properties = properties;
  }

  /**
   * Execute {@code action}, returning {@code fallback} when the circuit is open or all attempts
   * fail. Only retry idempotent operations; pass {@code retryable=false} for non-idempotent
   * writes/runs.
   */
  public <T> T call(String service, Supplier<T> action, T fallback, boolean retryable) {
    return call(service, action, fallback, retryable, error -> true);
  }

  /**
   * Execute with caller-provided transient-failure classification. A deterministic error proves the
   * dependency is reachable, so it is neither retried nor counted toward the availability circuit.
   */
  public <T> T call(
      String service,
      Supplier<T> action,
      T fallback,
      boolean retryable,
      Predicate<RuntimeException> transientFailure) {
    Circuit circuit = circuits.computeIfAbsent(service, k -> new Circuit());
    long now = System.currentTimeMillis();
    long openUntil = circuit.openUntil;
    if (openUntil > now) {
      log.warn("{} 熔断开启中，直接降级：剩余 {}ms", service, openUntil - now);
      return fallback;
    }

    int maxAttempts = retryable ? Math.max(1, properties.getMaxAttempts()) : 1;
    long backoffMs = properties.getRetryBackoff().toMillis();
    int threshold = Math.max(1, properties.getCircuitFailureThreshold());
    long openMs = properties.getCircuitOpenDuration().toMillis();

    for (int attempt = 1; attempt <= maxAttempts; attempt++) {
      try {
        T result = action.get();
        circuit.failures.set(0);
        return result;
      } catch (RuntimeException e) {
        if (!transientFailure.test(e)) {
          circuit.failures.set(0);
          log.warn("{} 调用返回不可重试错误，跳过重试与熔断计数", service, e);
          return fallback;
        }
        int failures = circuit.failures.incrementAndGet();
        log.warn("{} 调用失败（第 {}/{} 次，累计失败 {}）", service, attempt, maxAttempts, failures, e);
        if (failures >= threshold) {
          circuit.openUntil = System.currentTimeMillis() + openMs;
          log.warn("{} 连续失败达阈值 {}，熔断 {}ms 后再试", service, threshold, openMs);
          break;
        }
        if (attempt < maxAttempts && backoffMs > 0) {
          try {
            Thread.sleep(backoffMs);
          } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            break;
          }
        }
      }
    }
    return fallback;
  }

  /** Whether the circuit for {@code service} is currently open (used by streaming callers). */
  public boolean isOpen(String service) {
    Circuit circuit = circuits.get(service);
    return circuit != null && circuit.openUntil > System.currentTimeMillis();
  }

  /** Record a successful streaming call, closing the circuit. */
  public void recordSuccess(String service) {
    circuits.computeIfAbsent(service, k -> new Circuit()).failures.set(0);
  }

  /** Record a failed streaming call, opening the circuit once the threshold is reached. */
  public void recordFailure(String service) {
    Circuit circuit = circuits.computeIfAbsent(service, k -> new Circuit());
    int failures = circuit.failures.incrementAndGet();
    if (failures >= Math.max(1, properties.getCircuitFailureThreshold())) {
      circuit.openUntil =
          System.currentTimeMillis() + properties.getCircuitOpenDuration().toMillis();
      log.warn(
          "{} 连续失败达阈值 {}，熔断 {}ms",
          service,
          properties.getCircuitFailureThreshold(),
          properties.getCircuitOpenDuration().toMillis());
    }
  }

  private static final class Circuit {
    final AtomicInteger failures = new AtomicInteger(0);
    volatile long openUntil = 0L;
  }
}
