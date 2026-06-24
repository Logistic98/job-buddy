package com.jobbuddy.backend.modules.job.dto.command;

/**
 * 收藏岗位分析命令对象，显式表达岗位标识与可选简历标识。
 */
public class JobFavoriteAnalysisCommand {
    private final String jobKey;
    private final String resumeId;

    private JobFavoriteAnalysisCommand(String jobKey, String resumeId) {
        this.jobKey = trimToNull(jobKey);
        this.resumeId = trimToNull(resumeId);
    }

    public static JobFavoriteAnalysisCommand of(String jobKey, String resumeId) {
        return new JobFavoriteAnalysisCommand(jobKey, resumeId);
    }

    public String getJobKey() {
        return jobKey;
    }

    public String getResumeId() {
        return resumeId;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
