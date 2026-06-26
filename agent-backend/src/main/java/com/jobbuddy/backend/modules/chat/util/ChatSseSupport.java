package com.jobbuddy.backend.modules.chat.util;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.vo.IntentResult;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.booleanValue;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.doubleValue;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.firstPresent;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.stringValue;

/**
 * 聊天 SSE 链路的无状态纯函数：意图/能力路由解析、岗位上下文拼装、工具事件累积、匹配结果摘要等。
 * 从 ChatSseServiceImpl 提取以收敛主流程体积，全部为无状态静态方法，行为与原私有方法保持一致，
 * 不触碰 SSE 下发、持久化执行器与任何实例状态。
 */
public final class ChatSseSupport {

    private ChatSseSupport() {
    }

    /**
     * 判定一条用户消息是否值得写入长期记忆，并返回长期记忆类型；普通对话返回 null（只进短期记忆）。
     * 约束类（排除/不要/不考虑/约束）优先于偏好类（偏好/优先/目标/期望/希望/喜欢/倾向）。
     */
    public static String classifyMemoryType(String message) {
        String text = message == null ? "" : message;
        if (text.contains("排除") || text.contains("不要") || text.contains("不考虑") || text.contains("约束")) return "constraint";
        if (text.contains("偏好") || text.contains("优先") || text.contains("目标") || text.contains("期望")
                || text.contains("希望") || text.contains("喜欢") || text.contains("倾向")) return "preference";
        return null;
    }

    /** 与 ChatSessionStore 保持一致的记忆噪声判定：只按稳定标识字段 id/name 过滤，避免展示文案命中“记忆”导致误删。 */
    public static boolean isMemoryNoiseEvent(Map<String, Object> event) {
        if (event == null) return false;
        StringBuilder builder = new StringBuilder();
        for (String key : new String[]{"id", "name"}) {
            Object value = event.get(key);
            if (value != null) builder.append(' ').append(String.valueOf(value).toLowerCase(Locale.ROOT));
        }
        String text = builder.toString();
        return text.contains("memory") || text.contains("记忆");
    }

