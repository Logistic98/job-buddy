package com.jobbuddy.backend.modules.journey.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class JourneyRecordRequest extends MapBackedDto {
    public JourneyRecordRequest() {
    }

    public JourneyRecordRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static JourneyRecordRequest from(Map<String, Object> fields) {
        return new JourneyRecordRequest(fields);
    }
}
