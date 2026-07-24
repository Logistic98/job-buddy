package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.toolStatus;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.firstPresent;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.stringValue;

import com.jobbuddy.backend.common.util.JsonCodec;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunRequest;
import com.jobbuddy.backend.modules.chat.dto.runtime.RuntimeRunResult;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** Runtime 托管任务链路：流式优先下发答案与推理增量，空产出时回退非流式托管调用， 并按流式中断/无产出/成功分别下发对应终态。 */
class RuntimeManagedTaskHandler {
  private static final JsonCodec JSON = new JsonCodec();
  private final ChatSseEventSender sender;
  private final AgentIntegrationService integrationService;
  private final RuntimeManagedRequestFactory requestFactory;

  RuntimeManagedTaskHandler(
      ChatSseEventSender sender,
      AgentIntegrationService integrationService,
      RuntimeManagedRequestFactory requestFactory) {
    this.sender = sender;
    this.integrationService = integrationService;
    this.requestFactory = requestFactory;
  }

  void handle(
      final SseEmitter emitter,
      final String sessionId,
      String rawMessage,
      ChatSessionState state,
      Map<String, Object> directive,
      IntentResult intent)
      throws IOException {
    Map<String, Object> detail = new LinkedHashMap<String, Object>();
    detail.put("directive", directive == null ? Collections.emptyMap() : directive);
    detail.put("intent", intent);
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus("runtime_managed", "Runtime 托管任务", "running", "Agent Runtime 正在生成结果。", detail));

    Map<String, Object> metadata =
        requestFactory.runtimeManagedMetadata(rawMessage, state, directive, intent);
    RuntimeRunRequest request =
        requestFactory.buildRuntimeManagedRequest(
            sessionId, rawMessage, "job-buddy", metadata, true);
    final StringBuilder buffer = new StringBuilder();
    final StringBuilder reasoningBuffer = new StringBuilder();
    final String assistantId =
        "assistant_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    RuntimeRunResult streamResult =
        integrationService.runRuntimeStream(
            request,
            new java.util.function.Consumer<String>() {
              @Override
              public void accept(String piece) {
                if (piece == null || piece.isEmpty()) return;
                buffer.append(piece);
                try {
                  sender.sendMessageDelta(emitter, sessionId, assistantId, piece);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              }
            },
            new java.util.function.Consumer<String>() {
              @Override
              public void accept(String piece) {
                if (piece == null || piece.isEmpty()) return;
                reasoningBuffer.append(piece);
                try {
                  // 逐字下发推理过程，思考阶段即给到前端可见反馈，缩短首字空白感知。
                  sender.sendReasoningDelta(emitter, sessionId, assistantId, piece);
                } catch (IOException e) {
                  throw new RuntimeException(e);
                }
              }
            });
    Map<String, Object> runtimeResult =
        streamResult == null ? Collections.<String, Object>emptyMap() : streamResult.toMap(JSON);

    // runRuntimeStream 在收到 SSE error 事件时返回带 error 字段的 map，据此识别流式中断。
    String streamError = stringValue(firstPresent(runtimeResult, "error", "errorMessage"));
    boolean streamFailed = !streamError.isEmpty();
    // 推理过程优先取 done 终态聚合，缺失时回退到逐字累积，保证落库与展示一致。
    String reasoning = stringValue(runtimeResult.get("reasoning"));
    if (reasoning.isEmpty()) reasoning = reasoningBuffer.toString().trim();
    String answer = stringValue(firstPresent(runtimeResult, "answer", "final_answer"));
    if (answer.isEmpty()) answer = buffer.toString().trim();
    if (answer.isEmpty() && !streamFailed) {
      // 仅在流式连接正常但无产出（偶发空 done）时回退非流式托管调用。流式已报错时不再整请求重跑，
      // 否则会对已部分执行的任务（含 Boss 实时检索/详情）二次触发，既重复消耗预算又增加账号风控风险。
      // 复用流式阶段已装配好的 metadata（含 personal_context），避免回退时对画像/简历/记忆做一次完全相同的二次装配。
      Map<String, Object> fallback =
          requestFactory.runRuntimeManagedAnswerWithProfile(
              sessionId, rawMessage, "job-buddy", metadata);
      String fallbackAnswer = stringValue(firstPresent(fallback, "answer", "final_answer"));
      if (!fallbackAnswer.isEmpty()) {
        runtimeResult = fallback;
        answer = fallbackAnswer;
      }
    }
    if (answer.isEmpty()) answer = stringValue(directive == null ? null : directive.get("answer"));
    Map<String, Object> resultDetail = new LinkedHashMap<String, Object>();
    resultDetail.put("status", runtimeResult.get("status"));
    resultDetail.put("runId", firstPresent(runtimeResult, "run_id", "runId"));
    resultDetail.put("stopReason", firstPresent(runtimeResult, "stop_reason", "stopReason"));
    if (streamFailed) resultDetail.put("error", streamError);
    boolean hasAnswer = answer != null && !answer.trim().isEmpty();
    if (!hasAnswer) {
      String reason =
          streamFailed
              ? "Runtime 流式中断且无产出：" + streamError
              : "Runtime 未返回可展示回答，请检查能力接入、LLM 配置和工具预算。";
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus("runtime_managed", "Runtime 托管任务未产出", "error", reason, resultDetail));
      sender.sendAssistant(
          emitter,
          sessionId,
          state,
          reason,
          runtimeResult.isEmpty()
              ? null
              : Collections.<String, Object>singletonMap("runtimeResult", resultDetail));
      return;
    }
    if (streamFailed) {
      // 已流式展示部分内容但中途报错：保留已下发文本，但以错误态提示结果可能不完整，避免把残缺回答当成功。
      String reason = "Runtime 流式中断，已展示内容可能不完整：" + streamError;
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus("runtime_managed", "Runtime 托管任务中断", "error", reason, resultDetail));
    } else {
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus(
              "runtime_managed", "Runtime 托管任务完成", "success", "Runtime 已返回回答。", resultDetail));
    }
    Map<String, Object> finalMeta = new LinkedHashMap<String, Object>();
    finalMeta.put("assistantId", assistantId);
    if (!runtimeResult.isEmpty()) finalMeta.put("runtimeResult", resultDetail);
    // 推理过程随助手消息一并落库，刷新或切换会话后仍可回看本轮的思考过程。
    if (!reasoning.isEmpty()) finalMeta.put("reasoning", reasoning);
    sender.sendAssistant(emitter, sessionId, state, answer, finalMeta);
  }
}
