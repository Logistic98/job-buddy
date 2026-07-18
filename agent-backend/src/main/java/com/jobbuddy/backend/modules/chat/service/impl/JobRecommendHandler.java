package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.toolStatus;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/** 岗位推荐链路：Boss 岗位搜索、卡片下发与岗位消息持久化（普通推荐追加、换一批替换最近岗位消息）。 */
class JobRecommendHandler {
  private final ChatSseEventSender sender;
  private final ChatPersistenceCoordinator persistence;
  private final JobRuntimeService jobRuntimeService;
  private final JobBuddyProperties properties;

  JobRecommendHandler(
      ChatSseEventSender sender,
      ChatPersistenceCoordinator persistence,
      JobRuntimeService jobRuntimeService,
      JobBuddyProperties properties) {
    this.sender = sender;
    this.persistence = persistence;
    this.jobRuntimeService = jobRuntimeService;
    this.properties = properties;
  }

  /** 读取上一轮检索条件中的候选池页码，缺省或非法时视为第 1 批，供换一批确定性翻页递增使用。 */
  int currentBossPage(Map<String, Object> slots) {
    if (slots == null) return 1;
    Object value = slots.get("boss_page");
    if (value instanceof Number) return Math.max(1, ((Number) value).intValue());
    if (value != null) {
      try {
        return Math.max(1, Integer.parseInt(String.valueOf(value).trim()));
      } catch (NumberFormatException ignored) {
        return 1;
      }
    }
    return 1;
  }

  void handle(SseEmitter emitter, String sessionId, ChatSessionState state, IntentResult intent)
      throws IOException {
    handle(emitter, sessionId, state, intent, false);
  }

  void handle(
      SseEmitter emitter,
      String sessionId,
      ChatSessionState state,
      IntentResult intent,
      boolean replaceLatestJobTurn)
      throws IOException {
    Map<String, Object> searchPayload = new LinkedHashMap<String, Object>();
    searchPayload.put("stage", "prepare_cli");
    searchPayload.put("slots", intent.getSlots());
    searchPayload.put("timeoutSeconds", jobRuntimeService.bossCandidatePoolTimeoutSeconds());
    searchPayload.put("liveEnabled", true);
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus("job_search", "开始搜索岗位", "running", "正在搜索 Boss 岗位，登录失效时会弹出扫码。", searchPayload));
    List<Map<String, Object>> jobs;
    try {
      jobs = jobRuntimeService.recommendJobsFast(intent, sessionId, null);
    } catch (BossAuthRequiredException e) {
      String reason =
          e.getMessage() == null || e.getMessage().trim().isEmpty()
              ? "Boss 登录态失效。"
              : e.getMessage();
      Map<String, Object> authData =
          e.getAuthData() == null ? Collections.<String, Object>emptyMap() : e.getAuthData();
      Map<String, Object> detail = new LinkedHashMap<String, Object>();
      detail.put("reason", reason);
      detail.put("authData", authData);
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus("job_search", "需要登录 Boss 直聘", "error", reason, detail));
      throw e;
    } catch (RuntimeException e) {
      String reason =
          e.getMessage() == null || e.getMessage().trim().isEmpty() ? "岗位搜索失败" : e.getMessage();
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus("job_search", "岗位搜索失败", "error", reason, searchPayload));
      sender.sendAssistant(emitter, sessionId, state, reason);
      return;
    }
    int limit = Math.max(1, properties.getMaxJobsPerRecommend());
    jobs =
        jobs.size() > limit
            ? new java.util.ArrayList<Map<String, Object>>(jobs.subList(0, limit))
            : jobs;
    state.jobs = jobs;
    Map<String, Object> jobSearchDetail = new LinkedHashMap<String, Object>();
    jobSearchDetail.put("count", jobs.size());
    jobSearchDetail.put("mode", "live");
    jobSearchDetail.put(
        "sample",
        jobs.isEmpty() ? Collections.emptyList() : jobs.subList(0, Math.min(3, jobs.size())));
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "job_search", "岗位搜索完成", "success", "找到 " + jobs.size() + " 个候选岗位。", jobSearchDetail));
    sender.send(emitter, "job_cards", jobs);
    // 普通推荐保留独立助手消息，表示一轮新的用户意图；换一批是同一轮检索条件下的确定性翻页，
    // 应直接替换最近的岗位卡片消息，避免聊天区和历史回放里出现“换一批又新开一轮会话”的错觉。
    if (!jobs.isEmpty()) {
      if (replaceLatestJobTurn) {
        persistence.replaceLatestJobMessageAsync(sessionId, jobs, state.toolEvents);
      } else {
        Map<String, Object> turnMeta = new LinkedHashMap<String, Object>();
        turnMeta.put("jobCards", jobs);
        if (state.toolEvents != null && !state.toolEvents.isEmpty()) {
          turnMeta.put(
              "toolEvents", new java.util.ArrayList<Map<String, Object>>(state.toolEvents));
        }
        persistence.appendMessageAsync(sessionId, "assistant", "", turnMeta);
      }
    }
    // 岗位列表与本轮推理过程统一异步落库，确保扫码搜索路径下首屏卡片即时呈现、不被持久化阻塞。
    persistence.saveStateAsync(state);
  }
}
