package com.jobbuddy.backend.modules.resume.service.impl;

import com.jobbuddy.backend.modules.resume.entity.ResumeRecord;

import java.nio.file.Path;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Runtime 工具结果落盘：将 resume_parse / resume_analyze 的调用结果写回简历记录，
 * 并在分析工具不可用时生成本地规则兜底报告。
 */
class ResumeToolResultApplier {

    Map<String, Object> analysisArgs(Path tempFile, ResumeRecord record) {
        Map<String, Object> args = new LinkedHashMap<String, Object>();
        args.put("file_path", tempFile.toString());
        args.put("parsed", record.getParsed() == null ? Collections.<String, Object>emptyMap() : record.getParsed());
        return args;
    }

    boolean isAnalyzeToolUnavailable(RuntimeException e) {
        return isAnalyzeToolUnavailable(e.getMessage());
    }

    boolean isAnalyzeToolUnavailable(String message) {
        String text = message == null ? "" : message;
        return text.contains("resume_analyze") && (text.contains("工具未找到") || text.contains("已禁用") || text.contains("404"));
    }

    void applyLocalAnalysisFallback(ResumeRecord record, String reason) {
        Map<String, Object> parsed = record.getParsed() == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(record.getParsed());
        Map<String, Object> analysis = new LinkedHashMap<String, Object>();
        analysis.put("overall_score", "-");
        analysis.put("summary", "已完成基础分析。Runtime 暂未启用 resume_analyze 工具，因此当前展示基于解析结果生成的兜底报告。" + (reason == null ? "" : " 原因：" + reason));
        analysis.put("advantages", java.util.Arrays.asList(
                "简历已成功上传并完成基础解析，可继续用于岗位匹配和问答上下文。",
                "候选人姓名、摘要、技能、经历等结构化字段已被提取，可作为后续优化依据。"
        ));
        analysis.put("disadvantages", java.util.Arrays.asList(
                "当前缺少大模型深度分析结果，暂无法给出更细粒度的优势、风险和面试深挖建议。"
        ));
        analysis.put("problems", java.util.Arrays.asList(
                "请启用 Runtime 的 resume_analyze 工具后重新点击开始分析，以获得完整报告。"
        ));
        analysis.put("interview_deep_dive_points", Collections.emptyList());
        analysis.put("layout_issues", Collections.emptyList());
        analysis.put("typo_issues", Collections.emptyList());
        analysis.put("action_items", java.util.Arrays.asList(
                "检查 agent-runtime 是否已重启并注册 ResumeAnalyzeTool。",
                "确认后端配置的 Runtime 地址指向最新运行实例。"
        ));
        parsed.put("analysis", analysis);
        record.setParsed(parsed);
    }

    void applyAnalysisResult(ResumeRecord record, Map<String, Object> result) {
        if (!Boolean.TRUE.equals(result.get("success"))) {
            String error = stringOf(result.get("error"));
            if (isAnalyzeToolUnavailable(error)) {
                applyLocalAnalysisFallback(record, error);
                return;
            }
            Map<String, Object> parsed = record.getParsed() == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(record.getParsed());
            Map<String, Object> analysis = new LinkedHashMap<String, Object>();
            analysis.put("summary", "简历分析失败: " + error);
            analysis.put("advantages", Collections.emptyList());
            analysis.put("disadvantages", Collections.emptyList());
            analysis.put("problems", Collections.emptyList());
            analysis.put("interview_deep_dive_points", Collections.emptyList());
            analysis.put("layout_issues", Collections.emptyList());
            analysis.put("typo_issues", Collections.emptyList());
            parsed.put("analysis", analysis);
            record.setParsed(parsed);
            return;
        }
        Object output = result.get("output");
        if (output instanceof Map) {
            Object analysis = ((Map) output).get("analysis");
            if (analysis instanceof Map) {
                Map<String, Object> parsed = record.getParsed() == null ? new LinkedHashMap<String, Object>() : new LinkedHashMap<String, Object>(record.getParsed());
                parsed.put("analysis", analysis);
                record.setParsed(parsed);
            }
        }
    }

    void applyParseResult(ResumeRecord record, Map<String, Object> result) {
        boolean success = Boolean.TRUE.equals(result.get("success"));
        if (!success) {
            record.setParseStatus("fail");
            record.setParseError(stringOf(result.get("error")));
            return;
        }
        Object output = result.get("output");
        if (output instanceof Map) {
            Object parsed = ((Map) output).get("resume");
            if (parsed instanceof Map) record.setParsed((Map<String, Object>) parsed);
        }
        record.setParseStatus("success");
        record.setParseError(null);
    }

    private String stringOf(Object value) { return value == null ? "" : String.valueOf(value); }
}
