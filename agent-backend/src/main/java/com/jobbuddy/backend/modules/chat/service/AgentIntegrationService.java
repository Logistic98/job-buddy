package com.jobbuddy.backend.modules.chat.service;

import com.jobbuddy.backend.modules.chat.vo.TraceStep;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

public interface AgentIntegrationService {
  List<Map<String, Object>> listTools();

  List<Map<String, Object>> searchMemory(String query, String scope);

  void writeMemory(String scope, String content);

  Map<String, Object> evalTrace(List<TraceStep> trace);

  Map<String, Object> runRuntime(Map<String, Object> request);

  /** Directly invokes a named read-only Runtime tool and returns the normalized ToolResult data. */
  Map<String, Object> invokeRuntimeTool(String toolName, Map<String, Object> arguments);

  /**
   * 以 Token 流式调用 Agent Runtime，逐字回调答案增量，返回 done 终态数据。
   *
   * @param request runtime 请求体，与 runRuntime 契约一致
   * @param onToken 每个答案增量片段的回调
   * @return done 事件聚合数据（含 answer、status、stop_reason 等）；调用失败返回空 Map
   */
  Map<String, Object> runRuntimeStream(Map<String, Object> request, Consumer<String> onToken);

  /**
   * 以 Token 流式调用 Agent Runtime，逐字回调推理过程与答案增量，返回 done 终态数据。
   *
   * @param request runtime 请求体，与 runRuntime 契约一致
   * @param onToken 每个答案增量片段的回调
   * @param onReasoning 每个推理过程增量片段的回调（推理模型的思考过程），可为 null
   * @return done 事件聚合数据（含 answer、reasoning、status、stop_reason 等）；调用失败返回空 Map
   */
  Map<String, Object> runRuntimeStream(
      Map<String, Object> request, Consumer<String> onToken, Consumer<String> onReasoning);
}
