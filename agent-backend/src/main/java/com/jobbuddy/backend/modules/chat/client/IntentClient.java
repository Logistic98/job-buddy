package com.jobbuddy.backend.modules.chat.client;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * agent-intent 分层意图识别服务的调用客户端。
 *
 * <p>通过共享 {@link RestTemplate} 调用 agent-intent 的 {@code POST /v1/intent/classify}，
 * 把响应 {@code data} 映射为后端统一的 {@link IntentResult}。调用经 {@link ServiceResilience}
 * 熔断保护，服务键 {@code agent-intent} 视为幂等可重试；服务不可达、地址未配置或响应为空时返回
 * {@code null}，由上层退化到本地规则分类，保证对话主链路始终可用。</p>
 */
@Component
public class IntentClient {
    private static final Logger log = LoggerFactory.getLogger(IntentClient.class);

    private final RestTemplate restTemplate;
    private final AgentServiceProperties properties;
    private final ServiceResilience resilience;

    public IntentClient(RestTemplate restTemplate, AgentServiceProperties properties, ServiceResilience resilience) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.resilience = resilience;
    }

    /**
     * 调用 agent-intent 对用户消息做预分类。失败或返回空时返回 {@code null}。
     */
    public IntentResult classify(final String message) {
        final String baseUrl = intentBaseUrl();
        if (baseUrl.isEmpty()) {
            return null;
        }
        final String url = baseUrl + "/v1/intent/classify";
        return resilience.call("agent-intent", new java.util.function.Supplier<IntentResult>() {
            public IntentResult get() {
                Map<String, Object> request = new LinkedHashMap<String, Object>();
                request.put("message", message == null ? "" : message);
                Map response = restTemplate.postForObject(url, request, Map.class);
                Object data = response == null ? null : response.get("data");
                if (!(data instanceof Map)) {
                    return null;
                }
                return fromData((Map<String, Object>) data);
            }
        }, null, true);
    }

    @SuppressWarnings("unchecked")
    private IntentResult fromData(Map<String, Object> data) {
        Object slots = data.get("slots");
        Map<String, Object> slotMap = slots instanceof Map
                ? new LinkedHashMap<String, Object>((Map<String, Object>) slots)
                : new LinkedHashMap<String, Object>();
        Object secondary = data.get("secondary");
        List<String> secondaryList = secondary instanceof List
                ? (List<String>) secondary
                : Collections.<String>emptyList();
        return new IntentResult(
                stringValue(data.get("domain"), "unknown"),
                stringValue(data.get("intent"), "unknown"),
                doubleValue(data.get("confidence"), 0.0),
                secondaryList,
                stringValue(data.get("risk"), "low"),
                booleanValue(firstPresent(data, "needs_clarification", "needsClarification"), false),
                stringValue(firstPresent(data, "next_action", "nextAction"), "clarify"),
                slotMap
        );
    }

    private String intentBaseUrl() {
        String configured = properties.getIntentUrl();
        if (configured == null || configured.trim().isEmpty() || configured.contains("${")) {
            return "";
        }
        while (configured.endsWith("/")) {
            configured = configured.substring(0, configured.length() - 1);
        }
        return configured;
    }

    private Object firstPresent(Map<String, Object> map, String... keys) {
        if (map == null) return null;
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
        }
        return null;
    }

    private String stringValue(Object value, String fallback) {
        String text = value == null ? "" : String.valueOf(value).trim();
        return text.isEmpty() ? fallback : text;
    }

    private double doubleValue(Object value, double fallback) {
        if (value instanceof Number) return ((Number) value).doubleValue();
        try { return Double.parseDouble(value == null ? "" : String.valueOf(value)); } catch (Exception ignored) { return fallback; }
    }

    private boolean booleanValue(Object value, boolean fallback) {
        if (value instanceof Boolean) return ((Boolean) value).booleanValue();
        String text = value == null ? "" : String.valueOf(value).trim().toLowerCase(java.util.Locale.ROOT);
        if ("true".equals(text)) return true;
        if ("false".equals(text)) return false;
        return fallback;
    }
}
