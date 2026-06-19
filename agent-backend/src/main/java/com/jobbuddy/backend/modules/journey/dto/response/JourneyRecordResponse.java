package com.jobbuddy.backend.modules.journey.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class JourneyRecordResponse extends MapBackedDto {
    public JourneyRecordResponse() {
    }

    public JourneyRecordResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static JourneyRecordResponse from(Map<String, Object> fields) {
        return new JourneyRecordResponse(fields);
    }
}
