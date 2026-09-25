package com.jobbuddy.backend.modules.chat.service.impl;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunResult;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.*;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import java.io.IOException;
import java.util.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class ResumeFlowFallbackTest {
  final ChatSseEventSender sender = mock(ChatSseEventSender.class);
  final CurrentResumeLoader loader = mock(CurrentResumeLoader.class);
  final ResumeStorageService storage = mock(ResumeStorageService.class);
  final AgentIntegrationService integration = mock(AgentIntegrationService.class);
  final RuntimeManagedRequestFactory factory = mock(RuntimeManagedRequestFactory.class);
  final ResumeFlowHandler handler =
      new ResumeFlowHandler(
          sender,
          loader,
          storage,
          mock(JobRuntimeService.class),
          mock(ChatSessionStore.class),
          integration,
          factory,
          mock(SelectedJobContextResolver.class));
  final SseEmitter emitter = mock(SseEmitter.class);
  final ChatSessionState state = new ChatSessionState();

  ResumeFlowFallbackTest() {
    ResumeRecord resume = new ResumeRecord();
    resume.setResumeId("resume-1");
    resume.setOriginalName("resume.pdf");
    when(loader.loadCurrentResume(state)).thenReturn(resume);
    state.jobs = new ArrayList<>();
    state.lastSlots = new LinkedHashMap<>();
  }

  @Test
  void streamsTextAndReasoningWithOneAssistantIdentity() throws Exception {
    when(integration.runRuntimeStream(any(), any(), any()))
        .thenAnswer(
            call -> {
              Consumer<String> text = call.getArgument(1);
              Consumer<String> reasoning = call.getArgument(2);
              text.accept(null);
              text.accept("");
              reasoning.accept(null);
              reasoning.accept("");
              text.accept("参考分析");
              reasoning.accept("依据简历");
              return RuntimeRunResult.empty();
            });
    match();
    ArgumentCaptor<String> id = ArgumentCaptor.forClass(String.class);
    verify(sender).sendMessageDelta(eq(emitter), eq("session"), id.capture(), eq("参考分析"));
    verify(sender).sendReasoningDelta(emitter, "session", id.getValue(), "依据简历");
    Map<String, Object> metadata = assistantMetadata("参考分析");
    assertEquals(id.getValue(), metadata.get("assistantId"));
    assertEquals("依据简历", metadata.get("reasoning"));
    verify(factory, never()).runRuntimeManagedAnswerWithProfile(any(), any(), any(), any());
  }

  @Test
  void emptyStreamUsesManagedAnswer() throws Exception {
    when(factory.runRuntimeManagedAnswerWithProfile(any(), any(), any(), any()))
        .thenReturn(Map.of("answer", "补充回答"));
    match();
    assertEquals("general_role_knowledge", assistantMetadata("补充回答").get("matchBasis"));
    verify(factory)
        .runRuntimeManagedAnswerWithProfile(
            eq("session"), contains("Java"), eq("default"), eq(Collections.emptyMap()));
  }

  @ParameterizedTest
  @ValueSource(strings = {"empty", "runtime", "text", "reasoning"})
  void unavailableStreamReturnsLocalReferenceWithDiagnostic(String failure) throws Exception {
    when(factory.runRuntimeManagedAnswerWithProfile(any(), any(), any(), any()))
        .thenReturn(Collections.emptyMap());
    if (failure.equals("runtime")) {
      when(integration.runRuntimeStream(any(), any(), any()))
          .thenThrow(new IllegalStateException("unavailable"));
    } else if (!failure.equals("empty")) {
      if (failure.equals("text"))
        doThrow(new IOException("closed"))
            .when(sender)
            .sendMessageDelta(any(), any(), any(), any());
      else
        doThrow(new IOException("closed"))
            .when(sender)
            .sendReasoningDelta(any(), any(), any(), any());
      when(integration.runRuntimeStream(any(), any(), any()))
          .thenAnswer(
              call -> {
                Consumer<String> consumer = call.getArgument(failure.equals("text") ? 1 : 2);
                consumer.accept("piece");
                return null;
              });
    }
    match();
    ArgumentCaptor<String> answer = ArgumentCaptor.forClass(String.class);
    ArgumentCaptor<Map> metadata = ArgumentCaptor.forClass(Map.class);
    verify(sender)
        .sendAssistant(eq(emitter), eq("session"), eq(state), answer.capture(), metadata.capture());
    assertFalse(answer.getValue().isBlank());
    Map<?, ?> detail = (Map<?, ?>) metadata.getValue().get("resumeMatch");
    assertEquals("general_role_knowledge_fallback", detail.get("basis"));
    if (!failure.equals("empty")) assertTrue(detail.containsKey("runtime_error"));
  }

  @Test
  void analyzesSelectedResumeAndPublishesSummary() throws Exception {
    ResumeRecord analyzed = new ResumeRecord();
    analyzed.setResumeId("resume-1");
    when(storage.analyzeSync("resume-1", "session")).thenReturn(analyzed);
    handler.handleResumeAnalyze(emitter, "session", state);
    verify(storage).analyzeSync("resume-1", "session");
    verify(storage).summarize(analyzed);
    verify(sender).sendAssistant(eq(emitter), eq("session"), eq(state), contains("简历"), anyMap());
    verify(sender, times(2)).sendToolStatus(eq(emitter), eq("session"), eq(state), any());
  }

  @Test
  void missingResumeStopsBeforeRuntimeAndStorage() throws Exception {
    when(loader.loadCurrentResume(state)).thenReturn(null);
    handler.handleResumeAnalyze(emitter, "session", state);
    match();
    verifyNoInteractions(storage, integration, factory);
    verify(sender, times(2)).sendAssistant(eq(emitter), eq("session"), eq(state), contains("PDF"));
  }

  void match() throws IOException {
    handler.handleResumeMatch(emitter, "session", state, null, "Java 开发", Collections.emptyMap());
  }

  @SuppressWarnings("unchecked")
  Map<String, Object> assistantMetadata(String answer) throws IOException {
    ArgumentCaptor<Map> metadata = ArgumentCaptor.forClass(Map.class);
    verify(sender)
        .sendAssistant(eq(emitter), eq("session"), eq(state), eq(answer), metadata.capture());
    return metadata.getValue();
  }
}
