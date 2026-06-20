package com.jobbuddy.backend.modules.auth.client;

import com.jobbuddy.backend.common.config.AgentServiceProperties;
import com.jobbuddy.backend.common.resilience.ServiceResilience;
import com.jobbuddy.backend.common.result.ErrorCode;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Boss 工具能力客户端。
 *
 * 旧实现直连独立 Boss 浏览器服务；当前调用 agent-runtime 的 boss_browser 代理工具，
 * 由 Runtime 转发到 agent-tool 中真正的 Boss 工具实现。
 *
 * 对上仍返回原统一响应 {code, message, data}，保持 BossCliServiceImpl 调用契约稳定。
 */
@Service
public class BossBrowserClient {
    private static final String TOOL_NAME = "boss_browser";
    private static final String SERVICE_KEY = "boss-browser";

    private final RestTemplate restTemplate;
    private final AgentServiceProperties properties;
    private final ServiceResilience resilience;

    public BossBrowserClient(RestTemplate restTemplate, AgentServiceProperties properties, ServiceResilience resilience) {
        this.restTemplate = restTemplate;
        this.properties = properties;
        this.resilience = resilience;
    }

    public Map<String, Object> get(String path) {
        return invoke(mapOperation(path), Collections.<String, Object>emptyMap());
    }

    public Map<String, Object> post(String path, Map<String, Object> body) {
        return invoke(mapOperation(path), body == null ? Collections.<String, Object>emptyMap() : body);
    }

    public String baseUrl() {
        return properties.resolvedRuntimeUrl();
    }

    private Map<String, Object> invoke(String operation, Map<String, Object> payload) {
        final String url = baseUrl() + "/v1/runtime/tools/" + TOOL_NAME + "/invoke";
        Map<String, Object> arguments = new LinkedHashMap<String, Object>();
        arguments.put("operation", operation);
        arguments.put("payload", payload == null ? Collections.<String, Object>emptyMap() : payload);

        final Map<String, Object> body = new LinkedHashMap<String, Object>();
        body.put("arguments", arguments);

        // Boss 工具访问外部站点，存在限速与验证码风险，调用视为非幂等不做重试；仅借助
        // ServiceResilience 的熔断能力，避免 Runtime 不可达时持续阻塞在读超时上。
        Map<String, Object> fallback = failure("BOSS_TOOL_UNREACHABLE", "无法连接 Runtime Boss 工具（" + url + "），请稍后重试");
        return resilience.call(SERVICE_KEY, new Supplier<Map<String, Object>>() {
            @SuppressWarnings("unchecked")
            public Map<String, Object> get() {
                Map<String, Object> response = restTemplate.postForObject(url, body, Map.class);
                if (response == null) return failure("BOSS_TOOL_EMPTY", "Runtime Boss 工具返回空响应");
                Object data = response.get("data");
                if (!(data instanceof Map)) return failure("BOSS_TOOL_BAD_RESPONSE", "Runtime Boss 工具响应缺少 data");
                Map<String, Object> toolResult = (Map<String, Object>) data;
                if (!Boolean.TRUE.equals(toolResult.get("success"))) {
                    return failure("BOSS_TOOL_FAILED", String.valueOf(toolResult.get("error")));
                }
                Object output = toolResult.get("output");
                if (output instanceof Map) return (Map<String, Object>) output;
                return failure("BOSS_TOOL_BAD_OUTPUT", "Runtime Boss 工具输出不是对象");
            }
        }, fallback, false);
    }

    private String mapOperation(String path) {
        if ("/status".equals(path)) return "status";
        if ("/login/qr/start".equals(path)) return "qr_start";
        if ("/login/qr/status".equals(path)) return "qr_status";
        if ("/search".equals(path)) return "search";
        if ("/detail".equals(path)) return "detail";
        if ("/profile".equals(path)) return "profile";
        if ("/rate".equals(path)) return "rate";
        throw new IllegalArgumentException("不支持的 Boss 工具路径: " + path);
    }

    private Map<String, Object> failure(String code, String message) {
        Map<String, Object> error = new LinkedHashMap<String, Object>();
        error.put("code", code);
        error.put("message", message);
        Map<String, Object> envelope = new LinkedHashMap<String, Object>();
        envelope.put("code", ErrorCode.DEPENDENCY_FAILURE.getCode());
        envelope.put("message", message);
        envelope.put("data", null);
        envelope.put("error", error);
        return envelope;
    }
}
