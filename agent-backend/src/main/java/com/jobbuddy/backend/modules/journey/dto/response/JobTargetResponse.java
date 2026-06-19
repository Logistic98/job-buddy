package com.jobbuddy.backend.modules.journey.dto.response;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class JobTargetResponse extends MapBackedDto {
    public JobTargetResponse() {
    }

    public JobTargetResponse(Map<String, Object> fields) {
        super(fields);
    }

    public static JobTargetResponse from(Map<String, Object> fields) {
        return new JobTargetResponse(fields);
    }
}
