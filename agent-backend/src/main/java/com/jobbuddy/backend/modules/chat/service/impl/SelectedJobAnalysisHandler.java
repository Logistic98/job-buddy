package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.SELECTED_JOB_CONTEXT_KEY;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.toolStatus;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * 选中岗位分析入口：从当前卡片和推荐快照恢复岗位上下文，再委托简历匹配链路执行统一的证据型分析。
 */
class SelectedJobAnalysisHandler {
  private final ChatSseEventSender sender;
  private final SelectedJobContextResolver contextResolver;
  private final ResumeFlowHandler resumeFlowHandler;

  /**
   * 创建已选岗位分析处理器实例。
   *
   * @param sender SSE 事件发送器
   * @param contextResolver 上下文解析器
   * @param resumeFlowHandler 简历流程处理器
   */
  SelectedJobAnalysisHandler(
      ChatSseEventSender sender,
      SelectedJobContextResolver contextResolver,
      ResumeFlowHandler resumeFlowHandler) {
    this.sender = sender;
    this.contextResolver = contextResolver;
    this.resumeFlowHandler = resumeFlowHandler;
  }

  /**
   * 处理已选岗位分析。
   *
   * @param emitter SSE 事件发送器
   * @param sessionId 会话标识
   * @param state 状态
   * @param rawMessage 原始消息
   * @param selectedJob 已选岗位
   * @throws IOException 文件或网络读写失败时抛出
   */
  void handle(
      SseEmitter emitter,
      String sessionId,
      ChatSessionState state,
      String rawMessage,
      Map<String, Object> selectedJob)
      throws IOException {
    Map<String, Object> initialContext = contextResolver.compact(selectedJob);
    // 岗位匹配必须同时具备已选简历和可核验岗位上下文。
    if (state == null || state.resumeId == null || state.resumeId.trim().isEmpty()) {
      sender.sendAssistant(
          emitter,
          sessionId,
          state,
          "请先选择或上传 PDF 简历，再分析此岗位与简历的匹配度。",
          java.util.Collections.<String, Object>singletonMap("selectedJob", initialContext));
      return;
    }
    Map<String, Object> startDetail = new LinkedHashMap<String, Object>();
    startDetail.put("job", initialContext);
    startDetail.put("hasJobDescription", contextResolver.hasSufficientDescription(initialContext));
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "selected_job_context", "读取选中岗位上下文", "running", "正在读取当前岗位卡片与推荐快照。", startDetail));

    // 只合并当前卡片与会话推荐快照，不在分析入口触发 Boss 详情访问。
    SelectedJobContextResolver.Resolution resolution =
        contextResolver.resolve(selectedJob, state == null ? null : state.jobs);
    Map<String, Object> selectedJobContext = resolution.getJob();
    rememberSelectedJob(state, selectedJobContext);

    boolean hasFullDescription = contextResolver.hasSufficientDescription(selectedJobContext);
    Map<String, Object> successDetail = new LinkedHashMap<String, Object>();
    successDetail.put("job", selectedJobContext);
    successDetail.put("detailLoaded", resolution.isDetailLoaded());
    successDetail.put("hasJobDescription", hasFullDescription);
    successDetail.put("warning", resolution.getWarning());
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "selected_job_context",
            "岗位上下文已确认",
            "success",
            hasFullDescription ? "已从当前推荐结果保存选中岗位与完整 JD。" : "已保存选中岗位；职位描述加载保持独立，本次使用岗位列表信息分析。",
            successDetail));
    resumeFlowHandler.handleSelectedJobMatch(
        emitter, sessionId, state, rawMessage, selectedJobContext);
  }

  /**
   * 记录已选项岗位。
   *
   * @param state 状态
   * @param selectedJobContext 已选岗位上下文
   */
  private void rememberSelectedJob(ChatSessionState state, Map<String, Object> selectedJobContext) {
    if (state == null) return;
    state.lastSlots =
        state.lastSlots == null
            ? new LinkedHashMap<String, Object>()
            : new LinkedHashMap<String, Object>(state.lastSlots);
    state.lastSlots.put(SELECTED_JOB_CONTEXT_KEY, selectedJobContext);
  }
}
