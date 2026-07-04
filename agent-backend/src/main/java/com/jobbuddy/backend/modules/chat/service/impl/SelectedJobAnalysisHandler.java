package com.jobbuddy.backend.modules.chat.service.impl;

import com.jobbuddy.backend.modules.chat.entity.ChatSessionState;
import com.jobbuddy.backend.modules.chat.service.AgentIntegrationService;
import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;
import com.jobbuddy.backend.modules.resume.service.ResumeStorageService;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static com.jobbuddy.backend.modules.chat.util.ChatSseSupport.toolStatus;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.firstPresent;
import static com.jobbuddy.backend.modules.chat.util.ChatValueSupport.stringValue;

/**
 * 选中岗位分析：聊天岗位卡片上"分析此岗位"的确定性单岗位分析入口，
 * 负责岗位信息压缩、提示词构造与 Runtime 流式匹配分析。
 */
class SelectedJobAnalysisHandler {
    private final ChatSseEventSender sender;
    private final CurrentResumeLoader resumeLoader;
    private final ResumeStorageService resumeStorageService;
    private final AgentIntegrationService integrationService;
    private final RuntimeManagedRequestFactory requestFactory;

    SelectedJobAnalysisHandler(ChatSseEventSender sender,
                               CurrentResumeLoader resumeLoader,
                               ResumeStorageService resumeStorageService,
                               AgentIntegrationService integrationService,
                               RuntimeManagedRequestFactory requestFactory) {
        this.sender = sender;
        this.resumeLoader = resumeLoader;
        this.resumeStorageService = resumeStorageService;
        this.integrationService = integrationService;
        this.requestFactory = requestFactory;
    }

    void handle(final SseEmitter emitter, final String sessionId, ChatSessionState state, String rawMessage, Map<String, Object> selectedJob) throws IOException {
        Map<String, Object> startDetail = new LinkedHashMap<String, Object>();
        startDetail.put("job", compactSelectedJob(selectedJob));
        sender.sendToolStatus(emitter, sessionId, state, toolStatus("selected_job_analysis", "分析此岗位", "running", "正在读取当前简历并生成岗位匹配分析。", startDetail));

        ResumeRecord resume = resumeLoader.loadCurrentResume(state);
        if (resume == null) {
            sender.sendAssistant(emitter, sessionId, state, "请先选择或上传 PDF 简历，再分析此岗位与简历的匹配度。", Collections.<String, Object>singletonMap("selectedJob", compactSelectedJob(selectedJob)));
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
        metadata.put("personal_context", requestFactory.buildPersonalContext(rawMessage, null, state));

        final StringBuilder buffer = new StringBuilder();
        final StringBuilder reasoningBuffer = new StringBuilder();
        final String assistantId = "assistant_" + UUID.randomUUID().toString().replace("-", "").substring(0, 12);
        Map<String, Object> request = requestFactory.buildRuntimeManagedRequest(sessionId, prompt, "job-buddy", metadata, true);
        Map<String, Object> runtimeResult = integrationService.runRuntimeStream(request, new java.util.function.Consumer<String>() {
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
        }, new java.util.function.Consumer<String>() {
            @Override
            public void accept(String piece) {
                if (piece == null || piece.isEmpty()) return;
                reasoningBuffer.append(piece);
                try {
                    sender.sendReasoningDelta(emitter, sessionId, assistantId, piece);
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
        sender.sendToolStatus(emitter, sessionId, state, toolStatus(
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
        sender.sendAssistant(emitter, sessionId, state, answer, finalMeta);
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
}
