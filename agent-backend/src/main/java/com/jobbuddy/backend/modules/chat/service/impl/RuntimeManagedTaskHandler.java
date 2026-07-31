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
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Runtime 托管任务链路：流式优先下发答案与推理增量，空产出时回退非流式托管调用， 并按流式中断/无产出/成功分别下发对应终态。
 */
class RuntimeManagedTaskHandler {
  private static final JsonCodec JSON = new JsonCodec();
  private final ChatSseEventSender sender;
  private final AgentIntegrationService integrationService;
  private final RuntimeManagedRequestFactory requestFactory;

  /**
   * 创建运行时托管任务处理器实例。
   *
   * @param sender SSE 事件发送器
   * @param integrationService 集成服务
   * @param requestFactory 请求工厂
   */
  RuntimeManagedTaskHandler(
      ChatSseEventSender sender,
      AgentIntegrationService integrationService,
      RuntimeManagedRequestFactory requestFactory) {
    this.sender = sender;
    this.integrationService = integrationService;
    this.requestFactory = requestFactory;
  }

  /**
   * 处理已选岗位分析。
   *
   * @param emitter SSE 事件发送器
   * @param sessionId 会话标识
   * @param rawMessage 原始消息
   * @param state 状态
   * @param directive 运行时指令
   * @param intent 意图
   * @throws IOException 文件或网络读写失败时抛出
   */
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
              /**
               * 接收并处理输入。
               *
               * @param piece 文本片段
               */
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
              /**
               * 接收并处理输入。
               *
               * @param piece 文本片段
               */
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
    Map<String, Object> sandboxStatus = sandboxExecutionStatus(runtimeResult);
    if (!sandboxStatus.isEmpty()) {
      sender.sendToolStatus(emitter, sessionId, state, sandboxStatus);
    }
    Map<String, Object> webSearchStatus = webSearchExecutionStatus(runtimeResult);
    if (!webSearchStatus.isEmpty()) {
      sender.sendToolStatus(emitter, sessionId, state, webSearchStatus);
    }
    Map<String, Object> resultDetail = new LinkedHashMap<String, Object>();
    resultDetail.put("status", runtimeResult.get("status"));
    resultDetail.put("runId", firstPresent(runtimeResult, "run_id", "runId"));
    resultDetail.put("stopReason", firstPresent(runtimeResult, "stop_reason", "stopReason"));
    if (streamFailed) resultDetail.put("error", streamError);
    boolean hasAnswer = answer != null && !answer.trim().isEmpty();
    boolean runtimeFailed = explicitRuntimeFailure(runtimeResult);
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
    if (streamFailed || runtimeFailed) {
      // 已流式展示部分内容但中途报错：保留已下发文本，但以错误态提示结果可能不完整，避免把残缺回答当成功。
      String reason =
          streamFailed
              ? "Runtime 流式中断，已展示内容可能不完整：" + streamError
              : "Runtime 执行未成功，已保留诊断回答：" + stringValue(resultDetail.get("stopReason"));
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

  /**
   * 把 Runtime 的结构化代码工具结果投影为用户可审计的过程事件。只保留有界执行摘要，不透传候选源码、Sandbox
   * argv 或完整原始响应。
   *
   * @param runtimeResult Runtime 终态
   * @return 沙箱执行过程事件；未调用代码工具时返回空 Map
   */
  static Map<String, Object> sandboxExecutionStatus(Map<String, Object> runtimeResult) {
    Object rawResults = runtimeResult == null ? null : runtimeResult.get("tool_results");
    if (!(rawResults instanceof List)) return Collections.emptyMap();
    Map<?, ?> selected = null;
    for (Object item : (List<?>) rawResults) {
      if (!(item instanceof Map)) continue;
      Map<?, ?> row = (Map<?, ?>) item;
      if ("sandbox_code_execute".equals(stringValue(row.get("tool_name")))) selected = row;
    }
    if (selected == null) return Collections.emptyMap();

    Object rawOutput = selected.get("output");
    Map<?, ?> output = rawOutput instanceof Map ? (Map<?, ?>) rawOutput : Collections.emptyMap();
    boolean toolSuccess = Boolean.TRUE.equals(selected.get("success"));
    boolean sandboxed = Boolean.TRUE.equals(output.get("sandboxed"));
    Object exitCode = output.get("exit_code");
    boolean exitedCleanly = exitCode instanceof Number && ((Number) exitCode).intValue() == 0;
    boolean success = toolSuccess && sandboxed && exitedCleanly;
    Map<String, Object> detail = new LinkedHashMap<String, Object>();
    detail.put("toolName", "sandbox_code_execute");
    detail.put("sandboxed", sandboxed);
    detail.put("language", boundedText(output.get("language"), 40));
    detail.put("exitCode", output.get("exit_code"));
    String stdout = output.get("stdout") == null ? "" : String.valueOf(output.get("stdout"));
    String stderr = output.get("stderr") == null ? "" : String.valueOf(output.get("stderr"));
    String combinedOutput = stdout + "\u0000" + stderr;
    detail.put("outputChars", stdout.length() + stderr.length());
    detail.put("outputSha256", sha256(combinedOutput));
    detail.put("latencyMs", selected.get("latency_ms"));
    if (!success) detail.put("errorCategory", "sandbox_execution_failed");
    String summary = success ? "候选代码已由 agent-sandbox 隔离执行并通过验证。" : "候选代码未能在 agent-sandbox 中成功执行。";
    return toolStatus(
        "runtime_sandbox_code_execute", "沙箱代码执行", success ? "success" : "error", summary, detail);
  }

  private static String sha256(String value) {
    try {
      byte[] digest =
          MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
      return HexFormat.of().formatHex(digest);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 不可用", exception);
    }
  }

  /**
   * 把 Runtime 的网页搜索结果投影为用户可审计的过程事件。只保留标题与公网 URL，不透传网页摘要或原始响应。
   *
   * @param runtimeResult Runtime 终态
   * @return 联网搜索过程事件；未调用搜索工具时返回空 Map
   */
  static Map<String, Object> webSearchExecutionStatus(Map<String, Object> runtimeResult) {
    Object rawResults = runtimeResult == null ? null : runtimeResult.get("tool_results");
    if (!(rawResults instanceof List)) return Collections.emptyMap();
    Map<?, ?> selected = null;
    for (Object item : (List<?>) rawResults) {
      if (!(item instanceof Map)) continue;
      Map<?, ?> row = (Map<?, ?>) item;
      if ("web_search".equals(stringValue(row.get("tool_name")))) selected = row;
    }
    if (selected == null) return Collections.emptyMap();

    Object rawOutput = selected.get("output");
    Map<?, ?> output = rawOutput instanceof Map ? (Map<?, ?>) rawOutput : Collections.emptyMap();
    List<Map<String, Object>> sources = new java.util.ArrayList<Map<String, Object>>();
    Object rawSources = output.get("results");
    if (rawSources instanceof List) {
      for (Object item : (List<?>) rawSources) {
        if (!(item instanceof Map)) continue;
        Map<?, ?> source = (Map<?, ?>) item;
        String title = boundedText(source.get("title"), 180);
        String url = boundedHttpUrl(source.get("url"), 500);
        if (title.isEmpty() || url.isEmpty()) continue;
        Map<String, Object> projected = new LinkedHashMap<String, Object>();
        projected.put("title", title);
        projected.put("url", url);
        sources.add(projected);
        if (sources.size() >= 5) break;
      }
    }
    boolean success = Boolean.TRUE.equals(selected.get("success")) && !sources.isEmpty();
    Map<String, Object> detail = new LinkedHashMap<String, Object>();
    detail.put("query", boundedText(output.get("query"), 240));
    List<String> queries = new java.util.ArrayList<String>();
    Object rawQueries = output.get("queries");
    if (rawQueries instanceof List) {
      for (Object item : (List<?>) rawQueries) {
        String query = boundedText(item, 240);
        if (query.isEmpty() || queries.contains(query)) continue;
        queries.add(query);
        if (queries.size() >= 3) break;
      }
    }
    detail.put("queries", queries);
    detail.put("provider", boundedText(output.get("source"), 60));
    detail.put("rawCount", output.get("raw_count"));
    detail.put("deduplicatedCount", output.get("deduplicated_count"));
    List<String> preferredSourceDomains = new java.util.ArrayList<String>();
    Object rawPreferredSourceDomains = output.get("preferred_source_domains");
    if (rawPreferredSourceDomains instanceof List) {
      for (Object item : (List<?>) rawPreferredSourceDomains) {
        String domain = boundedText(item, 120);
        if (domain.isEmpty() || preferredSourceDomains.contains(domain)) continue;
        preferredSourceDomains.add(domain);
        if (preferredSourceDomains.size() >= 3) break;
      }
    }
    detail.put("preferredSourceDomains", preferredSourceDomains);
    boolean preferredSourceFound = Boolean.TRUE.equals(output.get("preferred_source_found"));
    detail.put("preferredSourceFound", preferredSourceFound);
    detail.put("sourceCount", sources.size());
    detail.put("sources", sources);
    detail.put("latencyMs", selected.get("latency_ms"));
    if (!success) detail.put("error", boundedText(selected.get("error"), 500));
    String summary =
        success
            ? (preferredSourceDomains.isEmpty() || preferredSourceFound
                ? "联网搜索已完成，取得 " + sources.size() + " 个可引用来源。"
                : "联网搜索已完成，但未命中推断的官方域名，当前结果仅作为第三方线索。")
            : "联网搜索未取得可引用来源。";
    return toolStatus("runtime_web_search", "联网搜索", success ? "success" : "error", summary, detail);
  }

  static boolean explicitRuntimeFailure(Map<String, Object> runtimeResult) {
    String status = stringValue(runtimeResult == null ? null : runtimeResult.get("status"));
    return !status.isEmpty() && !"success".equalsIgnoreCase(status);
  }

  private static String boundedText(Object value, int maxChars) {
    String text =
        stringValue(value).replace("\u0000", "").replace("\r\n", "\n").replace('\r', '\n').trim();
    return text.length() <= maxChars ? text : text.substring(0, maxChars) + "...";
  }

  private static String boundedHttpUrl(Object value, int maxChars) {
    String url = boundedText(value, maxChars);
    String lower = url.toLowerCase(java.util.Locale.ROOT);
    return lower.startsWith("https://") || lower.startsWith("http://") ? url : "";
  }
}
