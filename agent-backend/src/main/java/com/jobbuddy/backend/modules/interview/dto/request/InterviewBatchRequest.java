package com.jobbuddy.backend.modules.interview.dto.request;

import lombok.Data;
import com.jobbuddy.backend.common.dto.MapBackedDto;

import java.util.Map;

@Data
public class InterviewBatchRequest extends MapBackedDto {
    public InterviewBatchRequest() {
    }

    public InterviewBatchRequest(Map<String, Object> fields) {
        super(fields);
    }

    public static InterviewBatchRequest from(Map<String, Object> fields) {
        return new InterviewBatchRequest(fields);
    }
}
