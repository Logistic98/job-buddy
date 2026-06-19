package com.jobbuddy.backend.modules.journey.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class JourneyAnalysisRequest extends MapBackedDto {
    public JourneyAnalysisRequest() {
    }

    public JourneyAnalysisRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static JourneyAnalysisRequest from(Map<String, Object> fields) {
        return new JourneyAnalysisRequest(fields);
    }
}
