package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.dto.request.ChatRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunResult;
import com.jobbuddy.backend.modules.chat.service.AgentFlowService;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.util.RuntimeRequestBuilder;
import com.jobbuddy.backend.modules.chat.vo.ChatResponse;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.chat.vo.TraceStep;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;

@Service
public class AgentFlowServiceImpl implements AgentFlowService {
  private static final JsonCodec JSON = new JsonCodec();
  private final AgentIntegrationService integrationService;
  private final JobBuddyProperties properties;

  public AgentFlowServiceImpl(
      AgentIntegrationService integrationService, JobBuddyProperties properties) {
    this.integrationService = integrationService;
    this.properties = properties;
  }

  public ChatResponse answer(ChatRequest request) {
    String sessionId =
        request.getSessionId() == null || request.getSessionId().trim().isEmpty()
            ? "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
            : request.getSessionId();
    RuntimeRunResult result =
        integrationService.runRuntime(buildRuntimeRequest(sessionId, request));
    Map<String, Object> runtimeResult =
        result == null ? Collections.<String, Object>emptyMap() : result.toMap(JSON);
    IntentResult intent = intentFromRuntime(runtimeResult);
    String answer = stringValue(firstPresent(runtimeResult, "answer", "final_answer"));
    if (answer.isEmpty()) answer = stringValue(firstDirective(runtimeResult).get("answer"));
    if (answer.isEmpty()) answer = "Agent Runtime 未返回可展示回答，请检查服务、模型或工具配置。";
    List<TraceStep> trace = traceFromRuntime(runtimeResult);
    if (trace.isEmpty())
      trace.add(
          new TraceStep(
              "runtime_proxy",
              "Agent Runtime 代理",
              runtimeResult.isEmpty() ? "error" : "success",
              runtimeResult.isEmpty() ? "Runtime 不可用" : "Runtime 已返回"));
    return new ChatResponse(
        sessionId,
        answer,
        intent,
        executionMode(runtimeResult, intent),
        planFromRuntime(runtimeResult),
        trace,
        Collections.<String>emptyList(),
        Collections.<Map<String, Object>>emptyList(),
        Collections.<Map<String, Object>>emptyList(),
        Collections.<String, Object>emptyMap(),
        Instant.now());
  }

  private RuntimeRunRequest buildRuntimeRequest(String sessionId, ChatRequest request) {
    return RuntimeRequestBuilder.forEntrypoint(sessionId, request.getMessage(), "chat.ask")
        .budget(
            properties.getRuntimeMaxTurns(),
            properties.getRuntimeMaxToolCalls(),
            properties.getRuntimeMaxFailures(),
            properties.getRuntimeMaxTokens())
        .build();
  }

  private IntentResult intentFromRuntime(Map<String, Object> runtimeResult) {
    Map<String, Object> directive = firstDirective(runtimeResult);
    Object slots = directive.get("slots");
    Map<String, Object> slotMap =
        slots instanceof Map
            ? new LinkedHashMap<String, Object>((Map<String, Object>) slots)
            : new LinkedHashMap<String, Object>();
    Object secondary = directive.get("secondary");
    List<String> secondaryList =
        secondary instanceof List ? (List<String>) secondary : Collections.<String>emptyList();
    IntentResult intentResult =
        new IntentResult(
            stringValue(directive.get("domain"), "runtime"),
            stringValue(directive.get("intent"), "agent.run"),
            doubleValue(
                directive.get("confidence"),
                runtimeResult == null || runtimeResult.isEmpty() ? 0.0 : 1.0),
            secondaryList,
            stringValue(directive.get("risk"), "low"),
            booleanValue(
                firstPresent(directive, "needs_clarification", "needsClarification"), false),
            stringValue(firstPresent(directive, "next_action", "nextAction"), "run_runtime"),
            slotMap);
    intentResult.setTraceId(stringValue(firstPresent(directive, "trace_id", "traceId"), null));
    intentResult.setRouter(stringValue(directive.get("router"), null));
    return intentResult;
  }

  private String executionMode(Map<String, Object> runtimeResult, IntentResult intent) {
    Object plan = runtimeResult == null ? null : runtimeResult.get("plan");
    if (plan instanceof Map) {
      Object steps = ((Map) plan).get("steps");
      if (steps instanceof List && !((List) steps).isEmpty()) return "runtime_agent";
    }
    if (intent.isNeedsClarification()) return "clarification";
    return "runtime_proxy";
  }

  private List<String> planFromRuntime(Map<String, Object> runtimeResult) {
    List<String> planRows = new ArrayList<String>();
    Object plan = runtimeResult == null ? null : runtimeResult.get("plan");
    if (plan instanceof Map && ((Map) plan).get("steps") instanceof List) {
      for (Object item : (List) ((Map) plan).get("steps")) {
        if (item instanceof Map)
          planRows.add(
              stringValue(
                  firstPresent((Map<String, Object>) item, "goal", "name", "result_summary")));
      }
    }
    return planRows;
  }

  private List<TraceStep> traceFromRuntime(Map<String, Object> runtimeResult) {
    List<TraceStep> trace = new ArrayList<TraceStep>();
    Object logs = runtimeResult == null ? null : runtimeResult.get("logs");
    if (logs instanceof List) {
      int index = 0;
      for (Object item : (List) logs) {
        Map<String, Object> row =
            item instanceof Map
                ? (Map<String, Object>) item
                : Collections.<String, Object>singletonMap("value", item);
        trace.add(
            new TraceStep(
                "runtime_" + (++index),
                stringValue(firstPresent(row, "name", "step_id"), "Runtime Step"),
                stringValue(firstPresent(row, "status"), "success"),
                stringValue(firstPresent(row, "output", "error", "value"))));
      }
    }
    Object traceEvents = runtimeResult == null ? null : runtimeResult.get("trace_events");
    if (traceEvents instanceof List) {
      for (Object item : (List) traceEvents) {
        Map<String, Object> row =
            item instanceof Map
                ? (Map<String, Object>) item
                : Collections.<String, Object>singletonMap("value", item);
        trace.add(
            new TraceStep(
                stringValue(firstPresent(row, "event"), "runtime_event"),
                "Runtime Event",
                "success",
                stringValue(row.get("payload"))));
      }
    }
    return trace;
  }

  private Map<String, Object> firstDirective(Map<String, Object> runtimeResult) {
    return RuntimeRequestBuilder.extractDirective(runtimeResult);
  }

  private Object firstPresent(Map<String, Object> map, String... keys) {
    if (map == null) return null;
    for (String key : keys) {
      Object value = map.get(key);
      if (value != null && !String.valueOf(value).trim().isEmpty()) return value;
    }
    return null;
  }

  private String stringValue(Object value) {
    return value == null ? "" : String.valueOf(value).trim();
  }

  private String stringValue(Object value, String fallback) {
    String text = stringValue(value);
    return text.isEmpty() ? fallback : text;
  }

  private double doubleValue(Object value, double fallback) {
    if (value instanceof Number) return ((Number) value).doubleValue();
    try {
      return Double.parseDouble(stringValue(value));
    } catch (Exception ignored) {
      return fallback;
    }
  }

  private boolean booleanValue(Object value, boolean fallback) {
    if (value instanceof Boolean) return ((Boolean) value).booleanValue();
    String text = stringValue(value).toLowerCase(java.util.Locale.ROOT);
    if ("true".equals(text)) return true;
    if ("false".equals(text)) return false;
    return fallback;
  }
}
