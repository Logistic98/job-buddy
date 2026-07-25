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
 * Python Agent 服务出站调用的无依赖弹性层。
 *
 * <p>提供带退避的有界重试和服务级熔断，避免单个下游故障拖住所有请求。
 */
@Component
public class ServiceResilience {
  private static final Logger log = LoggerFactory.getLogger(ServiceResilience.class);

  private final AgentServiceProperties properties;
  private final ConcurrentHashMap<String, Circuit> circuits =
      new ConcurrentHashMap<String, Circuit>();

  /**
   * 创建服务弹性治理实例。
   *
   * @param properties 配置属性
   */
  public ServiceResilience(AgentServiceProperties properties) {
    this.properties = properties;
  }

  /**
   * 执行下游动作；熔断开启或全部尝试失败时返回降级结果。
   *
   * <p>仅幂等操作可重试，非幂等写入或运行必须传入 {@code retryable=false}。
   *
   * @param service 下游服务
   * @param action 执行动作
   * @param fallback 降级结果
   * @param retryable 是否允许重试
   * @return 下游调用结果
   */
  public <T> T call(String service, Supplier<T> action, T fallback, boolean retryable) {
    return call(service, action, fallback, retryable, error -> true);
  }

  /**
   * 使用调用方提供的瞬时故障分类执行操作。
   *
   * <p>确定性错误表示依赖可达，因此不重试，也不计入可用性熔断。
   *
   * @param service 下游服务
   * @param action 执行动作
   * @param fallback 降级结果
   * @param retryable 是否允许重试
   * @param transientFailure 瞬时故障判定函数
   * @return 下游调用结果
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

  /**
   * 返回 {@code service} 熔断器是否开启，供流式调用方使用。
   *
   * @param service 下游服务
   * @return 熔断器是否开启
   */
  public boolean isOpen(String service) {
    Circuit circuit = circuits.get(service);
    return circuit != null && circuit.openUntil > System.currentTimeMillis();
  }

  /**
   * 记录流式调用成功并关闭熔断。
   *
   * @param service 下游服务
   */
  public void recordSuccess(String service) {
    circuits.computeIfAbsent(service, k -> new Circuit()).failures.set(0);
  }

  /**
   * 记录流式调用失败，达到阈值后开启熔断。
   *
   * @param service 下游服务
   */
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

  /**
   * 定义熔断器。
   */
  private static final class Circuit {
    final AtomicInteger failures = new AtomicInteger(0);
    volatile long openUntil = 0L;
  }
}
