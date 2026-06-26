package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.common.config.JobBuddyProperties;
import com.jobbuddy.backend.modules.auth.exception.BossAuthRequiredException;
import com.jobbuddy.backend.modules.chat.dto.request.ChatStreamRequest;
import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.chat.service.ChatSessionStore;
import com.jobbuddy.backend.modules.chat.service.ChatSseService;
import com.jobbuddy.backend.modules.chat.service.IntentService;
import com.jobbuddy.backend.modules.chat.service.JobRuntimeService;
import com.jobbuddy.backend.modules.chat.util.RuntimeRequestBuilder;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.firstPresent;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.stringValue;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.accumulateToolEvent;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.classifyMemoryType;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.compactMatchDetail;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.directiveAction;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.fallbackGeneralResumeMatchAnswer;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.intentFromRuntime;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.intentHint;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.isCapabilityUnavailable;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.manualTargetJobs;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.matchesCapability;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.resumeMatchSummary;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.summarizeRuntimeResult;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.toolStatus;
import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.withSelectedJobContext;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.prompt.model.PersonalContext;
import com.jobbuddy.backend.modules.prompt.service.PersonalContextBuilder;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import com.jobbuddy.backend.modules.system.service.SystemSettingsService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import javax.annotation.PreDestroy;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class ChatSseServiceImpl implements ChatSseService {
    private static final Logger log = LoggerFactory.getLogger(ChatSseServiceImpl.class);
    private final JobRuntimeService jobRuntimeService;
    private final ChatSessionStore sessionStore;
    private final AgentIntegrationService integrationService;
    private final IntentService intentService;
    private final ResumeStorageService resumeStorageService;
    private final PersonalContextBuilder personalContextBuilder;
    private final SystemSettingsService settingsService;
    private final JobBuddyProperties properties;
    // SSE 任务运行时间长（单条流可达 180s），改用有界线程池避免无界 newCachedThreadPool 在高并发或异常堆积时线程膨胀打满资源。
    // 队列满时采用 CallerRunsPolicy 做背压（由提交线程兜底执行），既不静默丢任务，也给系统降速保护的机会。
    private final ExecutorService executor = new ThreadPoolExecutor(
            4, 64, 60L, TimeUnit.SECONDS,
            new ArrayBlockingQueue<Runnable>(256),
            namedThreadFactory("chat-sse"),
            new ThreadPoolExecutor.CallerRunsPolicy());
    // 会话持久化（Postgres/Redis 读写）从 SSE 主线程剥离，统一交给单线程顺序执行，
    // 既保证用户消息/助手消息/工具事件的落库顺序，又避免每次 tool_status 的 DB 写阻塞首包与答案流式。
    private final ExecutorService persistExecutor = Executors.newSingleThreadExecutor(namedThreadFactory("chat-persist"));

    private static java.util.concurrent.ThreadFactory namedThreadFactory(final String prefix) {
        final AtomicInteger seq = new AtomicInteger(1);
        return new java.util.concurrent.ThreadFactory() {
            @Override
            public Thread newThread(Runnable runnable) {
                Thread thread = new Thread(runnable, prefix + "-" + seq.getAndIncrement());
                thread.setDaemon(true);
                return thread;
            }
        };
    }

    public ChatSseServiceImpl(JobRuntimeService jobRuntimeService,
                              ChatSessionStore sessionStore,
                              AgentIntegrationService integrationService,
                              IntentService intentService,
                              ResumeStorageService resumeStorageService,
                              PersonalContextBuilder personalContextBuilder,
                              SystemSettingsService settingsService,
                              JobBuddyProperties properties) {
        this.jobRuntimeService = jobRuntimeService;
        this.sessionStore = sessionStore;
        this.integrationService = integrationService;
        this.intentService = intentService;
        this.resumeStorageService = resumeStorageService;
        this.personalContextBuilder = personalContextBuilder;
        this.settingsService = settingsService;
        this.properties = properties;
    }

    @PreDestroy
    public void shutdownExecutors() {
        executor.shutdownNow();
        // 持久化队列允许已提交任务执行完毕，避免关停时丢失尚未落库的会话消息。
        persistExecutor.shutdown();
    }

    /**
     * 记忆分层：短期记忆即当前会话上下文（chat_message_log，按会话隔离、随会话过期），普通问答只进短期记忆；
     * 长期记忆只承载跨会话稳定的偏好/约束/目标，必须是高信号信息才落库。这里只做轻量分层判断，
     * 真正的去重、容量裁剪与启用开关由 SystemSettingsService 统一控制。
     */
    private void captureLongTermMemory(String message) {
        if (message == null || message.trim().isEmpty()) return;
        String tier = classifyMemoryType(message);
        // 只有判定为长期记忆的稳定信息才写入持久化记忆，普通对话仅留在会话短期记忆中，不污染长期记忆。
        if (tier == null) return;
        try {
            settingsService.writeLocalMemory(tier, message.trim(), "chat");
        } catch (Exception e) {
            // 长期记忆写入失败不阻断问答主链路，但需留痕以便排查记忆丢失。
            log.warn("写入长期记忆失败 tier={}: {}", tier, e.getMessage());
        }
    }

    /** 长期记忆写入涉及文件读写与同步锁，放到后台执行，避免阻塞首包与答案流式链路。 */
    private void captureLongTermMemoryAsync(String message) {
        if (message == null || message.trim().isEmpty()) return;
        executor.submit(new Runnable() {
            @Override
            public void run() {
                captureLongTermMemory(message);
            }
        });
    }

    /** 顺序异步落库助手消息，保证与用户消息的先后顺序，且不阻塞 SSE 主线程。 */
    private void appendMessageAsync(final String sessionId, final String role, final String content, final Map<String, Object> metadata) {
        persistExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    if (metadata == null || metadata.isEmpty()) sessionStore.appendMessage(sessionId, role, content);
                    else sessionStore.appendMessage(sessionId, role, content, metadata);
                } catch (Exception e) {
                    // 异步落库失败不影响已推送给前端的流式内容，但需留痕以便定位消息丢失。
                    log.warn("异步落库消息失败 sessionId={} role={}: {}", sessionId, role, e.getMessage());
                }
            }
        });
    }

    /** 顺序异步替换最近一条岗位助手消息；若历史中尚无岗位消息则回退为追加，保证新会话首屏仍可持久化。 */
    private void replaceLatestJobMessageAsync(final String sessionId, final List<Map<String, Object>> jobs, final List<Map<String, Object>> toolEvents) {
        final List<Map<String, Object>> jobsSnapshot = jobs == null ? Collections.<Map<String, Object>>emptyList() : new java.util.ArrayList<Map<String, Object>>(jobs);
        final List<Map<String, Object>> toolEventsSnapshot = toolEvents == null ? Collections.<Map<String, Object>>emptyList() : new java.util.ArrayList<Map<String, Object>>(toolEvents);
        persistExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    boolean replaced = sessionStore.replaceLatestAssistantJobMessage(sessionId, jobsSnapshot, toolEventsSnapshot);
                    if (!replaced) {
                        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
                        metadata.put("jobCards", jobsSnapshot);
                        if (!toolEventsSnapshot.isEmpty()) {
                            metadata.put("toolEvents", toolEventsSnapshot);
                        }
                        sessionStore.appendMessage(sessionId, "assistant", "", metadata);
                    }
                } catch (Exception e) {
                    log.warn("异步替换岗位消息失败 sessionId={}: {}", sessionId, e.getMessage());
                }
            }
        });
    }

    /**
     * 等待持久化队列排空：persistExecutor 为单线程顺序执行，提交一个空屏障任务并等待其完成，
     * 即代表此前排队的用户消息/助手消息/会话状态落库均已结束。用于在 done 之前保证服务端一致。
     */
    private void awaitPersistFlush() {
        try {
            persistExecutor.submit(new Runnable() {
                @Override
                public void run() {
                }
            }).get(5, java.util.concurrent.TimeUnit.SECONDS);
        } catch (Exception e) {
            // 落库屏障等待超时/中断不阻断 done 下发，仅留痕：可能存在尚未刷盘的会话消息。
            log.warn("等待持久化队列排空异常: {}", e.getMessage());
        }
    }

    /** 顺序异步保存会话状态（槽位/岗位/工具事件等），从 SSE 主线程剥离。 */
    private void saveStateAsync(final ChatSessionState state) {
        if (state == null) return;
        persistExecutor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    sessionStore.save(state);
                } catch (Exception e) {
                    // 会话状态异步保存失败不阻断当前流，但需留痕以便定位状态回看缺失。
                    log.warn("异步保存会话状态失败 sessionId={}: {}", state.sessionId, e.getMessage());
                }
            }
        });
    }

    /** 自动装配求职画像、当前简历、求职进展等个人上下文，工作台问答无需用户重复提供。 */
    private Map<String, Object> buildPersonalContext(String message, IntentResult intent, ChatSessionState state) {
        try {
            PersonalContext context = personalContextBuilder.build(null, message, intent, state);
            return context == null || context.isEmpty() ? Collections.<String, Object>emptyMap() : context.toMap();
        } catch (Exception e) {
            // 个人上下文装配失败时降级为空上下文，不阻断问答，但留痕便于定位画像缺失。
            log.warn("装配个人上下文失败: {}", e.getMessage());
            return Collections.emptyMap();
        }
    }

    public SseEmitter stream(ChatStreamRequest request) {
        final SseEmitter emitter = new SseEmitter(180000L);
        executor.submit(new Runnable() {
            @Override
            public void run() {
                try {
                    handle(request, emitter);
                    // done 之前先把本轮助手消息与会话状态（含推理过程）落库完成，
                    // 确保前端收到 done 后从服务端重载时能拿到完整推理过程，不会被未完成的异步落库覆盖丢失。
                    awaitPersistFlush();
                    send(emitter, "done", Collections.singletonMap("ok", true));
                    emitter.complete();
                } catch (BossAuthRequiredException e) {
                    try {
                        send(emitter, "auth_required", e.getAuthData());
                        send(emitter, "done", Collections.singletonMap("ok", false));
                    } catch (Exception sendError) {
                        // 客户端可能已断开，写 SSE 失败属预期，debug 留痕即可。
                        log.debug("下发 auth_required 事件失败（客户端可能已断开）: {}", sendError.getMessage());
                    }
                    emitter.complete();
                } catch (Exception e) {
                    log.warn("SSE 会话处理异常: {}", e.getMessage(), e);
                    try {
                        send(emitter, "error", Collections.singletonMap("message", e.getMessage()));
                        send(emitter, "done", Collections.singletonMap("ok", false));
                    } catch (Exception sendError) {
                        // 客户端可能已断开，写 SSE 失败属预期，debug 留痕即可。
                        log.debug("下发 error 事件失败（客户端可能已断开）: {}", sendError.getMessage());
                    }
                    emitter.complete();
                }
            }
        });
        return emitter;
    }

    private void handle(ChatStreamRequest request, SseEmitter emitter) throws IOException {
        String sessionId = request.getSessionId() == null || request.getSessionId().isEmpty()
                ? "sess_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12)
                : request.getSessionId();
        // 首包优先：先把会话反馈直接写入 SSE，不做任何 DB/文件 IO，避免用户看到长时间空白。
        send(emitter, "session", Collections.singletonMap("sessionId", sessionId));

        ChatSessionState state = sessionStore.getOrCreate(sessionId);
        // 扫码续跑与换一批都是确定性动作：必须存在上一轮检索条件才能短路，否则优雅回退到正常意图管线。
        boolean resumeAfterAuthRequested = Boolean.TRUE.equals(request.getResumeAfterAuth());
        boolean flipJobsRequested = Boolean.TRUE.equals(request.getFlipJobs());
        boolean hasLastSlots = state.lastSlots != null && !state.lastSlots.isEmpty();
        boolean resumeAfterAuth = resumeAfterAuthRequested && hasLastSlots;
        boolean flipJobs = flipJobsRequested && hasLastSlots;
        // 每轮（含扫码续跑、换一批）都重置本轮过程事件与匹配结果，避免上一轮过程被二次累积重复展示。
        state.toolEvents = new java.util.ArrayList<Map<String, Object>>();
        state.resumeMatch = null;
        if (request.getResumeId() != null && !request.getResumeId().isEmpty()) {
            state.resumeId = request.getResumeId();
        }

        // 聊天岗位卡片上的“分析此岗位”是确定性的单岗位分析入口，直接进入流式分析，
        // 不再走同步弹窗接口，也避免任务理解误把它扩展成整批岗位分析。
        if (isSelectedJobAnalysis(request)) {
            appendMessageAsync(sessionId, "user", request.getMessage(), null);
            handleSelectedJobAnalysis(emitter, sessionId, state, request.getMessage(), request.getSelectedJob());
            return;
        }

        // 扫码登录后的续跑：复用上一轮检索条件，跳过任务理解直接继续岗位搜索，与登录提示合并为同一段连续过程。
        if (resumeAfterAuth) {
            sendToolStatus(emitter, sessionId, state, toolStatus("auth_resume", "登录后继续执行", "success", "Boss 登录完成，继续岗位搜索。", state.lastSlots));
            IntentResult resumedIntent = new IntentResult("job", "job.recommend", 1.0, Collections.<String>emptyList(), "low", false, "call_get_recommend_jobs", state.lastSlots);
            handleJobRecommend(emitter, sessionId, state, resumedIntent);
            return;
        }
        // 换一批：复用上一轮检索条件并翻到候选池下一批，跳过意图预判与任务理解的模型往返，命中缓存即时刷新。
        if (flipJobs) {
            int nextPage = currentBossPage(state.lastSlots) + 1;
            Map<String, Object> flipSlots = new LinkedHashMap<String, Object>(state.lastSlots);
            flipSlots.put("boss_page", nextPage);
            state.lastSlots = flipSlots;
            sendToolStatus(emitter, sessionId, state, toolStatus("job_flip", "换一批", "success", "复用上一轮检索条件，直接翻到第 " + nextPage + " 批岗位。", flipSlots));
            IntentResult flipIntent = new IntentResult("job", "job.recommend", 1.0, Collections.<String>emptyList(), "low", false, "call_get_recommend_jobs", flipSlots);
            handleJobRecommend(emitter, sessionId, state, flipIntent, true);
            return;
        }

        // 仅正常路径提示“任务理解中”：确定性短路（续跑/换一批）不再出现该过程框。
        // 该 running 状态只发流不落库，后续 success 状态会累积到内存状态并在本轮结束时统一落库。
        send(emitter, "tool_status", toolStatus("runtime_understanding", "Runtime 任务理解", "running", "已收到请求，正在理解你的问题并准备作答。", null));

        // 续跑/换一批是对已落库消息的重放或确定性动作，跳过用户消息落库与长程记忆写入，
        // 否则会话历史会出现重复的用户气泡（“登录/换一批后又自动发了一次会话”），破坏“本就是一次会话”的语义。
        if (!resumeAfterAuthRequested && !flipJobsRequested) {
            appendMessageAsync(sessionId, "user", request.getMessage(), null);
            captureLongTermMemoryAsync(request.getMessage());
        }
        // 选中岗位分析：把岗位关键信息注入 Runtime 消息上下文，回答仍走常规问答持久化链路。
        String effectiveMessage = withSelectedJobContext(request.getMessage(), request.getSelectedJob());

        sendToolStatus(emitter, sessionId, state, toolStatus("request_init", "初始化会话", "success", "会话已建立，准备调用 Agent Runtime。", null));

        // 快速预分类：先经过 agent-intent 这层独立、廉价的意图与风险预判，再决定是否进入较重的 runtime 链路。
        // 预判结果作为提示注入 runtime（不替换权威路由），并通过 intent_precheck 事件透出用于观测。
        IntentResult preIntent = intentService.classify(effectiveMessage);
        send(emitter, "intent_precheck", preIntent);
        if (isSafetyGateBlocked(preIntent)) {
            sendToolStatus(emitter, sessionId, state, toolStatus("intent_safety_gate", "高风险拦截", "error",
                    "该请求被独立安全门控判定为高风险并拒绝执行。", preIntent));
            sendAssistant(emitter, sessionId, state, "抱歉，该请求被判定为高风险，已被安全策略拒绝，无法继续执行。",
                    Collections.singletonMap("intentPrecheck", preIntent));
            return;
        }

        Map<String, Object> directive = runTaskUnderstanding(sessionId, effectiveMessage, state, preIntent);
        IntentResult intent = intentFromRuntime(directive);
        state.lastSlots = intent.getSlots();
        send(emitter, "intent", intent);
        sendToolStatus(emitter, sessionId, state, toolStatus("runtime_understanding", "Runtime 任务理解", "success", intent.getDomain() + "/" + intent.getIntent() + "，置信度 " + intent.getConfidence(), directive));

        if (isCapabilityUnavailable(directive)) {
            String answer = stringValue(directive.get("answer"), "该能力尚未接入执行链路。");
            sendToolStatus(emitter, sessionId, state, toolStatus("capability_not_implemented", "能力未实现", "error", answer, directive.get("implementation")));
            sendAssistant(emitter, sessionId, state, answer, Collections.singletonMap("runtimeDirective", directive));
            return;
        }

        handleDirective(emitter, sessionId, effectiveMessage, state, directive, intent);
    }

    /**
     * 安全门控：仅当配置开关开启，且预判为高风险并建议拒绝时拦截。默认关闭，主链路行为与现状一致。
     */
    private boolean isSafetyGateBlocked(IntentResult preIntent) {
        if (!properties.isIntentSafetyGateEnabled() || preIntent == null) return false;
        return "high".equalsIgnoreCase(stringValue(preIntent.getRisk()))
                && "reject".equalsIgnoreCase(stringValue(preIntent.getNextAction()));
    }

    private Map<String, Object> runTaskUnderstanding(String sessionId, String message, ChatSessionState state, IntentResult preIntent) {
        // 任务理解只需意图/能力路由/directive，这里短路 Runtime 图，跳过上下文装配、Tool Search、Planner、合成，
        // 把一次多余的 LLM/工具往返从首字延迟链路上移除；真正的答案合成由后续流式托管调用完成。
        Map<String, Object> request = RuntimeRequestBuilder
                .forEntrypoint(sessionId, message, "chat.stream")
                .budget(1, 0, 1)
                .metadata("understanding_only", true)
                .metadata("intent_hint", intentHint(preIntent))
                .metadata("resume_id", state == null ? null : state.resumeId)
                .metadata("previous_slots", state == null || state.lastSlots == null ? Collections.emptyMap() : state.lastSlots)
                .metadata("current_jobs_count", state == null || state.jobs == null ? 0 : state.jobs.size())
                .metadata("boss_live_enabled", true)
                .metadata("personal_context", buildPersonalContext(message, null, state))
                .build();
        Map<String, Object> result = integrationService.runRuntime(request);
        Map<String, Object> directive = RuntimeRequestBuilder.extractDirective(result);
        if (directive == null || directive.isEmpty()) {
            // 区分两种失败：result 为空说明 Runtime 不可达或返回空响应；result 非空但缺 directive
            // 说明 Runtime 应答但任务理解结构异常。统一报 "不可用" 会掩盖真实根因，影响排障。
            if (result == null || result.isEmpty()) {
                throw new IllegalStateException("Agent Runtime 未返回结果，请检查服务可用性与 runtime-url 配置。");
            }
            throw new IllegalStateException("Agent Runtime 任务理解结果缺少 directive：" + summarizeRuntimeResult(result));
        }
        directive.put("runtime_result", result == null ? Collections.emptyMap() : result);
        return directive;
    }

    private void handleDirective(SseEmitter emitter, String sessionId, String rawMessage, ChatSessionState state, Map<String, Object> directive, IntentResult intent) throws IOException {
        String action = directiveAction(directive, intent);
        if (matchesCapability(action, intent, "call_login", "trigger_boss_login", "auth.login")) {
            Map<String, Object> login = jobRuntimeService.startBossLogin(sessionId);
            if (!Boolean.TRUE.equals(login.get("authRequired"))) {
                sendAssistant(emitter, sessionId, state, "Boss 登录态有效，可继续筛选岗位或查看详情。", Collections.singletonMap("runtimeDirective", directive));
                return;
            }
            throw new BossAuthRequiredException("Boss 直聘未登录，请先完成二维码登录。", login);
        }
        if (matchesCapability(action, intent, "call_get_recommend_jobs", "run_job_recommend", "job.recommend")) {
            handleJobRecommend(emitter, sessionId, state, intent);
            return;
        }
        if (matchesCapability(action, intent, "call_resume_match", "run_resume_match", "resume.match")) {
            handleResumeMatch(emitter, sessionId, state, intent, rawMessage);
            return;
        }
        if (matchesCapability(action, intent, "call_resume_analyze", "run_resume_analyze", "resume.analyze")) {
            handleResumeAnalyze(emitter, sessionId, state);
            return;
        }
        handleRuntimeManagedTask(emitter, sessionId, rawMessage, state, directive, intent);
    }

    private boolean isSelectedJobAnalysis(ChatStreamRequest request) {
        return request != null
                && request.getSelectedJob() != null
                && !request.getSelectedJob().isEmpty()
                && !Boolean.TRUE.equals(request.getResumeAfterAuth())
                && !Boolean.TRUE.equals(request.getFlipJobs());
    }

    private void handleSelectedJobAnalysis(SseEmitter emitter, String sessionId, ChatSessionState state, String rawMessage, Map<String, Object> selectedJob) throws IOException {
        Map<String, Object> startDetail = new LinkedHashMap<String, Object>();
        startDetail.put("job", compactSelectedJob(selectedJob));
        sendToolStatus(emitter, sessionId, state, toolStatus("selected_job_analysis", "分析此岗位", "running", "正在读取当前简历并生成岗位匹配分析。", startDetail));

        ResumeRecord resume = loadCurrentResume(state);
        if (resume == null) {
            sendAssistant(emitter, sessionId, state, "请先选择或上传 PDF 简历，再分析此岗位与简历的匹配度。", Collections.singletonMap("selectedJob", compactSelectedJob(selectedJob)));
            return;
        }

        Map<String, Object> resumeSummary = resumeStorageService.summarize(resume);
        String prompt = buildSelectedJobAnalysisPrompt(rawMessage, selectedJob, resumeSummary);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("job_buddy", true);
        metadata.put("entrypoint", "chat.selected_job_analysis");
        metadata.put("runtime_execute", true);
        metadata.put("resume_id", state == null ? null : state.resumeId);
        metadata.put("selected_job", compactSelectedJob(selectedJob));
        metadata.put("personal_context", buildPersonalContext(rawMessage, null, state));

        final StringBuilder buffer = new StringBuilder();
        final StringBuilder reasoningBuffer = new StringBuilder();
        final String assistantId = "assistant_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> request = buildRuntimeManagedRequest(sessionId, prompt, "job-buddy", metadata, true);
        Map<String, Object> runtimeResult = integrationService.runRuntimeStream(request, new java.util.function.Consumer<String>() {
            @Override
            public void accept(String piece) {
                if (piece == null || piece.isEmpty()) return;
                buffer.append(piece);
                try {
                    sendMessageDelta(emitter, sessionId, assistantId, piece);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }, new java.util.function.Consumer<String>() {
            @Override
            public void accept(String piece) {
                if (piece == null || piece.isEmpty()) return;
                reasoningBuffer.append(piece);
                try {
                    sendReasoningDelta(emitter, sessionId, assistantId, piece);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });

        String streamError = stringValue(firstPresent(runtimeResult, "error", "errorMessage"));
        boolean streamFailed = !streamError.isEmpty();
        String reasoning = stringValue(runtimeResult.get("reasoning"));
        if (reasoning.isEmpty()) reasoning = reasoningBuffer.toString().trim();
        String answer = stringValue(firstPresent(runtimeResult, "answer", "final_answer"));
        if (answer.isEmpty()) answer = buffer.toString().trim();
        // 部分推理模型在该短路路径下只返回 reasoning 增量而 final answer 为空。
        // 选中岗位分析的 reasoning 内容本身就是面向用户的结构化分析，因此兜底写回主气泡，避免最终助手消息空白。
        if (answer.isEmpty() && !reasoning.isEmpty()) answer = reasoning;
        if (answer.isEmpty()) {
            answer = "岗位分析暂未生成有效内容，请稍后重试。";
        }

        Map<String, Object> resultDetail = new LinkedHashMap<String, Object>();
        resultDetail.put("status", runtimeResult.get("status"));
        resultDetail.put("runId", firstPresent(runtimeResult, "run_id", "runId"));
        resultDetail.put("stopReason", firstPresent(runtimeResult, "stop_reason", "stopReason"));
        if (streamFailed) resultDetail.put("error", streamError);
        sendToolStatus(emitter, sessionId, state, toolStatus(
                "selected_job_analysis",
                streamFailed ? "岗位分析中断" : "岗位分析完成",
                streamFailed ? "error" : "success",
                streamFailed ? "Runtime 流式中断，已展示内容可能不完整。" : "已完成当前岗位与简历的匹配分析。",
                resultDetail));

        Map<String, Object> finalMeta = new LinkedHashMap<String, Object>();
        finalMeta.put("assistantId", assistantId);
        finalMeta.put("selectedJob", compactSelectedJob(selectedJob));
        if (!runtimeResult.isEmpty()) finalMeta.put("runtimeResult", resultDetail);
        if (!reasoning.isEmpty()) finalMeta.put("reasoning", reasoning);
        sendAssistant(emitter, sessionId, state, answer, finalMeta);
    }

    private Map<String, Object> compactSelectedJob(Map<String, Object> job) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        if (job == null) return result;
        putSelectedJobField(result, "jobName", job, "jobName", "job_name", "title", "name");
        putSelectedJobField(result, "company", job, "brandName", "companyName", "company");
        putSelectedJobField(result, "salary", job, "salaryDesc", "salary", "salaryText", "jobSalary");
        putSelectedJobField(result, "city", job, "cityName", "city", "location", "areaDistrict");
        putSelectedJobField(result, "experience", job, "jobExperience", "experience", "experienceName");
        putSelectedJobField(result, "degree", job, "jobDegree", "education", "degree", "degreeName");
        putSelectedJobField(result, "description", job, "jobDescription", "description", "postDescription", "jobDesc", "jobSecText", "detailText", "jobRequire");
        return result;
    }

    private void putSelectedJobField(Map<String, Object> target, String field, Map<String, Object> source, String... keys) {
        for (String key : keys) {
            Object value = source.get(key);
            String text = normalizeSelectedJobText(value);
            if (!text.isEmpty()) {
                target.put(field, text.length() > 1200 ? text.substring(0, 1200) : text);
                return;
            }
        }
    }

    private String normalizeSelectedJobText(Object value) {
        if (value == null) return "";
        String raw = String.valueOf(value).replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder builder = new StringBuilder();
        for (String line : raw.split("\\n+")) {
            String text = line == null ? "" : line.replace('\t', ' ').trim().replaceAll(" {2,}", " ");
            if (text.isEmpty() || "null".equalsIgnoreCase(text)) continue;
            if (builder.length() > 0) builder.append('\n');
            builder.append(text);
        }
        return builder.toString();
    }

    private String buildSelectedJobAnalysisPrompt(String rawMessage, Map<String, Object> selectedJob, Map<String, Object> resumeSummary) {
        Map<String, Object> job = compactSelectedJob(selectedJob);
        StringBuilder builder = new StringBuilder();
        builder.append("请对用户选中的单个岗位与当前简历做流式匹配分析。\n");
        builder.append("要求：先给出 0-100 匹配评分和一句结论，再分段输出匹配优势、主要差距、面试准备建议和是否建议投递。\n");
        builder.append("不要输出 JSON，不要等待全部分析完成后再集中输出，按自然语言逐步展开。\n");
        builder.append("用户请求：").append(rawMessage == null ? "分析此岗位" : rawMessage).append("\n\n");
        builder.append("岗位信息：\n");
        for (Map.Entry<String, Object> entry : job.entrySet()) {
            builder.append("- ").append(entry.getKey()).append(": ").append(entry.getValue()).append('\n');
        }
        builder.append("\n当前简历摘要：\n").append(resumeSummary == null ? "" : String.valueOf(resumeSummary)).append('\n');
        return builder.toString();
    }

    private void handleResumeMatch(SseEmitter emitter, String sessionId, ChatSessionState state, IntentResult intent, String rawMessage) throws IOException {
        ResumeRecord resume = loadCurrentResume(state);
        if (resume == null) {
            sendAssistant(emitter, sessionId, state, "请先选择或上传 PDF 简历，再分析岗位匹配度。");
            return;
        }
        String targetDescription = stringValue(firstPresent(intent.getSlots(), "target_job_description", "jd", "job_description"));
        String targetRole = stringValue(firstPresent(intent.getSlots(), "role", "target_role"), rawMessage);
        List<Map<String, Object>> jobs = state.jobs == null || state.jobs.isEmpty()
                ? manualTargetJobs(targetRole, targetDescription, intent.getSlots())
                : state.jobs;
        if (jobs.isEmpty()) {
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("basis", "general_role_knowledge");
            detail.put("targetRole", targetRole);
            detail.put("slots", intent.getSlots());
            sendToolStatus(emitter, sessionId, state, toolStatus("resume_match", "通用岗位分析", "running", "缺少目标 JD 或岗位列表，将基于通用岗位要求做参考分析。", detail));
            Map<String, Object> general = streamGeneralResumeMatchAnswer(emitter, sessionId, rawMessage, resume, targetRole, targetDescription, intent.getSlots());
            String answer = stringValue(general.get("answer"));
            if (answer.isEmpty()) answer = fallbackGeneralResumeMatchAnswer(resume, targetRole);
            Map<String, Object> metadata = new LinkedHashMap<String, Object>();
            metadata.put("resumeMatch", general);
            metadata.put("matchBasis", "general_role_knowledge");
            Object assistantId = general.remove("assistantId");
            if (assistantId != null) metadata.put("assistantId", assistantId);
            Object reasoning = general.remove("reasoning");
            if (reasoning != null && !stringValue(reasoning).isEmpty()) metadata.put("reasoning", reasoning);
            sendToolStatus(emitter, sessionId, state, toolStatus("resume_match", "通用岗位分析完成", "success", "参考分析已完成。", metadata));
            sendAssistant(emitter, sessionId, state, answer, metadata);
            return;
        }
        sendToolStatus(emitter, sessionId, state, toolStatus("resume_match", "简历匹配分析", "running", "正在基于真实岗位或用户 JD 评估简历匹配。", intent.getSlots()));
        Map<String, Object> match = jobRuntimeService.matchResume(resume, jobs, sessionId);
        if (!match.containsKey("target")) match.put("target", targetDescription.isEmpty() ? targetRole : targetDescription);
        // 匹配结果写入内存状态，随本轮助手消息一并异步落库，避免单独的同步写阻塞 SSE。
        state.resumeMatch = match;
        sendToolStatus(emitter, sessionId, state, toolStatus("resume_match", "简历匹配完成", "success", "简历匹配已完成。", compactMatchDetail(match)));
        send(emitter, "resume_match", match);
        sendAssistant(emitter, sessionId, state, resumeMatchSummary(match), Collections.singletonMap("resumeMatch", match));
    }

    /** 通用简历匹配分析：流式优先逐字下发，流式无产出时回退非流式托管调用，最终回退本地模板。 */
    private Map<String, Object> streamGeneralResumeMatchAnswer(SseEmitter emitter, String sessionId, String rawMessage, ResumeRecord resume, String targetRole, String targetDescription, Map<String, Object> slots) {
        Map<String, Object> response = new LinkedHashMap<String, Object>();
        Map<String, Object> resumeSummary = resumeStorageService.summarize(resume);
        String role = stringValue(targetRole, stringValue(targetDescription, "目标岗位"));
        String prompt = "请基于通用岗位画像，而不是具体 JD，对当前简历与目标方向做参考匹配分析。\n"
                + "目标方向：" + role + "\n"
                + "用户原始问题：" + stringValue(rawMessage) + "\n"
                + "已知槽位：" + String.valueOf(slots == null ? Collections.emptyMap() : slots) + "\n"
                + "简历摘要：" + String.valueOf(resumeSummary) + "\n\n"
                + "要求：1）基于通用岗位画像给出参考判断；2）不输出精确匹配分；3）输出匹配结论、主要优势、明显短板、面试准备建议和简历修改建议；"
                + "4）简历摘要信息不足时，说明需要补充的信息。";
        // runtime_execute 让 Runtime 跳过重复任务理解直达流式合成，与托管问答路径保持一致的首字延迟。
        Map<String, Object> extraMetadata = new LinkedHashMap<String, Object>();
        extraMetadata.put("runtime_execute", true);
        extraMetadata.put("entrypoint", "resume.match.general");
        final String assistantId = "assistant_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        final StringBuilder buffer = new StringBuilder();
        final StringBuilder reasoningBuffer = new StringBuilder();
        try {
            Map<String, Object> request = buildRuntimeManagedRequest(sessionId, prompt, "default", extraMetadata, true);
            Map<String, Object> runtimeResult = integrationService.runRuntimeStream(request, new java.util.function.Consumer<String>() {
                @Override
                public void accept(String piece) {
                    if (piece == null || piece.isEmpty()) return;
                    buffer.append(piece);
                    try {
                        sendMessageDelta(emitter, sessionId, assistantId, piece);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            }, new java.util.function.Consumer<String>() {
                @Override
                public void accept(String piece) {
                    if (piece == null || piece.isEmpty()) return;
                    reasoningBuffer.append(piece);
                    try {
                        sendReasoningDelta(emitter, sessionId, assistantId, piece);
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                }
            });
            String answer = stringValue(firstPresent(runtimeResult, "answer", "final_answer"));
            if (answer.isEmpty()) answer = buffer.toString().trim();
            String reasoning = stringValue(runtimeResult.get("reasoning"));
            if (reasoning.isEmpty()) reasoning = reasoningBuffer.toString().trim();
            if (answer.isEmpty()) {
                // 流式无任何产出时回退非流式托管调用，避免偶发流式中断直接对用户报错。
                Map<String, Object> fallback = runRuntimeManagedAnswerWithProfile(sessionId, prompt, "default", Collections.<String, Object>emptyMap());
                answer = stringValue(firstPresent(fallback, "answer", "final_answer"));
                if (!answer.isEmpty()) runtimeResult = fallback;
            }
            if (!answer.isEmpty()) {
                response.put("answer", answer);
                if (!reasoning.isEmpty()) response.put("reasoning", reasoning);
                response.put("assistantId", assistantId);
                response.put("runtimeResult", runtimeResult);
                response.put("target", role);
                response.put("resumeSummary", resumeSummary);
                response.put("basis", "general_role_knowledge");
                return response;
            }
        } catch (RuntimeException ignored) {
            response.put("runtime_error", ignored.getMessage());
        }
        response.put("answer", fallbackGeneralResumeMatchAnswer(resume, role));
        response.put("target", role);
        response.put("resumeSummary", resumeSummary);
        response.put("basis", "general_role_knowledge_fallback");
        return response;
    }

    private void handleResumeAnalyze(SseEmitter emitter, String sessionId, ChatSessionState state) throws IOException {
        ResumeRecord resume = loadCurrentResume(state);
        if (resume == null) {
            sendAssistant(emitter, sessionId, state, "请先选择或上传 PDF 简历，再分析简历。");
            return;
        }
        sendToolStatus(emitter, sessionId, state, toolStatus("resume_analyze", "解析当前简历", "running", "正在解析当前简历。", resume.getResumeId()));
        ResumeRecord analyzed = resumeStorageService.analyzeSync(resume.getResumeId(), sessionId);
        Map<String, Object> summary = resumeStorageService.summarize(analyzed);
        sendToolStatus(emitter, sessionId, state, toolStatus("resume_analyze", "简历解析完成", "success", "简历结构化信息已读取。", summary));
        sendAssistant(emitter, sessionId, state, "已解析当前简历，可继续生成分析建议。", Collections.singletonMap("resumeSummary", summary));
    }

    private ResumeRecord loadCurrentResume(ChatSessionState state) {
        if (state == null || state.resumeId == null || state.resumeId.trim().isEmpty()) return null;
        ResumeRecord record = resumeStorageService.get(state.resumeId);
        if (record == null) return null;
        if (record.getParsed() == null || record.getParsed().isEmpty()) {
            record = resumeStorageService.parseSync(state.resumeId, state.sessionId);
        }
        return record;
    }

    private void handleRuntimeManagedTask(SseEmitter emitter, String sessionId, String rawMessage, ChatSessionState state, Map<String, Object> directive, IntentResult intent) throws IOException {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("directive", directive == null ? Collections.emptyMap() : directive);
        detail.put("intent", intent);
        sendToolStatus(emitter, sessionId, state, toolStatus("runtime_managed", "Runtime 托管任务", "running", "Agent Runtime 正在生成结果。", detail));

        Map<String, Object> metadata = runtimeManagedMetadata(rawMessage, state, directive, intent);
        Map<String, Object> request = buildRuntimeManagedRequest(sessionId, rawMessage, "job-buddy", metadata, true);
        final StringBuilder buffer = new StringBuilder();
        final StringBuilder reasoningBuffer = new StringBuilder();
        final String assistantId = "assistant_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> runtimeResult = integrationService.runRuntimeStream(request, new java.util.function.Consumer<String>() {
            @Override
            public void accept(String piece) {
                if (piece == null || piece.isEmpty()) return;
                buffer.append(piece);
                try {
                    sendMessageDelta(emitter, sessionId, assistantId, piece);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        }, new java.util.function.Consumer<String>() {
            @Override
            public void accept(String piece) {
                if (piece == null || piece.isEmpty()) return;
                reasoningBuffer.append(piece);
                try {
                    // 逐字下发推理过程，思考阶段即给到前端可见反馈，缩短首字空白感知。
                    sendReasoningDelta(emitter, sessionId, assistantId, piece);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        });

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
            Map<String, Object> fallback = runRuntimeManagedAnswer(sessionId, rawMessage, state, directive, intent);
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
            String reason = streamFailed
                    ? "Runtime 流式中断且无产出：" + streamError
                    : "Runtime 未返回可展示回答，请检查能力接入、LLM 配置和工具预算。";
            sendToolStatus(emitter, sessionId, state, toolStatus("runtime_managed", "Runtime 托管任务未产出", "error", reason, resultDetail));
            sendAssistant(emitter, sessionId, state, reason, runtimeResult.isEmpty() ? null : Collections.singletonMap("runtimeResult", resultDetail));
            return;
        }
        if (streamFailed) {
            // 已流式展示部分内容但中途报错：保留已下发文本，但以错误态提示结果可能不完整，避免把残缺回答当成功。
            String reason = "Runtime 流式中断，已展示内容可能不完整：" + streamError;
            sendToolStatus(emitter, sessionId, state, toolStatus("runtime_managed", "Runtime 托管任务中断", "error", reason, resultDetail));
        } else {
            sendToolStatus(emitter, sessionId, state, toolStatus("runtime_managed", "Runtime 托管任务完成", "success", "Runtime 已返回回答。", resultDetail));
        }
        Map<String, Object> finalMeta = new LinkedHashMap<String, Object>();
        finalMeta.put("assistantId", assistantId);
        if (!runtimeResult.isEmpty()) finalMeta.put("runtimeResult", resultDetail);
        // 推理过程随助手消息一并落库，刷新或切换会话后仍可回看本轮的思考过程。
        if (!reasoning.isEmpty()) finalMeta.put("reasoning", reasoning);
        sendAssistant(emitter, sessionId, state, answer, finalMeta);
    }

    private Map<String, Object> runRuntimeManagedAnswer(String sessionId, String message, ChatSessionState state, Map<String, Object> directive, IntentResult intent) {
        Map<String, Object> metadata = runtimeManagedMetadata(message, state, directive, intent);
        return runRuntimeManagedAnswerWithProfile(sessionId, message, "job-buddy", metadata);
    }

    private Map<String, Object> runRuntimeManagedAnswerWithProfile(String sessionId, String message, String profile, Map<String, Object> extraMetadata) {
        return integrationService.runRuntime(buildRuntimeManagedRequest(sessionId, message, profile, extraMetadata, false));
    }

    /** 构造 Runtime 托管请求体，供流式与非流式入口共用，保证消息/预算/元数据一致。 */
    private Map<String, Object> buildRuntimeManagedRequest(String sessionId, String message, String profile, Map<String, Object> extraMetadata, boolean stream) {
        Map<String, Object> request = new LinkedHashMap<String, Object>();
        List<Map<String, Object>> messages = new java.util.ArrayList<Map<String, Object>>();
        Map<String, Object> user = new LinkedHashMap<String, Object>();
        user.put("role", "user");
        user.put("content", message == null ? "" : message);
        messages.add(user);
        request.put("messages", messages);
        request.put("session_id", sessionId);
        request.put("stream", stream);
        Map<String, Object> budget = new LinkedHashMap<String, Object>();
        budget.put("max_turns", properties.getRuntimeMaxTurns());
        budget.put("max_tool_calls", properties.getRuntimeMaxToolCalls());
        budget.put("max_failures", properties.getRuntimeMaxFailures());
        request.put("budget", budget);
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("profile", profile);
        if (extraMetadata != null) metadata.putAll(extraMetadata);
        request.put("metadata", metadata);
        return request;
    }

    private Map<String, Object> runtimeManagedMetadata(String message, ChatSessionState state, Map<String, Object> directive, IntentResult intent) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        metadata.put("job_buddy", true);
        metadata.put("entrypoint", "chat.ask");
        metadata.put("runtime_execute", true);
        metadata.put("resume_id", state == null ? null : state.resumeId);
        metadata.put("previous_slots", state == null || state.lastSlots == null ? Collections.emptyMap() : state.lastSlots);
        metadata.put("current_jobs_count", state == null || state.jobs == null ? 0 : state.jobs.size());
        metadata.put("boss_live_enabled", true);
        metadata.put("personal_context", buildPersonalContext(message, intent, state));
        metadata.put("upstream_directive", directive == null ? Collections.emptyMap() : directive);
        return metadata;
    }

    /** 读取上一轮检索条件中的候选池页码，缺省或非法时视为第 1 批，供换一批确定性翻页递增使用。 */
    private int currentBossPage(Map<String, Object> slots) {
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

    private void handleJobRecommend(SseEmitter emitter, String sessionId, ChatSessionState state, IntentResult intent) throws IOException {
        handleJobRecommend(emitter, sessionId, state, intent, false);
    }

    private void handleJobRecommend(SseEmitter emitter, String sessionId, ChatSessionState state, IntentResult intent, boolean replaceLatestJobTurn) throws IOException {
        Map<String, Object> searchPayload = new LinkedHashMap<String, Object>();
        searchPayload.put("stage", "prepare_cli");
        searchPayload.put("slots", intent.getSlots());
        searchPayload.put("timeoutSeconds", jobRuntimeService.bossCandidatePoolTimeoutSeconds());
        searchPayload.put("liveEnabled", true);
        sendToolStatus(emitter, sessionId, state, toolStatus("job_search", "开始搜索岗位", "running", "正在搜索 Boss 岗位，登录失效时会弹出扫码。", searchPayload));
        List<Map<String, Object>> jobs;
        try {
            jobs = jobRuntimeService.recommendJobsFast(intent, sessionId, null);
        } catch (BossAuthRequiredException e) {
            String reason = e.getMessage() == null || e.getMessage().trim().isEmpty()
                    ? "Boss 登录态失效。"
                    : e.getMessage();
            Map<String, Object> authData = e.getAuthData() == null ? Collections.<String, Object>emptyMap() : e.getAuthData();
            Map<String, Object> detail = new LinkedHashMap<String, Object>();
            detail.put("reason", reason);
            detail.put("authData", authData);
            sendToolStatus(emitter, sessionId, state, toolStatus("job_search", "需要登录 Boss 直聘", "error", reason, detail));
            throw e;
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null || e.getMessage().trim().isEmpty() ? "岗位搜索失败" : e.getMessage();
            sendToolStatus(emitter, sessionId, state, toolStatus("job_search", "岗位搜索失败", "error", reason, searchPayload));
            sendAssistant(emitter, sessionId, state, reason);
            return;
        }
        int limit = Math.max(1, properties.getMaxJobsPerRecommend());
        jobs = jobs.size() > limit ? new java.util.ArrayList<Map<String, Object>>(jobs.subList(0, limit)) : jobs;
        state.jobs = jobs;
        Map<String, Object> jobSearchDetail = new LinkedHashMap<String, Object>();
        jobSearchDetail.put("count", jobs.size());
        jobSearchDetail.put("mode", "live");
        jobSearchDetail.put("sample", jobs.isEmpty() ? Collections.emptyList() : jobs.subList(0, Math.min(3, jobs.size())));
        sendToolStatus(emitter, sessionId, state, toolStatus("job_search", "岗位搜索完成", "success", "找到 " + jobs.size() + " 个候选岗位。", jobSearchDetail));
        send(emitter, "job_cards", jobs);
        // 普通推荐保留独立助手消息，表示一轮新的用户意图；换一批是同一轮检索条件下的确定性翻页，
        // 应直接替换最近的岗位卡片消息，避免聊天区和历史回放里出现“换一批又新开一轮会话”的错觉。
        if (!jobs.isEmpty()) {
            if (replaceLatestJobTurn) {
                replaceLatestJobMessageAsync(sessionId, jobs, state.toolEvents);
            } else {
                Map<String, Object> turnMeta = new LinkedHashMap<String, Object>();
                turnMeta.put("jobCards", jobs);
                if (state.toolEvents != null && !state.toolEvents.isEmpty()) {
                    turnMeta.put("toolEvents", new java.util.ArrayList<Map<String, Object>>(state.toolEvents));
                }
                appendMessageAsync(sessionId, "assistant", "", turnMeta);
            }
        }
        // 岗位列表与本轮推理过程统一异步落库，确保扫码搜索路径下首屏卡片即时呈现、不被持久化阻塞。
        saveStateAsync(state);
    }

    private void sendAssistant(SseEmitter emitter, String sessionId, ChatSessionState state, String value) throws IOException {
        sendAssistant(emitter, sessionId, state, value, null);
    }

    private void sendAssistant(SseEmitter emitter, String sessionId, ChatSessionState state, String value, Map<String, Object> metadata) throws IOException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("role", "assistant");
        data.put("content", value);
        data.put("createdAt", java.time.Instant.now().toString());
        if (metadata != null && !metadata.isEmpty()) data.putAll(metadata);
        // 把本轮推理过程随助手消息一并落库，刷新或切换会话后仍可查看该轮的思考与工具执行过程。
        // 推理步骤已在本轮累积到内存会话状态，这里直接取用，无需再读库。
        Map<String, Object> persistMeta = metadata == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(metadata);
        List<Map<String, Object>> toolEventsSnapshot = null;
        if (state != null && state.toolEvents != null && !state.toolEvents.isEmpty()) {
            toolEventsSnapshot = new java.util.ArrayList<Map<String, Object>>(state.toolEvents);
            if (!persistMeta.containsKey("toolEvents")) persistMeta.put("toolEvents", toolEventsSnapshot);
        }
        // 终态 message 同时携带本轮推理过程，前端据此把推理步骤绑定到最终助手消息，
        // 避免后续从服务端重载时因落库尚未完成而把内存里的推理过程覆盖丢失。
        if (toolEventsSnapshot != null && !data.containsKey("toolEvents")) {
            data.put("toolEvents", toolEventsSnapshot);
        }
        // 先把答案推到前端，再异步落库（助手消息 + 会话状态含推理过程），避免持久化 IO 阻塞用户感知。
        send(emitter, "message", data);
        appendMessageAsync(sessionId, "assistant", value, persistMeta.isEmpty() ? null : persistMeta);
        saveStateAsync(state);
    }

    /** 下发答案 Token 增量，前端按 assistantId 追加到在途助手消息，不落库（终态 message 落库）。 */
    private void sendMessageDelta(SseEmitter emitter, String sessionId, String assistantId, String delta) throws IOException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("sessionId", sessionId);
        data.put("assistantId", assistantId);
        data.put("delta", delta);
        send(emitter, "message_delta", data);
    }

    /** 下发推理过程增量，前端按 assistantId 追加到在途助手消息的推理过程，不落库（终态 message 携带完整推理过程落库）。 */
    private void sendReasoningDelta(SseEmitter emitter, String sessionId, String assistantId, String delta) throws IOException {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("sessionId", sessionId);
        data.put("assistantId", assistantId);
        data.put("delta", delta);
        send(emitter, "reasoning_delta", data);
    }

    private void sendToolStatus(SseEmitter emitter, String sessionId, ChatSessionState state, Map<String, Object> status) throws IOException {
        // 先把工具状态推给前端，再累积到内存会话状态（本轮结束统一落库），不在主线程做 DB 写。
        send(emitter, "tool_status", status);
        accumulateToolEvent(state, status);
    }

    private void send(SseEmitter emitter, String event, Object data) throws IOException {
        emitter.send(SseEmitter.event().name(event).data(data));
    }
}
