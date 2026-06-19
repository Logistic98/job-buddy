package com.jobbuddy.backend.modules.journey.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class JourneyAnalysisResponse extends MapBackedDto {
    public JourneyAnalysisResponse() {
    }

    public JourneyAnalysisResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static JourneyAnalysisResponse from(Map<String, Object> fields) {
        return new JourneyAnalysisResponse(fields);
    }
}
