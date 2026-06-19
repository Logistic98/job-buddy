package com.jobbuddy.backend.modules.journey.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class JobTargetRequest extends MapBackedDto {
    public JobTargetRequest() {
    }

    public JobTargetRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static JobTargetRequest from(Map<String, Object> fields) {
        return new JobTargetRequest(fields);
    }
}
