package com.jobbuddy.backend.modules.resume.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.LinkedHashMap;
import java.util.Map;

@Data
public class ResumeProfileRequest extends MapBackedDto {
    @SuppressWarnings("unchecked")
    public Map<String, Object> parsedPayload() {
        Object parsed = get("parsed");
        if (parsed instanceof Map) {
            return new LinkedHashMap<String, Object>((Map<String, Object>) parsed);
        }
        return toMap();
    }
}
