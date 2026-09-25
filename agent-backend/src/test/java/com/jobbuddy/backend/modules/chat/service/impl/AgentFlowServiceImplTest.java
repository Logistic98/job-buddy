package com.jobbuddy.backend.modules.chat.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.dto.request.ChatRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunResult;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class AgentFlowServiceImplTest {
  final JsonCodec json = new JsonCodec();
  final AgentIntegrationService integration = mock(AgentIntegrationService.class);
  final JobBuddyProperties properties = new JobBuddyProperties();
  final AgentFlowServiceImpl service = new AgentFlowServiceImpl(integration, properties);

  @Test
  void missingRuntimeCreatesSessionAndActionableErrorTrace() {
    ChatRequest request = new ChatRequest();
    request.setMessage("Hello");
    var response = service.answer(request);
    assertTrue(response.getSessionId().startsWith("sess_"));
    assertTrue(response.getAnswer().contains("未返回"));
    assertEquals(0, response.getIntent().getConfidence());
    assertEquals("runtime_proxy", response.getExecutionMode());
    assertEquals("error", response.getTrace().get(0).getStatus());
    ArgumentCaptor<RuntimeRunRequest> sent = ArgumentCaptor.forClass(RuntimeRunRequest.class);
    verify(integration).runRuntime(sent.capture());
    assertTrue(sent.getValue().toJson().toString().contains("Hello"));
    assertEquals(response.getSessionId(), sent.getValue().sessionId());
  }

  @Test
  void runtimePlanDirectiveAndBothTraceFormatsArePreserved() {
    when(integration.runRuntime(any()))
        .thenReturn(
            result(
                Map.of(
                    "answer",
                    "Final answer",
                    "directive",
                    Map.of(
                        "domain",
                        "job",
                        "intent",
                        "resume.match",
                        "confidence",
                        "0.82",
                        "slots",
                        Map.of("role", "Java"),
                        "secondary",
                        List.of("resume.analyze"),
                        "risk",
                        "low",
                        "traceId",
                        "trace-1",
                        "router",
                        "runtime"),
                    "plan",
                    Map.of(
                        "steps",
                        List.of(Map.of("goal", "Analyze"), "ignored", Map.of("name", "Verify"))),
                    "logs",
                    List.of(
                        Map.of("step_id", "tool", "status", "failed", "error", "timeout"),
                        "raw log"),
                    "trace_events",
                    List.of(Map.of("event", "finish", "payload", "done"), "unknown"))));
    ChatRequest request = new ChatRequest();
    request.setSessionId("session");
    request.setMessage("Analyze");
    var response = service.answer(request);
    assertEquals("session", response.getSessionId());
    assertEquals("Final answer", response.getAnswer());
    assertEquals("runtime_agent", response.getExecutionMode());
    assertEquals(List.of("Analyze", "Verify"), response.getPlan());
    assertEquals("resume.match", response.getIntent().getIntent());
    assertEquals(0.82, response.getIntent().getConfidence());
    assertEquals("Java", response.getIntent().getSlots().get("role"));
    assertEquals("trace-1", response.getIntent().getTraceId());
    assertEquals(4, response.getTrace().size());
    assertEquals("timeout", response.getTrace().get(0).getDetail());
    assertEquals("raw log", response.getTrace().get(1).getDetail());
    assertEquals("finish", response.getTrace().get(2).getNodeId());
    assertEquals("done", response.getTrace().get(2).getDetail());
  }

  @Test
  void directiveAnswerAndStringClarificationAreAccepted() {
    when(integration.runRuntime(any()))
        .thenReturn(
            result(
                Map.of(
                    "directive",
                    Map.of(
                        "answer",
                        "Which role?",
                        "needsClarification",
                        "true",
                        "nextAction",
                        "clarify",
                        "confidence",
                        "invalid"))));
    var response = service.answer(new ChatRequest());
    assertEquals("Which role?", response.getAnswer());
    assertEquals("clarification", response.getExecutionMode());
    assertEquals("clarify", response.getIntent().getNextAction());
    assertEquals(1, response.getIntent().getConfidence());
    assertEquals("success", response.getTrace().get(0).getStatus());
  }

  @Test
  void legacyFinalAnswerAndNumericConfidenceRemainSupported() {
    when(integration.runRuntime(any()))
        .thenReturn(
            result(
                Map.of(
                    "final_answer",
                    "Legacy answer",
                    "directive",
                    Map.of("confidence", 0.6, "needs_clarification", false))));
    var response = service.answer(new ChatRequest());
    assertEquals("Legacy answer", response.getAnswer());
    assertEquals(0.6, response.getIntent().getConfidence());
    assertEquals("runtime_proxy", response.getExecutionMode());
    assertTrue(response.getPlan().isEmpty());
  }

  RuntimeRunResult result(Map<String, Object> values) {
    return RuntimeRunResult.fromJson(json.toTree(values));
  }
}
