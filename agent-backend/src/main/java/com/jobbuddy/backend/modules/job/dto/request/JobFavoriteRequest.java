package com.jobbuddy.backend.modules.job.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class JobFavoriteRequest extends MapBackedDto {
    public JobFavoriteRequest() {
    }

    public JobFavoriteRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static JobFavoriteRequest from(Map<String, Object> fields) {
        return new JobFavoriteRequest(fields);
    }

    public String jobKey() {
        return stringValue(firstPresent("jobKey", "favoriteKey", "securityId", "id", "jobId", "encryptJobId"));
    }

    public String resumeId() {
        return stringValue(get("resumeId"));
    }

    private Object firstPresent(String... keys) {
        for (String key : keys) {
            Object value = get(key);
            if (value != null && !String.valueOf(value).trim().isEmpty()) {
                return value;
            }
        }
        return null;
    }

    private String stringValue(Object value) {
        if (value == null) return null;
        String text = String.valueOf(value).trim();
        return text.isEmpty() ? null : text;
    }
}
