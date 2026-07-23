package com.jobbuddy.backend.modules.chat.service.impl;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.toolStatus;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.JobRecommendationResult;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.prompt.model.PersonalContext;
import com.jobbuddy.backend.modules.prompt.service.PersonalContextBuilder;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
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
  private final PersonalContextBuilder personalContextBuilder;
  private final CurrentResumeLoader resumeLoader;
  private final JobBuddyProperties properties;

  JobRecommendHandler(
      ChatSseEventSender sender,
      ChatPersistenceCoordinator persistence,
      JobRuntimeService jobRuntimeService,
      PersonalContextBuilder personalContextBuilder,
      CurrentResumeLoader resumeLoader,
      JobBuddyProperties properties) {
    this.sender = sender;
    this.persistence = persistence;
    this.jobRuntimeService = jobRuntimeService;
    this.personalContextBuilder = personalContextBuilder;
    this.resumeLoader = resumeLoader;
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
    handle(emitter, sessionId, state, intent, false, "");
  }

  void handle(
      SseEmitter emitter,
      String sessionId,
      ChatSessionState state,
      IntentResult intent,
      boolean replaceLatestJobTurn)
      throws IOException {
    handle(emitter, sessionId, state, intent, replaceLatestJobTurn, "");
  }

  void handle(
      SseEmitter emitter,
      String sessionId,
      ChatSessionState state,
      IntentResult intent,
      boolean replaceLatestJobTurn,
      String rawMessage)
      throws IOException {
    PersonalContext personalContext =
        personalContextBuilder.build(state.tenantId, state.userId, rawMessage, intent, state);
    IntentResult effectiveIntent =
        JobRecommendationCriteriaBuilder.enrich(intent, personalContext, rawMessage);
    state.lastSlots = new LinkedHashMap<String, Object>(effectiveIntent.getSlots());
    Map<String, Object> searchPayload = new LinkedHashMap<String, Object>();
    searchPayload.put("stage", "prepare_cli");
    searchPayload.put("slots", effectiveIntent.getSlots());
    searchPayload.put("contextSources", personalContext.sources());
    searchPayload.put("timeoutSeconds", jobRuntimeService.bossCandidatePoolTimeoutSeconds());
    searchPayload.put("liveEnabled", true);
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus("job_search", "开始搜索岗位", "running", "正在搜索 Boss 岗位，登录失效时会弹出扫码。", searchPayload));
    List<Map<String, Object>> jobs;
    try {
      jobs = jobRuntimeService.recommendJobsFast(effectiveIntent, sessionId, null);
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
    int candidateCount = jobs.size();
    Map<String, Object> gateStart = new LinkedHashMap<String, Object>();
    gateStart.put("candidateCount", candidateCount);
    gateStart.put("minimumScore", properties.getMinimumRecommendedMatchScore());
    gateStart.put("contextSources", personalContext.sources());
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "recommendation_quality_gate",
            "画像与简历预筛",
            "running",
            "正在使用求职画像和当前简历验证候选岗位。",
            gateStart));
    JobRecommendationResult quality;
    try {
      ResumeRecord resume = resumeLoader.loadCurrentResume(state);
      quality = jobRuntimeService.prequalifyRecommendations(resume, jobs, sessionId);
    } catch (RuntimeException e) {
      String reason =
          e.getMessage() == null || e.getMessage().trim().isEmpty() ? "个性化推荐预筛失败。" : e.getMessage();
      sender.sendToolStatus(
          emitter,
          sessionId,
          state,
          toolStatus("recommendation_quality_gate", "推荐质量门未通过", "error", reason, gateStart));
      state.jobs = new java.util.ArrayList<Map<String, Object>>();
      sender.sendAssistant(
          emitter, sessionId, state, "岗位已经召回，但画像与简历匹配预筛未能完成。为避免展示未经验证的岗位，本轮未生成推荐卡片。请稍后重试。");
      persistence.saveStateAsync(state);
      return;
    }
    jobs = quality.getJobs();
    Map<String, Object> gateDetail = new LinkedHashMap<String, Object>();
    gateDetail.put("candidateCount", quality.getCandidateCount());
    gateDetail.put("qualifiedCount", quality.getQualifiedCount());
    gateDetail.put("rejectionReasons", quality.getRejectionReasons());
    gateDetail.put("warnings", quality.getWarnings());
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "recommendation_quality_gate",
            jobs.isEmpty() ? "当前批次无合格岗位" : "画像与简历预筛完成",
            "success",
            jobs.isEmpty() ? "没有岗位同时达到薪资、方向、匹配分和置信度门槛。" : "已有 " + jobs.size() + " 个岗位通过严格推荐门槛。",
            gateDetail));
    state.jobs = jobs;
    Map<String, Object> jobSearchDetail = new LinkedHashMap<String, Object>();
    jobSearchDetail.put("count", jobs.size());
    jobSearchDetail.put("candidateCount", candidateCount);
    jobSearchDetail.put("rejectionReasons", quality.getRejectionReasons());
    jobSearchDetail.put("mode", "profile_resume_strict");
    jobSearchDetail.put(
        "sample",
        jobs.isEmpty() ? Collections.emptyList() : jobs.subList(0, Math.min(3, jobs.size())));
    sender.sendToolStatus(
        emitter,
        sessionId,
        state,
        toolStatus(
            "job_search",
            "岗位搜索完成",
            "success",
            jobs.isEmpty() ? "当前批次没有达到严格推荐门槛的岗位。" : "找到 " + jobs.size() + " 个符合画像和简历的岗位。",
            jobSearchDetail));
    if (jobs.isEmpty()) {
      sender.sendAssistant(
          emitter,
          sessionId,
          state,
          "当前批次没有岗位同时满足目标方向、薪资要求以及画像和简历匹配门槛，因此没有展示低质量岗位。你可以换一批，或明确放宽岗位方向、薪资和经验条件后重新搜索。");
      persistence.saveStateAsync(state);
      return;
    }
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