    /**
     * 工具事件累积到内存会话状态（按 id 合并、过滤记忆噪声步骤），供本轮答案落库与刷新后回看推理过程使用。
     * 这里不直接写库，避免每个 tool_status 都触发一次 DB 写造成串行阻塞。
     */
    public static void accumulateToolEvent(ChatSessionState state, Map<String, Object> event) {
        if (state == null || event == null || event.get("id") == null) return;
        if (state.toolEvents == null) state.toolEvents = new ArrayList<Map<String, Object>>();
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

    public static Map<String, Object> toolStatus(String id, String title, String status, String summary, Object detail) {
        Map<String, Object> data = new LinkedHashMap<String, Object>();
        data.put("id", id);
        data.put("title", title);
        data.put("status", status);
        data.put("summary", summary);
        data.put("detail", detail);
        data.put("time", java.time.Instant.now().toString());
        return data;
    }

    /** 选中岗位分析：把岗位关键信息注入 Runtime 消息上下文，回答仍走常规问答持久化链路。 */
    public static String withSelectedJobContext(String message, Map<String, Object> selectedJob) {
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

    private static void appendJobField(StringBuilder builder, String label, Map<String, Object> job, String... keys) {
        for (String key : keys) {
            Object value = job.get(key);
            if (value == null) continue;
            String text = cleanJobFieldText(value);
            if (text.isEmpty() || "null".equals(text)) continue;
            if (text.length() > 400) text = text.substring(0, 400);
            builder.append(label).append(": ").append(text).append('\n');
            return;
        }
    }

    private static String cleanJobFieldText(Object value) {
        String raw = String.valueOf(value == null ? "" : value).replace("\r\n", "\n").replace('\r', '\n');
        StringBuilder builder = new StringBuilder();
        String[] lines = raw.split("\\n+");
        for (String line : lines) {
            String text = line == null ? "" : line.replace('\t', ' ').trim().replaceAll(" {2,}", " ");
            if (text.isEmpty()) continue;
            if (builder.length() > 0) builder.append('；');
            builder.append(text);
        }
        return builder.toString().trim();
    }

    /** 把 agent-intent 预判结果整理为 runtime intent_hint 元数据，runtime 对未知元数据安全忽略。 */
    public static Map<String, Object> intentHint(IntentResult preIntent) {
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

    @SuppressWarnings("unchecked")
    public static IntentResult intentFromRuntime(Map<String, Object> directive) {
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

    @SuppressWarnings("unchecked")
    public static boolean isCapabilityUnavailable(Map<String, Object> directive) {
        Object implementation = directive == null ? null : directive.get("implementation");
        Object statusValue = directive == null ? null : directive.get("implementation_status");
        if (implementation instanceof Map) {
            Object implemented = ((Map) implementation).get("implemented");
            if (Boolean.FALSE.equals(implemented)) return true;
            Object nestedStatus = ((Map) implementation).get("status");
            if (nestedStatus != null) statusValue = nestedStatus;
        }
        String status = stringValue(statusValue).toLowerCase(Locale.ROOT);
        return "planned".equals(status) || "unsupported".equals(status) || "not_implemented".equals(status);
    }

    /** 兼容 action 与 intent 两类能力匹配键。 */
    public static boolean matchesCapability(String action, IntentResult intent, String... keys) {
        String intentName = intent == null ? "" : stringValue(intent.getIntent());
        for (String key : keys) {
            if (key.equals(action) || key.equals(intentName)) {
                return true;
            }
        }
        return false;
    }

    @SuppressWarnings("unchecked")
    public static String directiveAction(Map<String, Object> directive, IntentResult intent) {
        Object action = firstPresent(directive, "action", "next_action", "nextAction", "target_action");
        if (action instanceof Map) action = firstPresent((Map<String, Object>) action, "type", "name", "action");
        String value = stringValue(action);
        if (!value.isEmpty()) return value;
        return intent == null ? "runtime_managed" : stringValue(intent.getNextAction(), intent.getIntent());
    }

    public static List<Map<String, Object>> manualTargetJobs(String targetRole, String targetDescription, Map<String, Object> slots) {
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

    public static boolean hasSufficientUserProvidedJd(String targetDescription) {
        return stringValue(targetDescription).length() >= 30;
    }

    private static String salaryText(Map<String, Object> slots) {
        if (slots == null) return "";
        Object min = slots.get("salary_min_k");
        Object max = slots.get("salary_max_k");
        if (min != null && max != null) return min + "-" + max + "K";
        if (min != null) return min + "K以上";
        return "";
    }

    public static Map<String, Object> compactMatchDetail(Map<String, Object> match) {
        Map<String, Object> detail = new LinkedHashMap<String, Object>();
        Object matches = match == null ? null : match.get("matches");
        detail.put("count", matches instanceof List ? ((List) matches).size() : 0);
        if (matches instanceof List && !((List) matches).isEmpty()) detail.put("top", ((List) matches).get(0));
        return detail;
    }

    public static String resumeMatchSummary(Map<String, Object> match) {
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

    public static String fallbackGeneralResumeMatchAnswer(ResumeRecord resume, String targetRole) {
        String role = stringValue(targetRole, "目标岗位");
        return "当前缺少具体 JD，以下为基于“" + role + "”通用岗位画像的参考判断，不作为真实岗位精确评分。\n\n"
                + role + "通常重点考察：大模型或 Agent/RAG 项目经验、后端工程能力、Prompt/Tool Calling、工作流编排、模型接口接入、数据处理和系统落地能力。\n\n"
                + "请重点检查简历中是否有 LLM 应用、RAG、Agent、工具调用、向量检索、Spring Boot/FastAPI、Python/Java 后端、异步任务和工程部署经历。相关项目需写清业务问题、个人职责、技术方案、异常处理、延迟优化和结果指标。\n\n"
                + "面试准备建议聚焦 RAG 流程、Agent Loop、Function Calling/Tool Calling、Prompt 设计、模型接口、向量库、评测可观测和 Java/Python 后端工程化。提供目标岗位 JD 后，可继续按真实职责逐条对照。";
    }

    public static String summarizeRuntimeResult(Map<String, Object> result) {
        if (result == null || result.isEmpty()) return "空响应";
        Object error = firstPresent(result, "error", "message", "detail");
        if (error != null) return stringValue(error);
        Object status = firstPresent(result, "status", "stop_reason", "stopReason");
        if (status != null) return "status=" + stringValue(status);
        return "缺少 directive 字段";
    }
}
