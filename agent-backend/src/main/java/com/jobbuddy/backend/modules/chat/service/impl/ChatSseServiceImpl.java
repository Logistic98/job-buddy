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
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.booleanValue;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.doubleValue;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.firstPresent;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.stringValue;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.truncate;
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

    /**
     * 判定一条用户消息是否值得写入长期记忆，并返回长期记忆类型；普通对话返回 null（只进短期记忆）。
     * 约束类（排除/不要/不考虑/约束）优先于偏好类（偏好/优先/目标/期望/希望/喜欢）。
     */
    private String classifyMemoryType(String message) {
        String text = message == null ? "" : message;
        if (text.contains("排除") || text.contains("不要") || text.contains("不考虑") || text.contains("约束")) return "constraint";
        if (text.contains("偏好") || text.contains("优先") || text.contains("目标") || text.contains("期望")
                || text.contains("希望") || text.contains("喜欢") || text.contains("倾向")) return "preference";
        return null;
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

    /**
     * 工具事件累积到内存会话状态（按 id 合并、过滤记忆噪声步骤），供本轮答案落库与刷新后回看推理过程使用。
     * 这里不直接写库，避免每个 tool_status 都触发一次 DB 写造成串行阻塞。
     */
    private void accumulateToolEvent(ChatSessionState state, Map<String, Object> event) {
        if (state == null || event == null || event.get("id") == null) return;
        if (state.toolEvents == null) state.toolEvents = new java.util.ArrayList<Map<String, Object>>();
        if (isMemoryNoiseEvent(event)) return;
        String id = String.valueOf(event.get("id"));
        for (int i = 0; i < state.toolEvents.size(); i++) {
            Map<String, Object> existing = state.toolEvents.get(i);
            if (id.equals(String.valueOf(existing.get("id")))) {
                Map<String, Object> merged = new LinkedHashMap<String, Object>(existing);
                merged.putAll(event);
                state.toolEvents.set(i, merged);
                return;
            }
        }
        state.toolEvents.add(event);
    }

    /** 与 ChatSessionStore 保持一致的记忆噪声判定：只按稳定标识字段 id/name 过滤，避免展示文案中出现“记忆”导致误删。 */
    private boolean isMemoryNoiseEvent(Map<String, Object> event) {
        if (event == null) return false;
        StringBuilder builder = new StringBuilder();
        for (String key : new String[]{"id", "name"}) {
            Object value = event.get(key);
            if (value != null) builder.append(' ').append(String.valueOf(value).toLowerCase(java.util.Locale.ROOT));
        }
        String text = builder.toString();
        return text.contains("memory") || text.contains("记忆");
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
        // 首包优先：先把会话与“处理中”反馈直接写入 SSE，不做任何 DB/文件 IO，避免用户看到长时间空白。
        // 这里的 running 状态只发流不落库，后续 success 状态会累积到内存状态并在本轮结束时统一落库。
        send(emitter, "session", Collections.singletonMap("sessionId", sessionId));
        send(emitter, "tool_status", toolStatus("runtime_understanding", "Runtime 任务理解", "running", "已收到请求，正在理解你的问题并准备作答。", null));

        ChatSessionState state = sessionStore.getOrCreate(sessionId);
        boolean resumeAfterAuth = Boolean.TRUE.equals(request.getResumeAfterAuth());
        if (!resumeAfterAuth) {
            state.toolEvents = new java.util.ArrayList<Map<String, Object>>();
            state.resumeMatch = null;
        }

        if (resumeAfterAuth && state.lastSlots != null && !state.lastSlots.isEmpty()) {
            sendToolStatus(emitter, sessionId, state, toolStatus("auth_resume", "登录后继续执行", "success", "Boss 登录完成，继续岗位搜索。", state.lastSlots));
            IntentResult resumedIntent = new IntentResult("job", "job.recommend", 1.0, Collections.<String>emptyList(), "low", false, "call_get_recommend_jobs", state.lastSlots);
            handleJobRecommend(emitter, sessionId, state, resumedIntent);
            return;
        }

        appendMessageAsync(sessionId, "user", request.getMessage(), null);
        captureLongTermMemoryAsync(request.getMessage());
        if (request.getResumeId() != null && !request.getResumeId().isEmpty()) {
            state.resumeId = request.getResumeId();
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

    private String withSelectedJobContext(String message, Map<String, Object> selectedJob) {
        if (selectedJob == null || selectedJob.isEmpty()) return message;
        StringBuilder builder = new StringBuilder(message == null ? "" : message);
        builder.append("\n\n[用户选中的目标岗位信息，请仅针对该岗位作答]\n");
        appendJobField(builder, "岗位名称", selectedJob, "jobName", "job_name", "title");
        appendJobField(builder, "公司", selectedJob, "brandName", "companyName", "company");
        appendJobField(builder, "薪资", selectedJob, "salaryDesc", "salary");
        appendJobField(builder, "城市", selectedJob, "cityName", "city", "areaDistrict");
        appendJobField(builder, "经验要求", selectedJob, "jobExperience", "experience", "experienceName");
        appendJobField(builder, "学历要求", selectedJob, "jobDegree", "degree", "degreeName");
        appendJobField(builder, "技能标签", selectedJob, "skills", "jobLabels", "labels");
        appendJobField(builder, "岗位描述", selectedJob, "jobRequire", "description", "jobDescription", "postDescription");
        return builder.toString();
    }

    private void appendJobField(StringBuilder builder, String label, Map<String, Object> job, String... keys) {
        for (String key : keys) {
            Object value = job.get(key);
            if (value == null) continue;
            String text = String.valueOf(value).trim();
            if (text.isEmpty() || "null".equals(text)) continue;
            if (text.length() > 400) text = text.substring(0, 400);
            builder.append(label).append(": ").append(text).append('\n');
            return;
        }
    }

    /**
     * 安全门控：仅当配置开关开启，且预判为高风险并建议拒绝时拦截。默认关闭，主链路行为与现状一致。
     */
    private boolean isSafetyGateBlocked(IntentResult preIntent) {
        if (!properties.isIntentSafetyGateEnabled() || preIntent == null) return false;
        return "high".equalsIgnoreCase(stringValue(preIntent.getRisk()))
                && "reject".equalsIgnoreCase(stringValue(preIntent.getNextAction()));
    }

    /** 把 agent-intent 预判结果整理为 runtime intent_hint 元数据，runtime 对未知元数据安全忽略。 */
    private Map<String, Object> intentHint(IntentResult preIntent) {
        if (preIntent == null) return Collections.emptyMap();
        Map<String, Object> hint = new LinkedHashMap<String, Object>();
        hint.put("domain", preIntent.getDomain());
        hint.put("intent", preIntent.getIntent());
        hint.put("confidence", preIntent.getConfidence());
        hint.put("risk", preIntent.getRisk());
        hint.put("needs_clarification", preIntent.isNeedsClarification());
        hint.put("next_action", preIntent.getNextAction());
        hint.put("secondary", preIntent.getSecondary());
        return hint;
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

    private boolean isCapabilityUnavailable(Map<String, Object> directive) {
        Object implementation = directive == null ? null : directive.get("implementation");
        Object statusValue = directive == null ? null : directive.get("implementation_status");
        if (implementation instanceof Map) {
            Object implemented = ((Map) implementation).get("implemented");
            if (Boolean.FALSE.equals(implemented)) return true;
            Object nestedStatus = ((Map) implementation).get("status");
            if (nestedStatus != null) statusValue = nestedStatus;
        }
        String status = stringValue(statusValue).toLowerCase(java.util.Locale.ROOT);
        return "planned".equals(status) || "unsupported".equals(status) || "not_implemented".equals(status);
    }

    private IntentResult intentFromRuntime(Map<String, Object> directive) {
        Object slots = directive.get("slots");
        Map<String, Object> slotMap = slots instanceof Map
                ? new LinkedHashMap<String, Object>((Map<String, Object>) slots)
                : new LinkedHashMap<String, Object>();
        Object secondary = directive.get("secondary");
        List<String> secondaryList = secondary instanceof List ? (List<String>) secondary : Collections.<String>emptyList();
        return new IntentResult(
                stringValue(directive.get("domain"), "unknown"),
                stringValue(directive.get("intent"), "unknown"),
                doubleValue(directive.get("confidence"), 0.0),
                secondaryList,
                stringValue(directive.get("risk"), "low"),
                booleanValue(firstPresent(directive, "needs_clarification", "needsClarification"), false),
                stringValue(firstPresent(directive, "next_action", "nextAction"), "clarify"),
                slotMap
        );
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

    /** 兼容 action 与 intent 两类能力匹配键。 */
    private boolean matchesCapability(String action, IntentResult intent, String... keys) {
        String intentName = intent == null ? "" : stringValue(intent.getIntent());
        for (String key : keys) {
            if (key.equals(action) || key.equals(intentName)) {
                return true;
            }
        }
        return false;
    }

    private String directiveAction(Map<String, Object> directive, IntentResult intent) {
        Object action = firstPresent(directive, "action", "next_action", "nextAction", "target_action");
        if (action instanceof Map) action = firstPresent((Map<String, Object>) action, "type", "name", "action");
        String value = stringValue(action);
        if (!value.isEmpty()) return value;
        return intent == null ? "runtime_managed" : stringValue(intent.getNextAction(), intent.getIntent());
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

    private String fallbackGeneralResumeMatchAnswer(ResumeRecord resume, String targetRole) {
        Map<String, Object> parsed = resume == null || resume.getParsed() == null ? Collections.<String, Object>emptyMap() : resume.getParsed();
        String role = stringValue(targetRole, "目标岗位");
        return "当前缺少具体 JD，以下为基于“" + role + "”通用岗位画像的参考判断，不作为真实岗位精确评分。\n\n"
                + role + "通常重点考察：大模型或 Agent/RAG 项目经验、后端工程能力、Prompt/Tool Calling、工作流编排、模型接口接入、数据处理和系统落地能力。\n\n"
                + "请重点检查简历中是否有 LLM 应用、RAG、Agent、工具调用、向量检索、Spring Boot/FastAPI、Python/Java 后端、异步任务和工程部署经历。相关项目需写清业务问题、个人职责、技术方案、异常处理、延迟优化和结果指标。\n\n"
                + "面试准备建议聚焦 RAG 流程、Agent Loop、Function Calling/Tool Calling、Prompt 设计、模型接口、向量库、评测可观测和 Java/Python 后端工程化。提供目标岗位 JD 后，可继续按真实职责逐条对照。";
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

    private List<Map<String, Object>> manualTargetJobs(String targetRole, String targetDescription, Map<String, Object> slots) {
        if (!hasSufficientUserProvidedJd(targetDescription)) return Collections.emptyList();
        Map<String, Object> job = new LinkedHashMap<String, Object>();
        job.put("id", "user_provided_jd");
        job.put("jobName", targetRole.isEmpty() ? "用户提供的目标岗位" : targetRole);
        job.put("jobDescription", targetDescription);
        job.put("cityName", slots == null ? null : slots.get("city"));
        job.put("salaryDesc", salaryText(slots));
        job.put("source", "user_provided_jd");
        return Collections.singletonList(job);
    }

    private boolean hasSufficientUserProvidedJd(String targetDescription) {
        String text = stringValue(targetDescription);
        return text.length() >= 30;
    }

    private String salaryText(Map<String, Object> slots) {
        if (slots == null) return "";
        Object min = slots.get("salary_min_k");
        Object max = slots.get("salary_max_k");
        if (min != null && max != null) return min + "-" + max + "K";
        if (min != null) return min + "K以上";
        return "";
    }

    private Map<String, Object> compactMatchDetail(Map<String, Object> match) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        Object matches = match == null ? null : match.get("matches");
        detail.put("count", matches instanceof List ? ((List) matches).size() : 0);
        if (matches instanceof List && !((List) matches).isEmpty()) detail.put("top", ((List) matches).get(0));
        return detail;
    }

    private String resumeMatchSummary(Map<String, Object> match) {
        Object matches = match == null ? null : match.get("matches");
        if (matches instanceof List && !((List) matches).isEmpty()) {
            Object first = ((List) matches).get(0);
            if (first instanceof Map) {
                Map row = (Map) first;
                String score = stringValue(row.get("score"));
                String confidence = stringValue(firstPresent(row, "score_confidence", "confidence"));
                String recommendation = stringValue(row.get("recommendation"));
                String suffix = recommendation.isEmpty() ? "" : "，结论：" + recommendation;
                if (!score.isEmpty()) return "简历匹配已完成，评分：" + score + (confidence.isEmpty() ? "" : "，置信度：" + confidence) + suffix + "。";
            }
        }
        return "简历匹配已完成，详情已更新到岗位匹配面板。";
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

    private void handleJobRecommend(SseEmitter emitter, String sessionId, ChatSessionState state, IntentResult intent) throws IOException {
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
        // 岗位列表与本轮推理过程统一异步落库，确保扫码搜索路径下首屏卡片即时呈现、不被持久化阻塞。
        saveStateAsync(state);
    }

    private List<Map<String, Object>> memorySummaries(List<Map<String, Object>> memories) {
        List<Map<String, Object>> rows = new java.util.ArrayList<Map<String, Object>>();
        if (memories == null) return rows;
        for (Map<String, Object> memory : memories) {
            Map<String, Object> item = new LinkedHashMap<String, Object>();
            item.put("content", truncate(stringValue(memory.get("content")), 180));
            item.put("source", firstPresent(memory, "source", "type"));
            item.put("scope", firstPresent(memory, "scope", "namespace"));
            rows.add(item);
        }
        return rows;
    }

    private Map<String, Object> memoryDetail(List<Map<String, Object>> memories) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        detail.put("count", memories == null ? 0 : memories.size());
        detail.put("memories", memorySummaries(memories));
        return detail;
    }

    private Map<String, Object> toolStatus(String id, String title, String status, String summary, Object detail) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", id);
        data.put("title", title);
        data.put("status", status);
        data.put("summary", summary);
        data.put("detail", detail);
        data.put("time", java.time.Instant.now().toString());
        return data;
    }

    private String summarizeRuntimeResult(Map<String, Object> result) {
        if (result == null || result.isEmpty()) return "空响应";
        Object error = firstPresent(result, "error", "message", "detail");
        if (error != null) return stringValue(error);
        Object status = firstPresent(result, "status", "stop_reason", "stopReason");
        if (status != null) return "status=" + stringValue(status);
        return "缺少 directive 字段";
    }

    private Map<String, Object> resultMetadata(List<Map<String, Object>> jobs) {
        Map<String, Object> metadata = new LinkedHashMap<String, Object>();
        if (jobs != null && !jobs.isEmpty()) metadata.put("jobCards", jobs);
        return metadata;
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
